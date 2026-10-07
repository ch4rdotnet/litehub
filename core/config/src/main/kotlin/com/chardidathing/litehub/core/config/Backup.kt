package com.chardidathing.litehub.core.config

import com.chardidathing.litehub.core.model.CompanionRegistration
import com.chardidathing.litehub.core.model.DeviceSettings
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

// a whole hub as a zip. dashboards, sources and settings go in with the secrets taken out, the
// secrets go in a file of their own sealed with a passphrase, or don't go in at all
object Backup {

    const val FORMAT = 1
    const val MIN_PASSPHRASE = 8

    // what only the hub should know, plus what ha and dlna know it by. restoring these makes
    // the hub take over from the one the backup came from
    data class Secrets(
        val pin: String?,
        val companion: CompanionRegistration?,
        val dlnaUuid: String?,
        val haToken: String?,
        val immichKey: String?,
    )

    // a hub's files as they are. config and sources are null when the hub has never saved one
    data class Hub(val config: String?, val sources: String?, val settings: DeviceSettings, val haUrl: String?, val haToken: String?)

    // a read backup. settings come without secrets, they're only opened with the passphrase
    class Contents internal constructor(
        val app: String,
        val created: String,
        val config: String?,
        val sources: String?,
        val settings: DeviceSettings,
        val haUrl: String?,
        private val sealed: JsonObject?,
    ) {
        val hasSecrets get() = sealed != null

        fun secrets(passphrase: String): Secrets {
            val s = sealed ?: throw ConfigException("this backup has no secrets in it")
            return decodeSecrets(open(s, passphrase))
        }
    }

    private const val MANIFEST = "backup.json"
    private const val CONFIG = "config.json"
    private const val SOURCES = "sources.json"
    private const val SETTINGS = "settings.json"
    private const val HA = "ha.json"
    private const val SECRETS = "secrets.json"

    // a backup is a few small json files, anything bigger isn't one of ours
    private const val MAX_ENTRY_BYTES = 1 shl 20
    private const val MAX_ENTRIES = 16

    // slow on purpose, a stolen backup gets guessed at offline. about a second on an a55
    private const val ITERATIONS = 200_000
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val KEY_BITS = 256
    private const val TAG_BITS = 128
    private const val KDF = "pbkdf2-sha256"

    // passphrase null leaves the secrets out
    fun write(hub: Hub, app: String, created: String, passphrase: String?, out: OutputStream, random: SecureRandom = SecureRandom()) {
        if (passphrase != null && passphrase.length < MIN_PASSPHRASE) {
            throw ConfigException("the passphrase needs at least $MIN_PASSPHRASE characters")
        }
        val manifest = obj("format" to JsonPrimitive(FORMAT), "app" to JsonPrimitive(app), "created" to JsonPrimitive(created), "secrets" to JsonPrimitive(passphrase != null))
        ZipOutputStream(out).use { zip ->
            fun put(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            put(MANIFEST, manifest.toString())
            hub.config?.let { put(CONFIG, it) }
            hub.sources?.let { put(SOURCES, it) }
            put(SETTINGS, SettingsCodec.encode(stripped(hub.settings)))
            hub.haUrl?.let { put(HA, obj("url" to JsonPrimitive(it)).toString()) }
            if (passphrase != null) put(SECRETS, seal(encodeSecrets(secretsOf(hub)), passphrase, random).toString())
        }
    }

    // sources and settings are checked here, config is the app's to check (it knows the themes)
    fun read(input: InputStream): Contents {
        val files = unzip(input)
        val manifest = json(files[MANIFEST] ?: throw ConfigException("this isn't a litehub backup"), MANIFEST)
        val format = (manifest["format"] as? JsonPrimitive)?.intOrNull ?: throw ConfigException("this isn't a litehub backup")
        if (format > FORMAT) throw ConfigException("this backup is from a newer litehub, update this one first")
        val settings = SettingsCodec.decode(files[SETTINGS] ?: throw ConfigException("the backup has no settings.json"))
        files[SOURCES]?.let(SourcesCodec::decode)
        val ha = files[HA]?.let { json(it, HA)["url"] as? JsonPrimitive }?.contentOrNull
        val sealed = files[SECRETS]?.let { json(it, SECRETS) }
        return Contents(
            app = text(manifest["app"]).orEmpty(),
            created = text(manifest["created"]).orEmpty(),
            config = files[CONFIG],
            sources = files[SOURCES],
            settings = settings,
            haUrl = ha,
            sealed = sealed,
        )
    }

    // the backup laid over this hub. without its secrets this hub keeps its own, a token only
    // ever goes back to the url it was entered for, so a moved server needs it typed again
    fun restore(backup: Contents, secrets: Secrets?, current: Hub): Hub {
        val here = current.settings
        val s = backup.settings
        val immich = s.screensaver.photos?.immich?.let { i ->
            val kept = here.screensaver.photos?.immich?.takeIf { sameUrl(it.url, i.url) }?.apiKey
            i.copy(apiKey = secrets?.immichKey ?: kept.orEmpty())
        }
        val settings = s.copy(
            pin = if (secrets != null) secrets.pin else here.pin,
            companion = if (secrets != null) secrets.companion else here.companion,
            dlna = s.dlna.copy(uuid = if (secrets != null) secrets.dlnaUuid else here.dlna.uuid),
            screensaver = s.screensaver.copy(photos = s.screensaver.photos?.copy(immich = immich)),
        )
        val token = backup.haUrl?.let { url ->
            secrets?.haToken ?: current.haToken?.takeIf { current.haUrl != null && sameUrl(current.haUrl, url) }.orEmpty()
        }
        return Hub(backup.config ?: current.config, backup.sources ?: current.sources, settings, backup.haUrl, token)
    }

    private fun sameUrl(a: String, b: String) = a.trimEnd('/') == b.trimEnd('/')

    private fun stripped(s: DeviceSettings) = s.copy(
        pin = null,
        companion = null,
        dlna = s.dlna.copy(uuid = null),
        screensaver = s.screensaver.copy(photos = s.screensaver.photos?.let { p -> p.copy(immich = p.immich?.copy(apiKey = "")) }),
    )

    private fun secretsOf(hub: Hub) = Secrets(
        pin = hub.settings.pin,
        companion = hub.settings.companion,
        dlnaUuid = hub.settings.dlna.uuid,
        haToken = hub.haToken,
        immichKey = hub.settings.screensaver.photos?.immich?.apiKey,
    )

    private fun encodeSecrets(s: Secrets): JsonObject = obj(
        "pin" to str(s.pin),
        "companion" to (s.companion?.let { obj("deviceId" to JsonPrimitive(it.deviceId), "webhookId" to JsonPrimitive(it.webhookId), "name" to JsonPrimitive(it.name)) } ?: JsonNull),
        "dlnaUuid" to str(s.dlnaUuid),
        "haToken" to str(s.haToken),
        "immichKey" to str(s.immichKey),
    )

    private fun decodeSecrets(o: JsonObject): Secrets {
        val c = o["companion"] as? JsonObject
        val companion = c?.let {
            CompanionRegistration(
                deviceId = text(it["deviceId"]) ?: throw ConfigException("the backup's secrets aren't complete"),
                webhookId = text(it["webhookId"]) ?: throw ConfigException("the backup's secrets aren't complete"),
                name = text(it["name"]) ?: throw ConfigException("the backup's secrets aren't complete"),
            )
        }
        return Secrets(text(o["pin"]), companion, text(o["dlnaUuid"]), text(o["haToken"]), text(o["immichKey"]))
    }

    private fun seal(plain: JsonObject, passphrase: String, random: SecureRandom): JsonObject {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(passphrase, salt, ITERATIONS), GCMParameterSpec(TAG_BITS, iv))
        val data = cipher.doFinal(plain.toString().toByteArray(Charsets.UTF_8))
        val b64 = Base64.getEncoder()
        return obj(
            "kdf" to JsonPrimitive(KDF),
            "iterations" to JsonPrimitive(ITERATIONS),
            "salt" to JsonPrimitive(b64.encodeToString(salt)),
            "iv" to JsonPrimitive(b64.encodeToString(iv)),
            "data" to JsonPrimitive(b64.encodeToString(data)),
        )
    }

