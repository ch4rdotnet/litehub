package com.chardidathing.litehub

import android.net.wifi.WifiManager
import com.chardidathing.litehub.core.model.DlnaSettings
import com.chardidathing.litehub.dlna.DlnaServer
import com.chardidathing.litehub.dlna.Renderer

// the hub as a dlna renderer, on and off with settings.dlna. lives with the process like the
// web server, so audio keeps going while the dashboard is behind another app
class DlnaHost(private val app: LitehubApp) {

    val playback = MediaPlayback(app)
    val renderer = Renderer(playback, AppLog::add).also { playback.events = it::onPlayer }

    private var server: DlnaServer? = null
    private var running: DlnaSettings? = null
    // most wifi drivers drop multicast without one, which is all of ssdp
    private val multicast: WifiManager.MulticastLock? =
        app.applicationContext.getSystemService(WifiManager::class.java)?.createMulticastLock("litehub dlna")?.apply { setReferenceCounted(false) }

    // blocking (sockets), call it off the main thread
    @Synchronized
    fun apply() {
        val s = app.settings.dlna
        val uuid = s.uuid
        if (s == running && server != null) return
        stop()
        if (!s.enabled || uuid == null) return
        val ip = Lan.address() ?: return AppLog.add("dlna has no network to answer on")
        val next = DlnaServer(renderer, app.http, LitehubApp.DEVICE_NAME, uuid, BuildConfig.VERSION_NAME, AppLog::add)
        next.start(ip, s.port).fold(
            {
                server = next
                running = s
                multicast?.acquire()
                AppLog.add("dlna renderer on ${ip.hostAddress}:${s.port}")
            },
            { AppLog.add("dlna renderer couldn't start, ${it.message}") },
        )
    }

    private fun stop() {
        server?.let {
            it.stop()
            renderer.stopHere()
            AppLog.add("dlna renderer off")
        }
        server = null
        running = null
        multicast?.release()
    }
}
