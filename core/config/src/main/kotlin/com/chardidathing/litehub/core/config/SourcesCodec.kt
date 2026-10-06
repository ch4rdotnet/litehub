package com.chardidathing.litehub.core.config

import com.chardidathing.litehub.core.model.Sources
import kotlinx.serialization.SerializationException

object SourcesCodec {

    const val VERSION = 1

    fun decode(text: String): Sources {
        val sources = try {
            ConfigCodec.json.decodeFromString(Sources.serializer(), text)
        } catch (e: SerializationException) {
            throw ConfigException("sources.json isn't valid, ${e.summary()}", e)
        } catch (e: IllegalArgumentException) {
            throw ConfigException("sources.json isn't valid, ${e.summary()}", e)
        }
        if (sources.version > VERSION) {
            throw ConfigException("sources.json version ${sources.version} is newer than this app supports ($VERSION)")
        }
        check(sources)
        return sources
    }

    // what every sources.json has to hold to, however it was written
    fun check(sources: Sources) {
        val ids = sources.calendars.map { it.id } + sources.feeds.map { it.id }
        ids.groupBy { it }.filter { it.value.size > 1 }.keys.firstOrNull()?.let {
            throw ConfigException("two sources share the id \"$it\"")
        }
        for (c in sources.calendars) {
            if ((c.url == null) == (c.entity == null)) {
                throw ConfigException("calendar \"${c.id}\" needs either a url or an entity")
            }
            if (c.refreshMinutes < 1) throw ConfigException("calendar \"${c.id}\" refreshes too often")
        }
        for (f in sources.feeds) {
            if (f.refreshMinutes < 1) throw ConfigException("feed \"${f.id}\" refreshes too often")
        }
    }

    fun encode(sources: Sources): String = ConfigCodec.json.encodeToString(Sources.serializer(), sources)
}
