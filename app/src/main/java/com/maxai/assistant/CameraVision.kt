package com.maxai.assistant

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Headless single-photo capture (no preview UI needed), front or back camera. */
object CameraVision {

    /** Captures one JPEG frame. "front" or "back". Returns null on any failure or timeout. */
    @Suppress("MissingPermission")
    fun capture(c: Context, facing: String): ByteArray? {
        val mgr = c.getSystemService(CameraManager::class.java) ?: return null
        val wantFront = facing.lowercase().contains("front") || facing.lowercase().contains("aage") || facing.lowercase().contains("selfie")
        val id = mgr.cameraIdList.firstOrNull {
            val f = mgr.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING)
            if (wantFront) f == CameraCharacteristics.LENS_FACING_FRONT else f == CameraCharacteristics.LENS_FACING_BACK
        } ?: mgr.cameraIdList.firstOrNull() ?: return null

        val thread = HandlerThread("max-cam").apply { start() }
        val handler = Handler(thread.looper)
        var result: ByteArray? = null
        val done = CountDownLatch(1)
        var reader: ImageReader? = null
        var device: CameraDevice? = null

        try {
            reader = ImageReader.newInstance(1280, 960, ImageFormat.JPEG, 1)
            reader.setOnImageAvailableListener({ r ->
                val img: Image? = r.acquireLatestImage()
                try {
                    if (img != null) {
                        val buf = img.planes[0].buffer
                        val bytes = ByteArrayOutputStream()
                        val arr = ByteArray(buf.remaining())
                        buf.get(arr)
                        bytes.write(arr)
                        result = bytes.toByteArray()
                    }
                } finally {
                    img?.close()
                    done.countDown()
                }
            }, handler)

            mgr.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(cam: CameraDevice) {
                    device = cam
                    try {
                        val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                            addTarget(reader!!.surface)
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                            set(CaptureRequest.JPEG_ORIENTATION, 90)
                        }.build()
                        cam.createCaptureSession(listOf(reader!!.surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                runCatching { session.capture(req, null, handler) }
                                    .onFailure { done.countDown() }
                            }
                            override fun onConfigureFailed(session: CameraCaptureSession) { done.countDown() }
                        }, handler)
                    } catch (e: Exception) {
                        done.countDown()
                    }
                }
                override fun onDisconnected(cam: CameraDevice) { done.countDown() }
                override fun onError(cam: CameraDevice, error: Int) { done.countDown() }
            }, handler)

            done.await(8, TimeUnit.SECONDS)
        } catch (e: Exception) {
            // leave result null
        } finally {
            runCatching { device?.close() }
            runCatching { reader?.close() }
            thread.quitSafely()
        }
        return result
    }
}
