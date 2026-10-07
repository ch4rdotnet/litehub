package com.chardidathing.litehub.core.config

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

// pins are stored as "pbkdf2$iterations$salt$hash", a copied settings.json doesn't give the pin away
object Pin {

    // a pin set before this went up to 6 still works, a new one needs 6
    const val MIN_LENGTH = 6

    // slow enough to make guessing a pin offline tedious, quick enough on an a55 core
    private const val ITERATIONS = 20_000
    private const val SALT_BYTES = 16
    private const val KEY_BITS = 256

    fun hash(pin: String, random: SecureRandom = SecureRandom()): String {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        return format(ITERATIONS, salt, derive(pin, salt, ITERATIONS))
    }

    fun matches(pin: String, stored: String): Boolean {
        val parts = stored.split('$')
        if (parts.size != 4 || parts[0] != "pbkdf2") return false
        val iterations = parts[1].toIntOrNull() ?: return false
        val decoder = Base64.getDecoder()
        val salt = runCatching { decoder.decode(parts[2]) }.getOrNull() ?: return false
        val expected = runCatching { decoder.decode(parts[3]) }.getOrNull() ?: return false
        return MessageDigest.isEqual(expected, derive(pin, salt, iterations))
    }

    private fun derive(pin: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, KEY_BITS)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    private fun format(iterations: Int, salt: ByteArray, hash: ByteArray): String {
        val encoder = Base64.getEncoder()
        return "pbkdf2$$iterations$${encoder.encodeToString(salt)}$${encoder.encodeToString(hash)}"
    }
}