    private fun open(sealed: JsonObject, passphrase: String): JsonObject {
        if (text(sealed["kdf"]) != KDF) throw ConfigException("the backup's secrets are sealed in a way this litehub doesn't know")
        val iterations = (sealed["iterations"] as? JsonPrimitive)?.intOrNull?.takeIf { it in 1..ITERATIONS * 10 }
            ?: throw ConfigException("the backup's secrets are damaged")
        val b64 = Base64.getDecoder()
        fun bytes(key: String) = text(sealed[key])?.let { runCatching { b64.decode(it) }.getOrNull() } ?: throw ConfigException("the backup's secrets are damaged")
        val plain = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(passphrase, bytes("salt"), iterations), GCMParameterSpec(TAG_BITS, bytes("iv")))
            cipher.doFinal(bytes("data"))
        } catch (e: GeneralSecurityException) {
            // gcm can't tell a wrong passphrase from a changed file, both fail the tag
            throw ConfigException("that passphrase doesn't open this backup's secrets", e)
        }
        return json(String(plain, Charsets.UTF_8), SECRETS)
    }

    private fun key(passphrase: String, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, iterations, KEY_BITS)
        return SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
    }

    // only the names we know are kept, nothing is ever written to disk under a name from the zip
    private fun unzip(input: InputStream): Map<String, String> {
        val files = HashMap<String, String>()
        try {
            ZipInputStream(input).use { zip ->
                var count = 0
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (++count > MAX_ENTRIES) throw ConfigException("this isn't a litehub backup, it has too many files")
                    if (entry.isDirectory || entry.name !in KNOWN) continue
                    files[entry.name] = String(capped(zip, entry.name), Charsets.UTF_8)
                }
            }
        } catch (e: IOException) {
            throw ConfigException("the backup couldn't be read as a zip", e)
        }
        return files
    }

    private val KNOWN = setOf(MANIFEST, CONFIG, SOURCES, SETTINGS, HA, SECRETS)

    private fun capped(input: InputStream, name: String): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_BYTES)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (out.size() + n > MAX_ENTRY_BYTES) throw ConfigException("$name in the backup is too big")
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private const val BUFFER_BYTES = 8 * 1024

    private fun json(text: String, name: String): JsonObject = try {
        ConfigCodec.json.parseToJsonElement(text) as? JsonObject
    } catch (e: SerializationException) {
        null
    } ?: throw ConfigException("$name in the backup isn't valid")

    private fun obj(vararg pairs: Pair<String, JsonElement>) = JsonObject(mapOf(*pairs))

    private fun str(s: String?): JsonElement = s?.let(::JsonPrimitive) ?: JsonNull

    private fun text(e: JsonElement?) = (e as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
}
