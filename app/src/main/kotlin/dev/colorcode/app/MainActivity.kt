package dev.colorcode.app

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.WindowInsets
import android.widget.*
import dev.colorcode.core.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var work: RetainedWork<MainActivity>
    private val actions = mutableListOf<Button>()
    private lateinit var text: EditText
    private lateinit var size: Spinner
    private lateinit var source: TextView
    private lateinit var counter: TextView
    private lateinit var status: TextView
    private lateinit var preview: ImageView
    private lateinit var result: TextView
    private lateinit var maker: LinearLayout
    private lateinit var reader: LinearLayout
    private lateinit var savePng: Button
    private lateinit var sharePng: Button
    private lateinit var saveData: Button
    private lateinit var retryPhoto: Button
    private var retryCapture: String? = null
    private var fileBytes: ByteArray? = null
    private var fileName = "Binary file"
    private var madeBytes: ByteArray? = null
    private var madeGrid = 57
    private var pngBitmap: Bitmap? = null
    private var decoded: ByteArray? = null
    private var correctedBytes = 0
    private var page = 0
    private val ink = Color.rgb(25, 42, 61)
    private val muted = Color.rgb(82, 98, 113)

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        @Suppress("UNCHECKED_CAST", "DEPRECATION")
        val retained = lastNonConfigurationInstance as? RetainedWork<MainActivity>
        work = retained ?: run {
            val executor = Executors.newSingleThreadExecutor()
            val main = Handler(Looper.getMainLooper())
            RetainedWork<MainActivity>({ executor.execute(it) }, { main.post(it) }, { message -> setWorkStatus(message) }, { executor.shutdown() })
        }
        val scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        val root = column().apply { setPadding(dp(20), dp(16), dp(20), dp(32)) }
        scroll.addView(root)
        // Target 36 draws edge-to-edge. Keep controls outside system bars and the keyboard.
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        setContentView(scroll)
        root.addView(label("ColorCode", 30, ink))
        root.addView(label("More color. More room for your data.", 16, muted))
        root.addView(label("OFFLINE  •  CUSTOM FORMAT v1", 12, Color.rgb(0, 116, 104)))
        val tabs = row()
        tabs.addView(button("Make") { showPage(0) }, weight())
        tabs.addView(button("Read") { showPage(1) }, weight())
        root.addView(tabs)
        status = label("Ready. Everything stays on this device.", 14, muted).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        root.addView(status)
        maker = card(); reader = card()
        root.addView(maker); root.addView(reader)
        buildMaker(); buildReader()
        text.setText(state?.getString("text") ?: "Color is data. 🌈")
        fileBytes = state?.getByteArray("file"); fileName = state?.getString("name") ?: fileName
        size.setSelection(state?.getInt("size") ?: 0)
        updateSource()
        state?.getByteArray("decoded")?.let { showDecoded(Decoded(it, state.getInt("corrected"))) }
        madeBytes = state?.getByteArray("made")
        madeGrid = state?.getInt("madeGrid") ?: 57
        retryCapture = state?.getString("retryCapture")
        madeBytes?.let { bytes ->
            val grid = madeGrid
            // Preview restoration is silent so it cannot hide a retained decode's result/error.
            background("", { makeBitmap(bytes, grid) }, discard = { it.recycle() }) {
                // An older restored preview must not replace an encode completed during rotation.
                if (madeBytes?.contentEquals(bytes) == true && madeGrid == grid) showBitmap(it, bytes, grid, announce = false)
                else it.recycle()
            }
        }
        showPage(state?.getInt("page") ?: 0)
    }

    private fun buildMaker() {
        maker.addView(label("Make a color code", 23, ink))
        maker.addView(label("Write text or choose a small binary file. Export the result as a lossless PNG.", 15, muted))
        text = EditText(this).apply {
            hint = "Text to encode"; minLines = 3; maxLines = 8
            filters = arrayOf(android.text.InputFilter.LengthFilter(ColorCode.MAX_PAYLOAD))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            contentDescription = "UTF-8 payload text"
        }
        maker.addView(text)
        counter = label("", 13, muted); maker.addView(counter)
        text.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { updateSource() }
            override fun afterTextChanged(s: Editable?) {}
        })
        val sourceButtons = row()
        sourceButtons.addView(button("Choose file") { openDocument("*/*", OPEN_BINARY) }, weight())
        sourceButtons.addView(button("Use text") { fileBytes = null; updateSource() }, weight())
        maker.addView(sourceButtons)
        source = label("Text • UTF-8", 14, ink); maker.addView(source)
        maker.addView(label("Code size", 14, ink))
        size = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                ColorCode.grids.map { "$it × $it  •  ${ColorCode.capacity(it)} bytes" })
            contentDescription = "Grid size and maximum byte capacity"
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) {}
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { updateSource() }
            }
        }
        maker.addView(size)
        maker.addView(label("Square • appearance v1", 14, muted))
        maker.addView(button("Create code") {
            val bytes = fileBytes?.copyOf() ?: text.text.toString().toByteArray(Charsets.UTF_8)
            val grid = ColorCode.grids[size.selectedItemPosition]
            background("Encoding ${bytes.size} bytes…", { makeBitmap(bytes, grid) }, discard = { it.recycle() }) { showBitmap(it, bytes, grid) }
        })
        maker.addView(label("Last created code", 14, ink))
        preview = ImageView(this).apply {
            adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Generated ColorCode image"
            setBackgroundColor(Color.WHITE)
        }
        maker.addView(preview, LinearLayout.LayoutParams(-1, dp(320)))
        val exports = row()
        savePng = button("Save PNG") { createDocument("image/png", "colorcode-v1.png", SAVE_PNG) }.apply { isEnabled = false }
        sharePng = button("Share PNG") { share() }.apply { isEnabled = false }
        exports.addView(savePng, weight()); exports.addView(sharePng, weight()); maker.addView(exports)
        maker.addView(label("Custom reader required. This is not a standard QR code. Keep the white border and original colors.", 13, muted))
    }

    private fun buildReader() {
        reader.addView(label("Read a color code", 23, ink))
        reader.addView(label("Import an image, or take a photo with the custom camera reader.", 15, muted))
        val buttons = row()
        buttons.addView(button("Open image") { openDocument("image/*", OPEN_IMAGE) }, weight())
        buttons.addView(button("Camera") {
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) openCamera()
            else requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION)
        }, weight())
        reader.addView(buttons)
        retryPhoto = button("Retry last photo") { readCapture() }.apply { isEnabled = false }
        reader.addView(retryPhoto)
        reader.addView(label("Camera reading is experimental. Use even light, keep all four corners visible and fill the frame. Imported PNGs are the best-tested path.", 13, muted))
        result = label("Decoded text or a binary preview will appear here.", 16, ink).apply { setTextIsSelectable(true) }
        reader.addView(result)
        saveData = button("Save exact bytes") { createDocument("application/octet-stream", "decoded.bin", SAVE_DATA) }.apply { isEnabled = false }
        reader.addView(saveData)
    }

    private fun updateSource() {
        if (!::counter.isInitialized) return
        val count = fileBytes?.size ?: text.text.toString().toByteArray(Charsets.UTF_8).size
        val cap = if (::size.isInitialized) ColorCode.capacity(ColorCode.grids[size.selectedItemPosition.coerceAtLeast(0)]) else ColorCode.capacity(57)
        counter.text = "$count / $cap bytes" + if (count > cap) " • choose a larger grid or less data" else ""
        counter.setTextColor(if (count > cap) Color.rgb(170, 45, 45) else muted)
        if (::source.isInitialized) source.text = if (fileBytes != null) "File: $fileName ($count bytes)" else "Text • UTF-8"
    }
    private fun showPage(index: Int) { page = index; maker.visibility = if (index == 0) View.VISIBLE else View.GONE; reader.visibility = if (index == 1) View.VISIBLE else View.GONE }
    private fun showBitmap(bitmap: Bitmap, bytes: ByteArray, grid: Int, announce: Boolean = true) {
        pngBitmap?.recycle(); pngBitmap = bitmap; madeBytes = bytes; madeGrid = grid
        preview.setImageDrawable(android.graphics.drawable.BitmapDrawable(resources, bitmap).apply { isFilterBitmap = false })
        savePng.isEnabled = true; sharePng.isEnabled = true
        if (announce) status.text = "Created ${bytes.size} bytes • $grid × $grid • format 1 / appearance 1. PNG is ready."
    }
    private fun showDecoded(value: Decoded) {
        decoded = value.bytes
        correctedBytes = value.correctedBytes
        val utf8 = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(value.bytes)).toString()
        } catch (_: java.nio.charset.CharacterCodingException) { null }
        val hex = value.bytes.take(256).joinToString(" ") { "%02x".format(it.toInt() and 255) }
        result.text = "${value.bytes.size} exact bytes • ${value.correctedBytes} bytes corrected\n" +
            if (utf8 != null) "UTF-8\n\n${utf8.replace("\u0000", "\\0")}" else "Binary • hex preview\n\n$hex${if (value.bytes.size > 256) "\n…" else ""}"
        saveData.isEnabled = true
        status.text = "Decoded successfully. Checksum verified."
    }

    @Deprecated("Framework activity results retained for a dependency-free offline build")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        if (requestCode == CAMERA) {
            showPage(1)
            val name = data?.getStringExtra(CameraFiles.RESULT_NAME) ?: return
            try { CameraFiles.resolve(filesDir, name) }
            catch (_: java.io.IOException) { status.text = "Camera returned an invalid capture."; return }
            retryCapture = name
            readCapture()
            return
        }
        val uri = data?.data ?: return
        val resolver = applicationContext.contentResolver
        when (requestCode) {
            OPEN_BINARY -> background("Opening file…", {
                resolver.openInputStream(uri)?.use { readLimited(it, ColorCode.MAX_PAYLOAD) }
                    ?: throw CodeException("Cannot open file")
            }) { fileBytes = it; fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "Binary file"; updateSource(); status.text = "File loaded. Choose a grid and create the code." }
            OPEN_IMAGE -> {
                clearDecoded()
                background("Reading image…", {
                    val bytes = resolver.openInputStream(uri)?.use { readLimited(it, 24 * 1024 * 1024) } ?: throw CodeException("Cannot open image")
                    readImage(bytes)
                }) { showDecoded(it) }
            }
            SAVE_PNG -> {
                // Recreate from saved payload state: the file picker may outlive this Activity.
                val bytes = madeBytes?.copyOf() ?: return
                val grid = madeGrid
                background("Saving PNG…", {
                    val bitmap = makeBitmap(bytes, grid)
                    try {
                        resolver.openOutputStream(uri, "wt")?.use { if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) throw CodeException("PNG encoding failed") }
                            ?: throw CodeException("Cannot write PNG")
                    } finally { bitmap.recycle() }
                }) { status.text = "Lossless PNG saved." }
            }
            SAVE_DATA -> {
                val bytes = decoded?.copyOf() ?: return
                background("Saving bytes…", {
                    resolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: throw CodeException("Cannot write file")
                }) { status.text = "Saved ${bytes.size} exact bytes." }
            }
        }
    }
    private fun readCapture() {
        val name = retryCapture ?: return
        val directory = filesDir
        clearDecoded()
        background("Reading camera capture…", {
            val capture = CameraFiles.resolve(directory, name)
            readImage(capture.inputStream().use { readLimited(it, 24 * 1024 * 1024) })
        }) {
            showDecoded(it)
            // Delete only after successful delivery. Detached/failed work retains retry input.
            CameraFiles.delete(directory, name)
            if (retryCapture == name) retryCapture = null
        }
    }
    private fun clearDecoded() {
        decoded = null; correctedBytes = 0; saveData.isEnabled = false
        result.text = "No verified payload from this attempt yet."
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) openCamera()
            else status.text = "Camera permission was denied. You can still open an image."
        }
    }
    private fun openCamera() {
        try { startActivityForResult(Intent(this, CameraActivity::class.java), CAMERA) }
        catch (e: Exception) { status.text = "Camera unavailable: ${e.message}" }
    }
    private fun openDocument(type: String, request: Int) = launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { this.type = type; addCategory(Intent.CATEGORY_OPENABLE) }, request)
    private fun createDocument(type: String, name: String, request: Int) = launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply { this.type = type; addCategory(Intent.CATEGORY_OPENABLE); putExtra(Intent.EXTRA_TITLE, name) }, request)
    private fun launch(intent: Intent, request: Int) {
        try { startActivityForResult(intent, request) } catch (_: android.content.ActivityNotFoundException) { status.text = "No file picker is available on this device." }
    }
    private fun share() {
        val bytes = madeBytes?.copyOf() ?: return
        val grid = madeGrid
        val app = applicationContext
        background("Preparing PNG…", {
            val store = SharedPngStore(File(app.filesDir, "shared-png"))
            store.cleanup { app.revokeUriPermission(Uri.parse(it), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val bitmap = makeBitmap(bytes, grid)
            try {
                store.create { if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) throw CodeException("PNG encoding failed") }
            } finally { bitmap.recycle() }
        }) { shared ->
            val uri = Uri.parse(shared)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"; putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri("ColorCode PNG", uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try { startActivity(Intent.createChooser(send, "Share lossless PNG")); status.text = "PNG ready to share. This link expires in 7 days; Save PNG keeps a copy." }
            catch (_: android.content.ActivityNotFoundException) { status.text = "No sharing app is available. Use Save PNG." }
        }
    }
    private fun <T> background(message: String, task: () -> T, discard: (T) -> Unit = {},
                               done: MainActivity.(T) -> Unit) {
        work.submit(message, task, done, { status.text = it.message ?: "Operation failed. Retry the input." }, discard)
    }
    private fun setWorkStatus(message: String?) {
        val idle = message == null
        actions.forEach { it.isEnabled = idle }
        savePng.isEnabled = idle && pngBitmap != null; sharePng.isEnabled = idle && pngBitmap != null
        saveData.isEnabled = idle && decoded != null
        retryPhoto.isEnabled = idle && retryCapture != null
        if (!message.isNullOrEmpty()) status.text = message
    }
    override fun onSaveInstanceState(out: Bundle) {
        work.detach(this)
        out.putString("text", text.text.toString()); out.putByteArray("file", fileBytes); out.putString("name", fileName)
        out.putInt("size", size.selectedItemPosition); out.putInt("page", page); out.putByteArray("decoded", decoded)
        out.putInt("corrected", correctedBytes)
        out.putByteArray("made", madeBytes); out.putInt("madeGrid", madeGrid)
        out.putString("retryCapture", retryCapture)
        super.onSaveInstanceState(out)
    }
    @Deprecated("Framework non-configuration retention keeps the offline build dependency-free")
    override fun onRetainNonConfigurationInstance(): Any = work
    override fun onResume() { super.onResume(); work.attach(this) }
    override fun onPause() { work.detach(this); super.onPause() }
    override fun onDestroy() {
        work.detach(this)
        if (!isChangingConfigurations) work.close()
        preview.setImageDrawable(null); pngBitmap?.recycle(); pngBitmap = null
        super.onDestroy()
    }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    private fun weight() = LinearLayout.LayoutParams(0, -2, 1f)
    private fun card() = column().apply {
        setPadding(dp(16), dp(12), dp(16), dp(16))
        background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(18).toFloat() }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
    }
    private fun label(value: String, sp: Int, color: Int) = TextView(this).apply {
        text = value; textSize = sp.toFloat(); setTextColor(color); setPadding(0, dp(6), 0, dp(6))
    }
    private fun button(value: String, action: () -> Unit) = Button(this).apply {
        text = value; isAllCaps = false; setOnClickListener { action() }; actions.add(this)
    }
    companion object {
        private const val OPEN_BINARY = 10; private const val OPEN_IMAGE = 11; private const val SAVE_PNG = 12
        private const val SAVE_DATA = 13; private const val CAMERA = 14; private const val CAMERA_PERMISSION = 15
    }
}

private fun makeBitmap(bytes: ByteArray, grid: Int): Bitmap {
    val raster = SquareRenderer.render(ColorCode.encode(bytes, grid), 8)
    return Bitmap.createBitmap(raster.pixels, raster.width, raster.height, Bitmap.Config.ARGB_8888)
}

private fun readImage(data: ByteArray): Decoded {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth.toLong() * bounds.outHeight > 200_000_000)
        throw CodeException("Unsupported image or excessive image dimensions")
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
    val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply {
        inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888
    }) ?: throw CodeException("Cannot open image")
    try {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return ColorCode.decode(Raster(bitmap.width, bitmap.height, pixels))
    } finally { bitmap.recycle() }
}

internal fun readLimited(input: InputStream, maximum: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = input.read(buffer, 0, minOf(buffer.size, maximum + 1 - output.size()))
        if (count < 0) break
        if (count == 0) throw CodeException("Input stream stopped making progress")
        output.write(buffer, 0, count)
        if (output.size() > maximum) throw CodeException("File exceeds the $maximum byte limit")
    }
    return output.toByteArray()
}
