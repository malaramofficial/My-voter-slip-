package in.malaramofficial.myvoterslip

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
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
            singleLine = true
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
        lines.take(1000).forEachIndexed { index, line ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setBackgroundColor(Color.WHITE)
            }
            card.addView(TextView(this).apply {
                text = line
                textSize = 17f
                setTextColor(Color.rgb(30, 37, 48))
            })
            val share = Button(this).apply {
                text = "इसी टेक्स्ट की पर्ची शेयर करें"
                setOnClickListener { shareSlip(line) }
            }
            card.addView(share, matchWrap())
            results.addView(card, matchWrap())
            results.addView(TextView(this).apply {
                text = ""
                setPadding(0, dp(3), 0, dp(3))
            })
        }
    }

    private fun shareSlip(text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "वोटर पर्ची")
            putExtra(Intent.EXTRA_TEXT, "वोटर सूची से चयनित विवरण\n\n$text")
        }
        startActivity(Intent.createChooser(intent, "चयनित पर्ची शेयर करें"))
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
