package dev.colorcode.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.*
import android.util.Size
import android.view.*
import android.widget.*

/** Camera2 acquisition only. Captured pixels go through the same custom decoder as imported images. */
class CameraActivity : Activity() {
    private lateinit var texture: TextureView
    private lateinit var message: TextView
    private lateinit var capture: Button
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var images: ImageReader? = null
    private var previewSurface: Surface? = null
    private var previewSize = Size(1280, 720)
    private var sensorOrientation = 90
    private var front = false
    private var afMode = CaptureRequest.CONTROL_AF_MODE_OFF
    @Volatile private var active = false
    @Volatile private var generation = 0
    private var opening = false
    private val captures = CaptureMatcher<ByteArray>()

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 16, 16, 16) }
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(bars.left + 16, bars.top + 16, bars.right + 16, bars.bottom + 16)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(insets.systemWindowInsetLeft + 16, insets.systemWindowInsetTop + 16, insets.systemWindowInsetRight + 16, insets.systemWindowInsetBottom + 16)
            }
            insets
        }
        message = TextView(this).apply { text = "Experimental camera reader\nKeep all four corners visible. Hold still, then capture."; textSize = 16f; setPadding(8, 8, 8, 16) }
        root.addView(message)
        texture = TextureView(this).apply { contentDescription = "Camera preview" }
        root.addView(texture, LinearLayout.LayoutParams(-1, 0, 1f))
        capture = Button(this).apply {
            text = "Capture & read"; isEnabled = false; isAllCaps = false
            setOnClickListener {
                isEnabled = false
                message.text = "Focusing and capturing…"
                handler?.post { takePhoto() }
            }
        }
        root.addView(capture)
        root.addView(Button(this).apply { text = "Cancel"; isAllCaps = false; setOnClickListener { finish() } })
        setContentView(root)
        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) { openCamera() }
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) { transformPreview() }
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
        }
    }

    override fun onResume() {
        super.onResume()
        generation++
        active = true
        thread = HandlerThread("ColorCodeCamera").apply { start() }
        handler = Handler(thread!!.looper)
        if (texture.isAvailable) openCamera()
    }
    override fun onPause() {
        active = false; generation++; capture.isEnabled = false
        handler?.post {
            session?.close(); session = null
            device?.close(); device = null
            images?.close(); images = null
            previewSurface?.release(); previewSurface = null
            opening = false
            captures.pendingRequest?.let { captures.cancel(it) }
        }
        thread?.quitSafely()
        thread?.join(1000)
        thread = null; handler = null
        super.onPause()
    }

    private fun openCamera() {
        if (!active || opening || device != null || !texture.isAvailable) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            fail("Camera permission is missing. Return to the reader and enable it."); return
        }
        opening = true
        val cameraHandler = handler ?: return
        val epoch = generation
        try {
            val manager = getSystemService(CameraManager::class.java)
            val id = manager.cameraIdList.firstOrNull {
                manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: manager.cameraIdList.firstOrNull() ?: throw IllegalStateException("No camera found")
            val info = manager.getCameraCharacteristics(id)
            sensorOrientation = info.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            front = info.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            val af = info.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
            afMode = when {
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE in af -> CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                CaptureRequest.CONTROL_AF_MODE_AUTO in af -> CaptureRequest.CONTROL_AF_MODE_AUTO
                else -> CaptureRequest.CONTROL_AF_MODE_OFF
            }
            val config = info.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: throw IllegalStateException("Camera has no image streams")
            val jpegSizes = config.getOutputSizes(ImageFormat.JPEG).toList()
            val photo = jpegSizes.filter { it.width.toLong() * it.height <= 5_000_000 }
                .maxByOrNull { it.width.toLong() * it.height } ?: jpegSizes.minBy { it.width.toLong() * it.height }
            val previews = config.getOutputSizes(SurfaceTexture::class.java).toList()
            previewSize = previews.filter { it.width <= 1920 && it.height <= 1080 }
                .minByOrNull { kotlin.math.abs(it.width.toDouble() / it.height - photo.width.toDouble() / photo.height) + kotlin.math.abs(it.width - 1280) / 5000.0 }
                ?: previews.minBy { it.width.toLong() * it.height }
            transformPreview()
            images = ImageReader.newInstance(photo.width, photo.height, ImageFormat.JPEG, 2).apply {
                setOnImageAvailableListener({ source ->
                    while (true) {
                        val image = try { source.acquireNextImage() } catch (_: IllegalStateException) { null } ?: break
                        image.use {
                            if (active && generation == epoch && captures.pendingRequest != null) {
                                val buffer = image.planes[0].buffer
                                // Unknown/oversized frames fail closed; the request times out.
                                if (buffer.remaining() <= 24 * 1024 * 1024) {
                                    val bytes = ByteArray(buffer.remaining()); buffer.get(bytes)
                                    captures.frame(image.timestamp, bytes)?.let { completeCapture(it, epoch) }
                                }
                            }
                        }
                    }
                }, cameraHandler)
            }
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (!active || generation != epoch) { camera.close(); return }
                    opening = false
                    device = camera; startPreview(camera, cameraHandler, epoch)
                }
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (generation == epoch) { device = null; opening = false; fail("Camera disconnected. Return and retry.") }
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    if (generation == epoch) { device = null; opening = false; fail("Camera error $error. Return and retry.") }
                }
            }, Handler(Looper.getMainLooper())) // Late opens must still be closed after the worker stops.
        } catch (e: Exception) { opening = false; fail("Camera unavailable: ${e.message}") }
    }

    @Suppress("DEPRECATION")
    private fun startPreview(camera: CameraDevice, cameraHandler: Handler, epoch: Int) {
        try {
            val surfaceTexture = texture.surfaceTexture ?: throw IllegalStateException("Preview surface unavailable")
            surfaceTexture.setDefaultBufferSize(previewSize.width, previewSize.height)
            val surface = Surface(surfaceTexture); previewSurface = surface
            val jpegSurface = images?.surface ?: throw IllegalStateException("Capture stream unavailable")
            camera.createCaptureSession(listOf(surface, jpegSurface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(value: CameraCaptureSession) {
                    if (!active || generation != epoch || device !== camera) { value.close(); return }
                    session = value
                    try {
                        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(surface); set(CaptureRequest.CONTROL_AF_MODE, afMode)
                            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                        }.build()
                        value.setRepeatingRequest(request, null, cameraHandler)
                        runOnUiThread { if (active && generation == epoch) capture.isEnabled = true }
                    } catch (e: Exception) { fail("Cannot start preview: ${e.message}") }
                }
                override fun onConfigureFailed(value: CameraCaptureSession) { value.close(); fail("Camera does not support this stream combination.") }
            }, cameraHandler)
        } catch (e: Exception) { fail("Preview failed: ${e.message}") }
    }

    private fun takePhoto() {
        val camera = device; val current = session; val target = images?.surface
        if (!active || camera == null || current == null || target == null || captures.pendingRequest != null) { fail("Camera is not ready.", true); return }
        val sequence = captures.begin()
        val epoch = generation
        try {
            // Trigger AF when supported. Continuous AF has already been running during preview.
            if (afMode != CaptureRequest.CONTROL_AF_MODE_OFF) {
                val focus = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(previewSurface!!); set(CaptureRequest.CONTROL_AF_MODE, afMode)
                    set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
                }.build()
                current.capture(focus, null, handler)
            }
            handler?.postDelayed({
                if (!active || generation != epoch || !captures.isPending(sequence) || device !== camera) return@postDelayed
                try {
                    @Suppress("DEPRECATION")
                    val degrees = when (windowManager.defaultDisplay.rotation) { Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0 }
                    val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                        setTag(sequence)
                        addTarget(target); set(CaptureRequest.CONTROL_AF_MODE, afMode)
                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                        set(CaptureRequest.JPEG_ORIENTATION, (sensorOrientation + (if (front) degrees else -degrees) + 360) % 360)
                        set(CaptureRequest.JPEG_QUALITY, 100.toByte())
                    }.build()
                    current.capture(request, object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureStarted(session: CameraCaptureSession, request: CaptureRequest, timestamp: Long, frameNumber: Long) {
                            if (active && generation == epoch && request.tag == sequence)
                                captures.started(sequence, timestamp)?.let { completeCapture(it, epoch) }
                        }
                        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                            if (active && generation == epoch && request.tag == sequence)
                                result.get(CaptureResult.SENSOR_TIMESTAMP)?.let { timestamp ->
                                    captures.started(sequence, timestamp)?.let { completeCapture(it, epoch) }
                                }
                        }
                        override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                            if (active && generation == epoch && request.tag == sequence && captures.cancel(sequence))
                                fail("Camera capture failed. Try again.", true)
                        }
                    }, handler)
                } catch (e: Exception) { if (captures.cancel(sequence)) fail("Capture failed: ${e.message}", true) }
            }, 500)
            handler?.postDelayed({
                if (active && generation == epoch && captures.cancel(sequence)) fail("Capture timed out. Try again.", true)
            }, 10_000)
        } catch (e: Exception) { if (captures.cancel(sequence)) fail("Focus failed: ${e.message}", true) }
    }

    private fun completeCapture(match: CaptureMatcher.Match<ByteArray>, epoch: Int) {
        if (!active || generation != epoch) return
        try {
            val file = CameraFiles.create(filesDir, match.value)
            runOnUiThread {
                if (active && generation == epoch) {
                    setResult(RESULT_OK, Intent().putExtra(CameraFiles.RESULT_NAME, file.name))
                    finish()
                } else file.delete()
            }
        } catch (e: Exception) { fail("Capture failed: ${e.message}", true) }
    }

    private fun transformPreview() {
        if (!texture.isAvailable) return
        @Suppress("DEPRECATION")
        val rotation = windowManager.defaultDisplay.rotation
        val view = RectF(0f, 0f, texture.width.toFloat(), texture.height.toFloat())
        val swap = (sensorOrientation - rotation * 90 + 360) % 180 != 0
        val bw = if (swap) previewSize.height else previewSize.width
        val bh = if (swap) previewSize.width else previewSize.height
        val buffer = RectF(0f, 0f, bw.toFloat(), bh.toFloat())
        buffer.offset(view.centerX() - buffer.centerX(), view.centerY() - buffer.centerY())
        val matrix = Matrix()
        matrix.setRectToRect(view, buffer, Matrix.ScaleToFit.FILL)
        val scale = maxOf(view.width() / bw, view.height() / bh)
        matrix.postScale(scale, scale, view.centerX(), view.centerY())
        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270)
            matrix.postRotate((90 * (rotation - 2)).toFloat(), view.centerX(), view.centerY())
        else if (rotation == Surface.ROTATION_180) matrix.postRotate(180f, view.centerX(), view.centerY())
        texture.setTransform(matrix)
    }
    private fun fail(text: String, retry: Boolean = false) {
        runOnUiThread { if (active) { message.text = text; capture.isEnabled = retry && device != null && session != null } }
    }
}
