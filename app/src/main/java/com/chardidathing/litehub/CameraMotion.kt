package com.chardidathing.litehub

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import kotlin.math.abs

// someone walking past wakes the screen. a tiny greyscale picture a couple of times a second,
// compared cell by cell with the one before. nothing is stored or sent anywhere
class CameraMotion(context: Context, private val onMotion: () -> Unit) {

    private val cameras = context.getSystemService(CameraManager::class.java)
    private var thread: HandlerThread? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var previous: IntArray? = null
    private var lastFrame = 0L

    val available get() = cameras.cameraIdList.isNotEmpty()

    @SuppressLint("MissingPermission") // the controller checks before starting
    fun start() {
        if (device != null) return
        // the front camera faces the room on a wall panel, otherwise whatever there is
        val id = cameras.cameraIdList.firstOrNull { cameras.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT }
            ?: cameras.cameraIdList.firstOrNull() ?: return
        val t = HandlerThread("motion").apply { start() }
        thread = t
        val handler = Handler(t.looper)
        val r = ImageReader.newInstance(WIDTH, HEIGHT, ImageFormat.YUV_420_888, 2)
        reader = r
        r.setOnImageAvailableListener({ ir ->
            val image = ir.acquireLatestImage() ?: return@setOnImageAvailableListener
            image.use {
                val now = System.currentTimeMillis()
                if (now - lastFrame < FRAME_MS) return@use
                lastFrame = now
                val plane = it.planes[0]
                compare(grid(plane.buffer, plane.rowStride))
            }
        }, handler)
        cameras.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                device = camera
                val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(r.surface)
                    set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(LOW_FPS, LOW_FPS))
                }
                @Suppress("DEPRECATION")
                camera.createCaptureSession(listOf(r.surface), object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        session = s
                        s.setRepeatingRequest(request.build(), null, handler)
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) = stop()
                }, handler)
            }

            override fun onDisconnected(camera: CameraDevice) = stop()

            override fun onError(camera: CameraDevice, error: Int) {
                AppLog.add("camera motion stopped, camera error $error")
                stop()
            }
        }, handler)
    }

    fun stop() {
        session?.close()
        device?.close()
        reader?.close()
        thread?.quitSafely()
        session = null
        device = null
        reader = null
        thread = null
        previous = null
    }

    // the frame as a few dozen average brightnesses
    private fun grid(buffer: java.nio.ByteBuffer, stride: Int): IntArray {
        val cells = IntArray(COLS * ROWS)
        val cw = WIDTH / COLS
        val ch = HEIGHT / ROWS
        for (y in 0 until HEIGHT) for (x in 0 until WIDTH) {
            cells[(y / ch).coerceAtMost(ROWS - 1) * COLS + (x / cw).coerceAtMost(COLS - 1)] += buffer.get(y * stride + x).toInt() and BYTE
        }
        val per = cw * ch
        for (i in cells.indices) cells[i] /= per
        return cells
    }

    private fun compare(cells: IntArray) {
        val before = previous
        previous = cells
        if (before == null) return
        val changed = cells.indices.count { abs(cells[it] - before[it]) > CELL_CHANGE }
        if (changed * PERCENT / cells.size >= CHANGED_PERCENT) onMotion()
    }

    private companion object {
        const val WIDTH = 160
        const val HEIGHT = 120
        const val COLS = 16
        const val ROWS = 12
        const val LOW_FPS = 5
        const val FRAME_MS = 500L
        // a cell has to change this much brightness (of 255) to count, sensor noise stays under it
        const val CELL_CHANGE = 25
        const val CHANGED_PERCENT = 6
        const val PERCENT = 100
        const val BYTE = 0xFF
    }
}
