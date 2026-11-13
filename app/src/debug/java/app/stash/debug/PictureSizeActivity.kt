package app.stash.debug

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import app.stash.StashApp
import app.stash.data.AttachmentSource
import app.stash.domain.Dbg
import app.stash.domain.DbgCode
import app.stash.domain.DbgComponent
import app.stash.domain.PictureSize
import app.stash.domain.WebpChunks
import app.stash.pictures.PictureException
import app.stash.pictures.PictureInput
import app.stash.pictures.Prepared
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Card 16b phone check. DEBUG BUILDS ONLY. Pick one picture with the system photo picker, prepare it at Small,
 * Standard and Large through the real [app.stash.pictures.PicturePreparer], show width x height, bytes, milliseconds
 * and the WebP chunk list for each, and let the owner look at each stored copy full-screen with pinch to zoom (is the
 * smallest print readable?). "Discard all" deletes the three files; leaving the screen does too. Nothing is added to
 * any note and nothing decrypted is written to disk (the viewer decodes from memory).
 *
 * Start from the launcher-less way: adb shell am start -n app.stash/app.stash.debug.PictureSizeActivity
 */
class PictureSizeActivity : ComponentActivity() {
    private val container get() = (application as StashApp).container
    private val prepared = LinkedHashMap<PictureSize, Prepared>()
    private lateinit var out: TextView
    private lateinit var show: LinearLayout
    private lateinit var zoom: ZoomImage

    private val picker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) runAll(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 48, 24, 24) }
        root.addView(Button(this).apply {
            text = "Pick a picture"
            setOnClickListener { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        })
        show = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (s in PictureSize.entries) {
            show.addView(Button(this).apply { text = "View ${s.name.lowercase()}"; setOnClickListener { view(s) } })
        }
        show.addView(Button(this).apply { text = "Discard all"; setOnClickListener { discardAll() } })
        root.addView(show)
        out = TextView(this).apply { textSize = 13f; setTextIsSelectable(true) }
        root.addView(ScrollView(this).apply { addView(out) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        zoom = ZoomImage(this).apply { visibility = android.view.View.GONE }
        val frame = android.widget.FrameLayout(this)
        frame.addView(root)
        frame.addView(zoom, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(frame)
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (zoom.visibility == android.view.View.VISIBLE) zoom.visibility = android.view.View.GONE else finish()
            }
        })
    }

    private fun runAll(uri: android.net.Uri) {
        discardAll()
        out.text = "Working..."
        lifecycleScope.launch {
            val sb = StringBuilder()
            for (size in PictureSize.entries) {
                val t0 = SystemClock.elapsedRealtime()
                try {
                    val p = container.picturePreparer.prepare(PictureInput.FromUri(uri), null, AttachmentSource.PICKER, size)
                    val ms = SystemClock.elapsedRealtime() - t0
                    prepared[size] = p
                    val a = p.attachment
                    val plain = withContext(Dispatchers.IO) {
                        container.attachmentStore.open(a.fileName, a.key, a.id).use { it.readBytes() }
                    }
                    val chunks = WebpChunks.list(plain)
                    val long = maxOf(a.width ?: 0, a.height ?: 0)
                    Dbg.event(DbgComponent.PICTURE, DbgCode.STORED, a.byteSize, long, ms = ms)
                    sb.append("${size.name}: ${a.width} x ${a.height}  ${a.kind.name.lowercase()}  ${a.byteSize} bytes  $ms ms\n")
                    sb.append("  chunks: ${chunks?.joinToString(" ") { "[$it]" } ?: "NOT WEBP"}  image data only: ${WebpChunks.onlyImageData(plain)}\n")
                } catch (e: PictureException) {
                    sb.append("${size.name}: failed, code ${e.failure.code} (${e.failure.name})\n")
                }
            }
            out.text = sb.toString()
        }
    }

    private fun view(size: PictureSize) {
        val a = prepared[size]?.attachment ?: return
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                container.attachmentStore.open(a.fileName, a.key, a.id).use { BitmapFactory.decodeStream(it) }
            }
            zoom.setBitmapFit(bmp)
            zoom.visibility = android.view.View.VISIBLE
        }
    }

    private fun discardAll() {
        zoom.visibility = android.view.View.GONE
        zoom.setImageDrawable(null)
        for (p in prepared.values) container.picturePreparer.discard(p)
        prepared.clear()
    }

    override fun onDestroy() {
        discardAll()
        super.onDestroy()
    }

    /** A full-screen image with pinch to zoom and drag. The back button closes it. */
    private class ZoomImage(context: android.content.Context) : ImageView(context) {
        private val m = Matrix()
        private var bmp: Bitmap? = null
        private var lastX = 0f
        private var lastY = 0f
        private val detector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                m.postScale(d.scaleFactor, d.scaleFactor, d.focusX, d.focusY)
                imageMatrix = m
                return true
            }
        })

        init {
            scaleType = ScaleType.MATRIX
            setBackgroundColor(0xFF000000.toInt())
        }

        fun setBitmapFit(b: Bitmap) {
            bmp = b
            setImageBitmap(b)
            post {
                val s = minOf(width.toFloat() / b.width, height.toFloat() / b.height)
                m.reset()
                m.postScale(s, s)
                imageMatrix = m
            }
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            detector.onTouchEvent(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { lastX = e.x; lastY = e.y }
                MotionEvent.ACTION_MOVE -> if (e.pointerCount == 1 && !detector.isInProgress) {
                    m.postTranslate(e.x - lastX, e.y - lastY)
                    imageMatrix = m
                    lastX = e.x; lastY = e.y
                } else {
                    lastX = e.x; lastY = e.y
                }
                MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_POINTER_DOWN -> { lastX = e.x; lastY = e.y }
            }
            return true
        }
    }
}
