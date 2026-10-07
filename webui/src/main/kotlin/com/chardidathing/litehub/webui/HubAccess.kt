package com.chardidathing.litehub.webui

import com.chardidathing.litehub.core.config.PinThrottle

// what the web server may read and change on the hub, the app implements it. texts are json
interface HubAccess {
    val editorEnabled: Boolean
    val statusEnabled: Boolean

    // the device pin's hash, null means the editor is open on the lan
    val pinHash: String?

    fun config(): String

    // validated first, the dashboard reloads
    fun saveConfig(text: String): Result<Unit>

    // calendars and feeds, the layout editor names them in widget settings
    fun sources(): String

    // the hub's own settings as the form both settings screens draw,
    // {"sections": [...], "values": {...}, "newItems": {...}, "palette": ["#rrggbb", ...]}. secrets come back blank
    fun settings(): String

    // flat key to value edits, checked like the device's own settings screen checks them
    fun saveSettings(text: String): Result<Unit>

    // the whole hub as a zip from {"passphrase": "..."}, the secrets sealed with it when it's there
    fun backup(request: String): Result<ByteArray>

    // {"zip": base64, "passphrase": "..."} laid over the hub, its secrets only with the passphrase
    fun restore(request: String): Result<Unit>

    // a settings section's button (ha's home location), the values it fills in as json
    suspend fun settingsAction(id: String): Result<String>

    // the built in themes, fully filled in, so the theme editor can show what a theme inherits
    fun presetThemes(): String

    // the same cooldown the device's own pin pad counts against
    val pinThrottle: PinThrottle

    fun schemas(): String

    suspend fun entities(): Result<String>

    // one tile drawn by the hub as a png, from the editor's copy of its placement, page and theme.
    // null when there's nothing on screen to size it against
    suspend fun tilePreview(body: String): ByteArray?

    // a png of the hub as it looks now, null when nothing is on screen
    fun preview(): ByteArray?

    suspend fun status(): String

    // css custom properties from the hub's theme, so the editor looks like the hub
    fun themeCss(): String
}
