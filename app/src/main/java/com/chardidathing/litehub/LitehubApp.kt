package com.chardidathing.litehub

import android.app.Application
import com.chardidathing.litehub.source.ha.EntityCache
import com.chardidathing.litehub.source.ha.EntityRepository
import com.chardidathing.litehub.source.ha.HaCredentials
import com.chardidathing.litehub.ui.components.Icons
import com.chardidathing.litehub.ui.tokens.Fonts
import okhttp3.OkHttpClient
import java.io.File

// hand wired singletons. every one of these reads disk, so first touch them off the main thread
class LitehubApp : Application() {

    val fonts by lazy { Fonts(assets) }

    val icons by lazy { Icons(assets) }

    val ha by lazy {
        EntityRepository(
            credentials = HaCredentials.load(File(filesDir, HA_FILE)),
            http = OkHttpClient(),
            cache = EntityCache(this),
        )
    }

    companion object {
        const val HA_FILE = "ha.json"
        const val CONFIG_FILE = "config.json"
    }
}
