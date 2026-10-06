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

    fun sources(): String

    fun saveSources(text: String): Result<Unit>

    // url and whether a token is set, the token itself never leaves the device
    fun ha(): String

    // a null token keeps the one already there
    fun saveHa(url: String, token: String?): Result<Unit>

    fun schemas(): String

    suspend fun entities(): Result<String>

    // a png of the hub as it looks now, null when nothing is on screen
    fun preview(): ByteArray?

    suspend fun status(): String

    // css custom properties from the hub's theme, so the editor looks like the hub
    fun themeCss(): String
}
