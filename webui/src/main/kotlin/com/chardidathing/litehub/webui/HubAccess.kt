package com.chardidathing.litehub.webui

// what the web server may read and change on the hub, the app implements it. texts are json
interface HubAccess {
    val editorEnabled: Boolean
    val statusEnabled: Boolean

    // the device pin's hash, null means the editor is open on the lan
    val pinHash: String?

    fun config(): String

    // validated first, the old one is kept as config.prev.json, the dashboard reloads
    fun saveConfig(text: String): Result<Unit>

    // calendars and feeds, the layout editor names them in widget settings
    fun sources(): String

    // the hub's own settings as the form both settings screens draw,
    // {"sections": [...], "values": {...}, "newItems": {...}, "palette": ["#rrggbb", ...]}. secrets come back blank
    fun settings(): String

    // flat key to value edits, checked like the device's own settings screen checks them
    fun saveSettings(text: String): Result<Unit>

    // a settings section's button (ha's home location), the values it fills in as json
    suspend fun settingsAction(id: String): Result<String>

    fun schemas(): String

    suspend fun entities(): Result<String>

    // a png of the hub as it looks now, null when nothing is on screen
    fun preview(): ByteArray?

    suspend fun status(): String

    // css custom properties from the hub's theme, so the editor looks like the hub
    fun themeCss(): String
}
