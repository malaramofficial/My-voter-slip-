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
import org.json.JSONObject
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
    private var voterRecords: List<String> = emptyList()
    private val prefs by lazy { getSharedPreferences("voter_slip_local", MODE_PRIVATE) }
    private val pickPdf = 701
    private val pickJson = 702

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        voterRecords = prefs.getString("ocr_records", "").orEmpty()
            .split("\n\n---RECORD---\n\n").map { it.trim() }.filter { it.isNotBlank() }
        if (voterRecords.isEmpty()) {
            voterRecords = loadBundledRecords()
            if (voterRecords.isNotEmpty()) {
                prefs.edit().putString("ocr_records", voterRecords.joinToString("\n\n---RECORD---\n\n")).apply()
            }
        }
        buildScreen()
        renderResults(voterRecords)
    }

    private fun buildScreen() {
        window.statusBarColor = Color.rgb(13, 45, 83)
        window.navigationBarColor = Color.rgb(247, 249, 252)
        window.decorView.systemUiVisibility = 0

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(10))
            setBackgroundColor(Color.rgb(247, 249, 252))
        }

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            setPadding(0, 0, 0, dp(8))
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(content)

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(22))
            background = roundedBg(Color.rgb(18, 61, 108), 24)
        }
        val brand = TextView(this).apply {
            text = "OFFLINE • HINDI OCR"
            textSize = 11f
            letterSpacing = 0.12f
            setTextColor(Color.rgb(185, 216, 250))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        val title = TextView(this).apply {
            text = "मेरी वोटर पर्ची"
            textSize = 29f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setPadding(0, dp(7), 0, dp(4))
        }
        val subtitle = TextView(this).apply {
            text = "मतदाता सूची खोजें और पर्ची इमेज तैयार करें"
            textSize = 14f
            setTextColor(Color.rgb(229, 239, 251))
        }
        hero.addView(brand, matchWrap())
        hero.addView(title, matchWrap())
        hero.addView(subtitle, matchWrap())
        content.addView(hero, matchWrap())

        val privacy = TextView(this).apply {
            text = "🔒  आपका डेटा इसी फोन में रहता है"
            textSize = 13f
            setTextColor(Color.rgb(28, 102, 77))
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = roundedBg(Color.rgb(228, 245, 237), 14)
        }
        val privacyParams = matchWrap().apply { topMargin = dp(12) }
        content.addView(privacy, privacyParams)

        val sectionTitle = TextView(this).apply {
            text = "वोटर सूची"
            textSize = 19f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(Color.rgb(25, 43, 64))
            setPadding(dp(2), dp(20), dp(2), dp(8))
        }
        content.addView(sectionTitle, matchWrap())

        status = TextView(this).apply {
            text = if (voterRecords.isEmpty()) "इस ऐप में मतदाता डेटा उपलब्ध नहीं है।" else "ऐप में पहले से ${voterRecords.size} मतदाता रिकॉर्ड मौजूद हैं।"
            textSize = 13f
            setTextColor(Color.rgb(82, 98, 116))
            setPadding(dp(2), dp(10), dp(2), dp(10))
        }
        content.addView(status, matchWrap())

        val searchCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = roundedBg(Color.WHITE, 18, Color.rgb(225, 231, 239))
        }
        val searchLabel = TextView(this).apply {
            text = "मतदाता खोजें"
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.rgb(25, 43, 64))
        }
        query = EditText(this).apply {
            hint = "नाम, पिता का नाम या मकान नंबर"
            setSingleLine(true)
            textSize = 15f
            setTextColor(Color.rgb(25, 43, 64))
            setHintTextColor(Color.rgb(135, 148, 164))
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = roundedBg(Color.rgb(246, 248, 251), 12, Color.rgb(226, 232, 240))
        }
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val search = Button(this).apply {
            text = "खोजें"
            textSize = 14f
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = roundedBg(Color.rgb(24, 91, 160), 12)
            setOnClickListener { filterResults() }
        }
        val clear = Button(this).apply {
            text = "साफ करें"
            textSize = 14f
            isAllCaps = false
            setTextColor(Color.rgb(39, 66, 96))
            background = roundedBg(Color.rgb(235, 240, 246), 12)
            setOnClickListener {
                query.setText("")
                renderResults(voterRecords)
            }
        }
        actions.addView(search, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(8) })
        actions.addView(clear, LinearLayout.LayoutParams(0, dp(48), 1f))
        searchCard.addView(searchLabel, matchWrap())
        searchCard.addView(query, matchWrap().apply { topMargin = dp(10); bottomMargin = dp(10) })
        searchCard.addView(actions, matchWrap())
        content.addView(searchCard, matchWrap())

        val resultsHeading = TextView(this).apply {
            text = "खोज परिणाम"
            textSize = 19f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(Color.rgb(25, 43, 64))
            setPadding(dp(2), dp(20), dp(2), dp(8))
        }
        content.addView(resultsHeading, matchWrap())
        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(results, matchWrap())

        val footer = TextView(this).apply {
            text = "डेवलपर: माला राम ऑफिशियल"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(102, 116, 133))
            setPadding(dp(8), dp(16), dp(8), dp(8))
        }
        content.addView(footer, matchWrap())
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        setContentView(root)
    }

    private fun loadBundledRecords(): List<String> {
        return try {
            val jsonText = assets.open("voters.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
            val rootObject = JSONObject(jsonText)
            val array = rootObject.optJSONArray("records") ?: return emptyList()
            val imported = mutableListOf<String>()
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val fields = listOf(
                    "नाम" to item.optString("name", ""),
                    "पिता/पति/अन्य का नाम" to item.optString("relative_name", ""),
                    "मकान नंबर" to item.optString("house_no", ""),
                    "आयु" to item.optString("age", ""),
                    "लिंग" to item.optString("gender", ""),
                    "EPIC नंबर" to item.optString("epic", ""),
                    "क्रमांक" to item.optString("serial_no", ""),
                    "परिवार समूह" to item.optString("family_id", ""),
                    "PDF" to item.optString("source_pdf", ""),
                    "पृष्ठ" to item.optString("page", "")
                ).filter { it.second.isNotBlank() && it.second != "null" }
                val raw = item.optString("raw_ocr", "")
                val record = fields.joinToString("\n") { "${it.first}: ${it.second}" } +
                    if (raw.isNotBlank()) "\nमूल OCR पाठ:\n$raw" else ""
                if (record.isNotBlank()) imported.add(record)
            }
            imported
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Deprecated("Deprecated in Android but still supported for document picker")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == pickPdf && resultCode == RESULT_OK) {
            data?.data?.let { uri -> runOcr(uri) }
        } else if (requestCode == pickJson && resultCode == RESULT_OK) {
            data?.data?.let { uri -> importOcrJson(uri) }
        }
    }

    private fun importOcrJson(uri: Uri) {
        status.text = "OCR JSON डेटा पढ़ा जा रहा है…"
        worker.execute {
            try {
                val jsonText = contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "JSON फाइल नहीं खुली" }
                    input.bufferedReader(Charsets.UTF_8).use { it.readText() }
                }
                val rootObject = JSONObject(jsonText)
                val array = rootObject.optJSONArray("records")
                    ?: throw IllegalArgumentException("इस JSON में records सूची नहीं मिली")
                val imported = mutableListOf<String>()
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val fields = listOf(
                        "नाम" to item.optString("name", ""),
                        "पिता/पति/अन्य का नाम" to item.optString("relative_name", ""),
                        "मकान नंबर" to item.optString("house_no", ""),
                        "आयु" to item.optString("age", ""),
                        "लिंग" to item.optString("gender", ""),
                        "EPIC नंबर" to item.optString("epic", ""),
                        "क्रमांक" to item.optString("serial_no", ""),
                        "परिवार समूह (प्रारंभिक)" to item.optString("family_id", ""),
                        "PDF" to item.optString("source_pdf", ""),
                        "पृष्ठ" to item.optString("page", "")
                    ).filter { it.second.isNotBlank() && it.second != "null" }
                    val raw = item.optString("raw_ocr", "")
                    val record = fields.joinToString("\n") { "${it.first}: ${it.second}" } +
                        if (raw.isNotBlank()) "\nमूल OCR पाठ:\n$raw" else ""
                    if (record.isNotBlank()) imported.add(record)
                }
                if (imported.isEmpty()) throw IllegalArgumentException("JSON में कोई मतदाता रिकॉर्ड नहीं मिला")
                prefs.edit().putString("ocr_records", imported.joinToString("\n\n---RECORD---\n\n")).apply()
                voterRecords = imported
                runOnUiThread {
                    status.text = "JSON से ${imported.size} रिकॉर्ड लोड हुए। नाम/विवरण मूल PDF से जाँचें।"
                    renderResults(voterRecords)
                    Toast.makeText(this, "OCR डेटा लोड हो गया", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "JSON आयात त्रुटि: ${e.localizedMessage ?: "फाइल पढ़ नहीं पाए"}"
                    Toast.makeText(this, status.text, Toast.LENGTH_LONG).show()
                }
            }
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
                val extracted = mutableListOf<String>()
                val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                descriptor.use { pfd ->
                    PdfRenderer(pfd).use { renderer ->
                        for (index in 0 until renderer.pageCount) {
                            runOnUiThread { status.text = "मतदाता कार्ड खोज रहे हैं: पेज ${index + 1}/${renderer.pageCount}" }
                            renderer.openPage(index).use { page ->
                                val scale = (1800f / page.width).coerceAtMost(3f)
                                val bitmap = Bitmap.createBitmap(
                                    (page.width * scale).toInt().coerceAtLeast(1),
                                    (page.height * scale).toInt().coerceAtLeast(1),
                                    Bitmap.Config.ARGB_8888
                                )
                                bitmap.eraseColor(Color.WHITE)
                                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                                // Standard Indian electoral-roll pages usually use a 3 x 10 voter-card grid.
                                // OCR each card crop independently so one card becomes one record.
                                val left = (bitmap.width * 0.015f).toInt()
                                val right = (bitmap.width * 0.985f).toInt()
                                val top = (bitmap.height * 0.105f).toInt()
                                val bottom = (bitmap.height * 0.98f).toInt()
                                val cellWidth = (right - left) / 3
                                val cellHeight = (bottom - top) / 10

                                for (row in 0 until 10) {
                                    for (column in 0 until 3) {
                                        val x = left + column * cellWidth
                                        val y = top + row * cellHeight
                                        val w = if (column == 2) right - x else cellWidth
                                        val h = if (row == 9) bottom - y else cellHeight
                                        if (w <= 0 || h <= 0) continue
                                        val card = Bitmap.createBitmap(bitmap, x, y, w, h)
                                        try {
                                            val result = com.google.android.gms.tasks.Tasks.await(
                                                recognizer.process(InputImage.fromBitmap(card, 0))
                                            )
                                            val cardText = result.textBlocks
                                                .map { it.text.trim() }
                                                .filter { it.isNotBlank() }
                                                .joinToString("\n")
                                                .trim()
                                            if (looksLikeVoterCard(cardText)) extracted.add(cardText)
                                        } finally {
                                            card.recycle()
                                        }
                                    }
                                }
                                bitmap.recycle()
                            }
                        }
                    }
                }
                prefs.edit().putString("ocr_records", extracted.joinToString("\n\n---RECORD---\n\n")).apply()
                voterRecords = extracted
                runOnUiThread {
                    status.text = "पूरा हुआ: ${extracted.size} मतदाता रिकॉर्ड मिले। नाम/विवरण मिलाकर जाँचें।"
                    renderResults(voterRecords)
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
            renderResults(voterRecords)
            return
        }
        renderResults(voterRecords.filter { it.contains(term, ignoreCase = true) })
    }

    private fun renderResults(records: List<String>) {
        results.removeAllViews()
        val count = TextView(this).apply {
            text = if (records.isEmpty()) "अभी कोई परिणाम नहीं" else "${records.size} मतदाता रिकॉर्ड"
            textSize = 12f
            setTextColor(Color.rgb(102, 116, 133))
            setPadding(dp(2), 0, dp(2), dp(8))
        }
        results.addView(count, matchWrap())
        if (records.isEmpty()) {
            val empty = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(22), dp(26), dp(22), dp(26))
                background = roundedBg(Color.WHITE, 18, Color.rgb(225, 231, 239))
            }
            empty.addView(TextView(this).apply {
                text = "⌕"
                textSize = 34f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(100, 132, 165))
            }, matchWrap())
            empty.addView(TextView(this).apply {
                text = "यहाँ खोज परिणाम दिखाई देंगे"
                textSize = 16f
                gravity = Gravity.CENTER
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(Color.rgb(43, 59, 78))
                setPadding(0, dp(6), 0, dp(5))
            }, matchWrap())
            empty.addView(TextView(this).apply {
                text = "खोज का शब्द बदलकर दोबारा प्रयास करें।"
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(111, 125, 142))
            }, matchWrap())
            results.addView(empty, matchWrap())
            return
        }
        records.take(1000).forEachIndexed { index, record ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(15), dp(14), dp(15), dp(14))
                background = roundedBg(Color.WHITE, 17, Color.rgb(228, 233, 240))
            }
            val top = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val number = TextView(this).apply {
                text = "${index + 1}".padStart(2, '0')
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(24, 91, 160))
                background = roundedBg(Color.rgb(232, 241, 251), 10)
                setPadding(dp(9), dp(7), dp(9), dp(7))
            }
            val label = TextView(this).apply {
                text = "पूरा मतदाता रिकॉर्ड"
                textSize = 11f
                setTextColor(Color.rgb(111, 125, 142))
                setPadding(dp(10), 0, 0, 0)
            }
            top.addView(number, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            top.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            card.addView(top, matchWrap())
            card.addView(TextView(this).apply {
                text = record
                textSize = 16f
                setTextColor(Color.rgb(35, 47, 63))
                setPadding(0, dp(12), 0, dp(12))
            }, matchWrap())
            card.addView(Button(this).apply {
                text = "पर्ची इमेज का पूर्वावलोकन"
                textSize = 14f
                isAllCaps = false
                setTextColor(Color.WHITE)
                background = roundedBg(Color.rgb(24, 91, 160), 12)
                setOnClickListener { showSlipPreview(record) }
            }, matchWrap())
            results.addView(card, matchWrap().apply { bottomMargin = dp(10) })
        }
    }

    private fun looksLikeVoterCard(text: String): Boolean {
        if (text.length < 12) return false
        val devanagariChars = text.count { it.code in 0x0900..0x097F }
        val hasEpic = Regex("(?i)\\b[A-Z]{2,4}\\s?\\d{6,8}\\b").containsMatchIn(text)
        // Reject empty cells and page headings; accept Hindi voter text or an EPIC-like ID.
        return hasEpic || devanagariChars >= 5
    }

    private fun splitIntoVoterRecords(raw: String): List<String> {
        val normalized = raw.replace("\r", "")
            .replace(Regex("[ \\t]+"), " ")
            .trim()
        if (normalized.isBlank()) return emptyList()
        val lines = normalized.lines().map { it.trim() }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return emptyList()

        val nameStart = Regex("(?i)^(?:निर्वाचक\\s*का\\s*नाम|मतदाता\\s*का\\s*नाम|नाम\\s*[:：])")
        val epic = Regex("(?i)\\b[A-Z]{2,4}\\s?\\d{6,8}\\b")
        val serial = Regex("(?i)^(?:मतदाता\\s*)?(?:क्रमांक|अनुक्रमांक|क्र\\.?\\s*सं\\.?)\\s*[:：.-]?\\s*\\d+")
        val nameStarts = lines.indices.filter { nameStart.containsMatchIn(lines[it]) }
        val epicStarts = lines.indices.filter { epic.containsMatchIn(lines[it]) }
        val serialStarts = lines.indices.filter { serial.containsMatchIn(lines[it]) }
        // Prefer one reliable boundary type, so a name and EPIC from the same voter
        // cannot accidentally split that voter's record into two separate slips.
        val starts = when {
            nameStarts.size >= 2 -> nameStarts
            epicStarts.size >= 2 -> epicStarts
            serialStarts.size >= 2 -> serialStarts
            else -> emptyList()
        }

        if (starts.size >= 2) {
            val chunks = mutableListOf<String>()
            var current = StringBuilder()
            lines.forEachIndexed { index, line ->
                if (index in starts && current.isNotEmpty()) {
                    chunks.add(current.toString().trim())
                    current = StringBuilder()
                }
                if (current.isNotEmpty()) current.append('\n')
                current.append(line)
            }
            if (current.isNotEmpty()) chunks.add(current.toString().trim())
            return chunks.filter { it.isNotBlank() }
        }

        // If OCR misses voter-boundary labels, keep the complete paragraph/page text
        // together rather than making a false separate slip for every individual line.
        return listOf(lines.joinToString("\n"))
    }

    private fun roundedBg(color: Int, radiusDp: Int, strokeColor: Int? = null): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeColor != null) setStroke(dp(1), strokeColor)
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
