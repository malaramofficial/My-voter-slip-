package `in`.malaramofficial.myvoterslip

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.BitmapFactory
import android.provider.MediaStore
import android.content.ContentValues
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val recognizer by lazy {
        TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
    }
    private lateinit var status: TextView
    private lateinit var query: EditText
    private lateinit var results: LinearLayout
    private var voterLines: List<String> = emptyList()
    private val prefs by lazy { getSharedPreferences("voter_slip_local", MODE_PRIVATE) }
    private val pickPdf = 701

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        voterLines = prefs.getString("ocr_text", "").orEmpty()
            .split("\n").map { it.trim() }.filter { it.isNotBlank() }
        buildScreen()
        renderResults(voterLines)
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(12))
            setBackgroundColor(Color.rgb(247, 249, 252))
        }
        val title = TextView(this).apply {
            text = "मेरी वोटर पर्ची"
            textSize = 26f
            setTextColor(Color.rgb(22, 53, 92))
            gravity = Gravity.CENTER
        }
        val subtitle = TextView(this).apply {
            text = "ऑफलाइन वोटर सूची • हिंदी OCR"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.DKGRAY)
        }
        val import = Button(this).apply {
            text = "PDF वोटर सूची चुनें"
            setOnClickListener {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/pdf"
                }
                startActivityForResult(intent, pickPdf)
            }
        }
        status = TextView(this).apply {
            text = "PDF चुनकर OCR शुरू करें। डेटा इसी फोन में रखा जाएगा।"
            textSize = 14f
            setPadding(0, dp(8), 0, dp(8))
        }
        query = EditText(this).apply {
            hint = "नाम, पिता का नाम, मकान नंबर खोजें"
            setSingleLine(true)
            textSize = 16f
        }
        val search = Button(this).apply {
            text = "खोजें"
            setOnClickListener { filterResults() }
        }
        val clear = Button(this).apply {
            text = "खोज साफ करें"
            setOnClickListener {
                query.setText("")
                renderResults(voterLines)
            }
        }
        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(results) }

        root.addView(title, matchWrap())
        root.addView(subtitle, matchWrap())
        root.addView(import, matchWrap())
        root.addView(status, matchWrap())
        root.addView(query, matchWrap())
        root.addView(search, matchWrap())
        root.addView(clear, matchWrap())
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        setContentView(root)
    }

    @Deprecated("Deprecated in Android but still supported for document picker")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == pickPdf && resultCode == RESULT_OK) {
            data?.data?.let { uri -> runOcr(uri) }
        }
    }

    private fun runOcr(uri: Uri) {
        status.text = "PDF पढ़ी जा रही है… कृपया ऐप खुला रखें।"
        worker.execute {
            try {
                val file = File(cacheDir, "voter-import.pdf")
                contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "PDF नहीं खुली" }
                    FileOutputStream(file).use { output -> input.copyTo(output) }
                }
                val allText = StringBuilder()
                val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                descriptor.use { pfd ->
                    PdfRenderer(pfd).use { renderer ->
                        for (index in 0 until renderer.pageCount) {
                            runOnUiThread { status.text = "OCR चल रहा है: पेज ${index + 1}/${renderer.pageCount}" }
                            renderer.openPage(index).use { page ->
                                val scale = (1600f / page.width).coerceAtMost(3f)
                                val bitmap = Bitmap.createBitmap(
                                    (page.width * scale).toInt().coerceAtLeast(1),
                                    (page.height * scale).toInt().coerceAtLeast(1),
                                    Bitmap.Config.ARGB_8888
                                )
                                bitmap.eraseColor(Color.WHITE)
                                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                val result = com.google.android.gms.tasks.Tasks.await(
                                    recognizer.process(InputImage.fromBitmap(bitmap, 0))
                                )
                                allText.append(result.text).append('\n')
                                bitmap.recycle()
                            }
                        }
                    }
                }
                val extracted = allText.toString().lines().map { it.trim() }
                    .filter { it.isNotBlank() }.distinct()
                prefs.edit().putString("ocr_text", extracted.joinToString("\n")).apply()
                voterLines = extracted
                runOnUiThread {
                    status.text = "पूरा हुआ: ${extracted.size} OCR लाइनें मिलीं। गलत पढ़े टेक्स्ट को जाँचें।"
                    renderResults(voterLines)
                    Toast.makeText(this, "PDF OCR पूरा हुआ", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "PDF/OCR त्रुटि: ${e.localizedMessage ?: "फाइल पढ़ नहीं पाए"}"
                    Toast.makeText(this, status.text, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun filterResults() {
        val term = query.text.toString().trim()
        if (term.isEmpty()) {
            renderResults(voterLines)
            return
        }
        renderResults(voterLines.filter { it.contains(term, ignoreCase = true) })
    }

    private fun renderResults(lines: List<String>) {
        results.removeAllViews()
        if (lines.isEmpty()) {
            results.addView(TextView(this).apply {
                text = "कोई परिणाम नहीं। PDF अपलोड करें या खोज बदलें।"
                textSize = 16f
                setPadding(0, dp(16), 0, dp(16))
            })
            return
        }
        lines.take(1000).forEach { line ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                setBackgroundColor(Color.WHITE)
            }
            card.addView(TextView(this).apply {
                text = line
                textSize = 17f
                setTextColor(Color.rgb(30, 37, 48))
            })
            card.addView(Button(this).apply {
                text = "पर्ची इमेज बनाएं"
                setOnClickListener { showSlipPreview(line) }
            }, matchWrap())
            results.addView(card, matchWrap())
            results.addView(TextView(this).apply { text = ""; setPadding(0, dp(3), 0, dp(3)) })
        }
    }

    private fun showSlipPreview(voterText: String) {
        val bitmap = createSlipBitmap(voterText)
        val image = android.widget.ImageView(this).apply {
            setImageBitmap(bitmap)
            adjustViewBounds = true
            setBackgroundColor(Color.WHITE)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            addView(image, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(430)
            ))
            addView(Button(this@MainActivity).apply {
                text = "इमेज WhatsApp पर शेयर करें"
                setOnClickListener { shareSlipImage(bitmap, voterText) }
            }, matchWrap())
            addView(Button(this@MainActivity).apply {
                text = "इमेज फोन में सेव करें"
                setOnClickListener { saveSlipImage(bitmap) }
            }, matchWrap())
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("वोटर पर्ची का पूर्वावलोकन")
            .setView(container)
            .setPositiveButton("बंद करें", null)
            .show()
    }

    private fun createSlipBitmap(voterText: String): Bitmap {
        val width = 900
        val padding = 42f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(35, 45, 60)
            textSize = 34f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        val lines = voterText.lines().flatMap { raw ->
            val words = raw.split(Regex("\\s+"))
            val wrapped = mutableListOf<String>()
            var current = ""
            for (word in words) {
                val candidate = if (current.isEmpty()) word else "$current $word"
                if (paint.measureText(candidate) > width - padding * 2) {
                    if (current.isNotEmpty()) wrapped.add(current)
                    current = word
                } else current = candidate
            }
            if (current.isNotEmpty()) wrapped.add(current)
            wrapped
        }.ifEmpty { listOf(voterText) }
        val rowHeight = 52
        val height = (220 + lines.size * rowHeight + 100).coerceAtLeast(520)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(24, 78, 132)
            style = Paint.Style.STROKE
            strokeWidth = 8f
        }
        canvas.drawRoundRect(18f, 18f, width - 18f, height - 18f, 24f, 24f, border)
        val heading = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(24, 78, 132)
            textSize = 48f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("मतदाता पर्ची", width / 2f, 92f, heading)
        val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.DKGRAY
            textSize = 25f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("वोटर सूची से प्राप्त विवरण", width / 2f, 138f, sub)
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(210, 220, 232)
            strokeWidth = 2f
        }
        canvas.drawLine(padding, 165f, width - padding, 165f, linePaint)
        var y = 220f
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(35, 45, 60)
            textSize = 34f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        lines.forEach { line ->
            canvas.drawText(line, padding, y, body)
            y += rowHeight
        }
        val footer = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.GRAY
            textSize = 20f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("कृपया मूल मतदाता सूची से विवरण सत्यापित करें", width / 2f, height - 45f, footer)
        return bitmap
    }

    private fun saveSlipImage(bitmap: Bitmap) {
        try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "VoterSlip_${System.currentTimeMillis()}.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/VoterSlips")
                }
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("फोन में इमेज सेव नहीं हो सकी")
            contentResolver.openOutputStream(uri).use { output ->
                requireNotNull(output) { "इमेज फाइल नहीं खुली" }
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            }
            Toast.makeText(this, "पर्ची इमेज फोन में सेव हो गई", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "सेव करने में त्रुटि: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    private fun shareSlipImage(bitmap: Bitmap, voterText: String) {
        try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "VoterSlip_${System.currentTimeMillis()}.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/VoterSlips")
                }
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("इमेज तैयार नहीं हो सकी")
            contentResolver.openOutputStream(uri).use { output ->
                requireNotNull(output) { "इमेज फाइल नहीं खुली" }
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            }
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, "मतदाता पर्ची")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "पर्ची इमेज शेयर करें"))
        } catch (e: Exception) {
            Toast.makeText(this, "शेयर करने में त्रुटि: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdown()
        recognizer.close()
    }
}
