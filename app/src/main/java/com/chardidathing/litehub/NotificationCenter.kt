package com.chardidathing.litehub

import com.chardidathing.litehub.core.model.HubNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

// the hub's recent notifications, newest first, kept on disk so a reboot doesn't lose them
class NotificationCenter(private val dir: File, private val scope: CoroutineScope) {

    private val file get() = File(dir, FILE)
    private val serializer = ListSerializer(HubNotification.serializer())
    private val json = Json { ignoreUnknownKeys = true }
    private val _items = MutableStateFlow(read())
    val items: StateFlow<List<HubNotification>> = _items

    fun add(title: String?, message: String, tag: String?): HubNotification {
        val n = HubNotification(UUID.randomUUID().toString(), title, message, System.currentTimeMillis(), tag)
        // a tagged notification replaces the last one with that tag, like ha's companion app
        update { list -> (listOf(n) + list.filter { tag == null || it.tag != tag }).take(KEEP) }
        return n
    }

    fun remove(id: String) = update { list -> list.filter { it.id != id } }

    fun removeTag(tag: String) = update { list -> list.filter { it.tag != tag } }

    fun clear() = update { emptyList() }

    private fun update(change: (List<HubNotification>) -> List<HubNotification>) {
        val next = change(_items.value)
        if (next == _items.value) return
        _items.value = next
        scope.launch(Dispatchers.IO) { runCatching { file.writeAtomic(json.encodeToString(serializer, next)) } }
    }

    private fun read(): List<HubNotification> =
        runCatching { json.decodeFromString(serializer, file.readText()) }.getOrDefault(emptyList())

    private companion object {
        const val FILE = "notifications.json"
        const val KEEP = 50
    }
}
