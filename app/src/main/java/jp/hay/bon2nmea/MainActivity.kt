package jp.hay.bon2nmea

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedWriter
import java.io.InputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.*

/**
 * BON converter workflow. The main branch is preserved as the upstream baseline.
 * SAF folder permissions persist across launches; no broad storage permission required.
 */
class MainActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences("bon_workflow", MODE_PRIVATE) }
    private var source: Uri? = null
    private var sourceName: String = ""
    private var sourceVersion = LocationData.DataVersion.BON1
    private var busy = false
    private var samples: List<LocationData> = emptyList()
    private var suggestedTrack = "Unknown"
    private var detectedTime: LocalDateTime? = null
    private var format = "nmea"

    private val chooseSourceTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            persistTree(uri)
            prefs.edit().putString("source_tree", uri.toString()).apply()
            showFiles()
        }
    }
    private val chooseOutputTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            persistTree(uri)
            prefs.edit().putString("output_tree", uri.toString()).apply()
            toast("出力先を保存しました")
        }
    }
    private val chooseSingleFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) selectSource(uri, displayName(uri))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<Button>(R.id.buttonOpen).setOnClickListener {
            if (prefs.contains("source_tree")) showFiles()
            else chooseSourceTree.launch(null)
        }
        findViewById<Button>(R.id.buttonSave).setOnClickListener { export() }
        // Long-press source to change the folder; long-press save to change output folder.
        findViewById<Button>(R.id.buttonOpen).setOnLongClickListener {
            chooseSourceTree.launch(null); true
        }
        findViewById<Button>(R.id.buttonSave).setOnLongClickListener {
            chooseOutputTree.launch(null); true
        }
        findViewById<TextView>(R.id.textView).text =
            "BONフォルダを選択してください（ボタン長押しで変更）"
    }

    private fun persistTree(uri: Uri) {
        contentResolver.takePersistableUriPermission(
            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
    }
    private fun tree(key: String): DocumentFile? {
        val uri = prefs.getString(key, null) ?: return null
        return try { DocumentFile.fromTreeUri(this, Uri.parse(uri)) } catch (_: Exception) { null }
    }
    private fun showFiles() {
        val dir = tree("source_tree")
        if (dir == null || !dir.canRead()) {
            chooseSourceTree.launch(null); return
        }
        val entries = dir.listFiles().filter {
            it.isFile && Regex("(?i)\\.bon[14]$").containsMatchIn(it.name ?: "")
        }
        if (entries.isEmpty()) {
            AlertDialog.Builder(this).setMessage("BON1/BON4ファイルがありません")
                .setPositiveButton("フォルダ変更") { _, _ -> chooseSourceTree.launch(null) }
                .setNeutralButton("単一ファイル") { _, _ -> chooseSingleFile.launch(arrayOf("*/*")) }
                .setNegativeButton("閉じる", null).show()
            return
        }
        val newest = prefs.getBoolean("newest_first", true)
        val ordered = if (newest) entries.sortedByDescending { it.lastModified() }
                      else entries.sortedBy { it.name?.lowercase() }
        val labels = ordered.map { it.name ?: "(unnamed)" }.toTypedArray()
        AlertDialog.Builder(this).setTitle(if (newest) "BONファイル（更新日が新しい順）" else "BONファイル（名前順）")
            .setItems(labels) { _, which -> selectSource(ordered[which].uri, labels[which]) }
            .setPositiveButton("並び順を切替") { _, _ ->
                prefs.edit().putBoolean("newest_first", !newest).apply(); showFiles()
            }
            .setNeutralButton("フォルダ変更") { _, _ -> chooseSourceTree.launch(null) }
            .setNegativeButton("閉じる", null).show()
    }
    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) return it.getString(0) ?: "input.bon4"
        }
        return "input.bon4"
    }
    private fun selectSource(uri: Uri, name: String) {
        val ext = name.substringAfterLast('.', "").uppercase()
        if (ext != "BON1" && ext != "BON4") { toast("BON1/BON4を選んでください"); return }
        source = uri
        sourceName = name
        sourceVersion = if (ext == "BON4") LocationData.DataVersion.BON4 else LocationData.DataVersion.BON1
        findViewById<TextView>(R.id.textView).text = "解析中: $name"
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { readSamples(it, sourceVersion) }
                        ?: error("ファイルを開けません")
                }
                samples = result
                detectedTime = result.firstOrNull()?.m_timestamp
                suggestedTrack = recognizeTrack(result)
                findViewById<TextView>(R.id.textView).text =
                    "$name\n推定: $suggestedTrack\n記録: ${detectedTime ?: "不明"}"
                if (tree("output_tree") == null) chooseOutputTree.launch(null)
                else if (suggestedTrack != "Unknown") save(suggestedTrack)
            } catch (e: Exception) {
                findViewById<TextView>(R.id.textView).text = "解析失敗: ${e.message}"
            }
        }
    }
    private fun readSamples(stream: InputStream, version: LocationData.DataVersion): List<LocationData> {
        val input = BufferedInputStream(stream)
        val header = ByteArray(8)
        if (readFully(input, header) != 8) error("BONヘッダーが不完全です")
        val points = ArrayList<LocationData>()
        var previous: LocationData? = null
        val chunk = ByteArray(26)
        var count = 0
        while (true) {
            val bytes = readFully(input, chunk) ?: break
            if (bytes != 26) error("BONレコードが不完全です")
            val point = LocationData(chunk, previous, version)
            if (count % 20 == 0) points.add(point)
            previous = point
            count++
        }
        return points
    }
    private fun readFully(input: InputStream, bytes: ByteArray): Int? {
        var offset = 0
        while (offset < bytes.size) {
            val n = input.read(bytes, offset, bytes.size - offset)
            if (n < 0) return if (offset == 0) null else offset
            if (n == 0) continue
            offset += n
        }
        return offset
    }
    private fun recognizeTrack(points: List<LocationData>): String {
        if (points.isEmpty()) return "Unknown"
        val lat = points.map { it.m_latitude / 60000000.0 }.average()
        val lon = points.map { it.m_longtitude / 60000000.0 }.average()
        val saved = prefs.getString("track_fingerprints", "") ?: ""
        val matches = saved.lines().mapNotNull {
            val p = it.split("|")
            if (p.size != 3) null else {
                val a = p[1].toDoubleOrNull()
                val b = p[2].toDoubleOrNull()
                if (a == null || b == null) null else Triple(p[0], a, b)
            }
        }
        // A short centroid distance is only a suggestion; TC1000/TC2000 are
        // adjacent and must not be conflated without a learned trajectory fingerprint.
        val best = matches.minByOrNull { distanceMeters(lat, lon, it.second, it.third) }
        return if (best != null && distanceMeters(lat, lon, best.second, best.third) < 100.0)
            best.first else "Unknown"
    }
    private fun distanceMeters(a: Double, b: Double, c: Double, d: Double): Double {
        val lat = Math.toRadians((a + c) / 2)
        return hypot((a - c) * 111195, (b - d) * 111195 * cos(lat))
    }
    private fun export() {
        if (busy || source == null) { toast("BONファイルを選択してください"); return }
        if (tree("output_tree") == null) { chooseOutputTree.launch(null); return }
        val input = EditText(this).apply {
            setSingleLine(true)
            setText(if (suggestedTrack == "Unknown") "" else suggestedTrack)
            hint = "コース名（例 TC2000）"
        }
        val formats = arrayOf("NMEA", "VBO (experimental)")
        AlertDialog.Builder(this).setTitle("コースを確認して出力")
            .setView(input)
            .setSingleChoiceItems(formats, if (format == "nmea") 0 else 1) { _, which ->
                format = if (which == 0) "nmea" else "vbo"
            }
            .setPositiveButton("変換して保存") { _, _ ->
                val track = input.text.toString().trim().ifBlank { "Unknown" }
                    .replace(Regex("[^a-zA-Z0-9_-]"), "_").take(40)
                save(track)
            }.setNegativeButton("キャンセル", null).show()
    }
    private fun save(track: String) {
        val src = source ?: return
        val folder = tree("output_tree") ?: return
        busy = true
        findViewById<ProgressBar>(R.id.progressBar).visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val date = detectedTime?.format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
                    ?: "unknown_date"
                val filename = "${date}_${track}.${format}"
                val result = withContext(Dispatchers.IO) {
                    var candidate = filename
                    var suffix = 1
                    while (folder.findFile(candidate) != null) {
                        candidate = "${date}_${track}_${suffix++}.${format}"
                    }
                    val mime = "application/octet-stream"
                    val dest = folder.createFile(mime, candidate) ?: error("保存ファイルを作れません")
                    try {
                        contentResolver.openInputStream(src)!!.use { raw ->
                            contentResolver.openOutputStream(dest.uri, "wt")!!.bufferedWriter().use { writer ->
                                writeConverted(raw, writer, sourceVersion, format)
                            }
                        }
                    } catch (e: Exception) { dest.delete(); throw e }
                    candidate
                }
                // Learn only explicitly confirmed names. Unknown is never learned.
                if (track != "Unknown" && samples.isNotEmpty()) {
                    val lat = samples.map { it.m_latitude / 60000000.0 }.average()
                    val lon = samples.map { it.m_longtitude / 60000000.0 }.average()
                    val old = prefs.getString("track_fingerprints", "") ?: ""
                    prefs.edit().putString("track_fingerprints", (old + "\n$track|$lat|$lon").trim()).apply()
                }
                toast("保存しました: $result")
            } catch (e: Exception) { toast("変換失敗: ${e.message}") }
            finally {
                busy = false
                findViewById<ProgressBar>(R.id.progressBar).visibility = View.INVISIBLE
            }
        }
    }
    private fun writeConverted(stream: InputStream, writer: BufferedWriter,
                               version: LocationData.DataVersion, output: String) {
        val input = BufferedInputStream(stream)
        if (readFully(input, ByteArray(8)) != 8) error("BONヘッダーが不完全です")
        if (output == "vbo") {
            // RaceChrono uses the creation date from the first VBO line.
            val creation = detectedTime?.format(DateTimeFormatter.ofPattern("dd/MM/yyyy '@' HH:mm"))
                ?: "01/01/2000 @ 00:00"
            writer.write("File created on $creation"); writer.newLine()
            writer.write("[header]"); writer.newLine()
            listOf("satellites", "time", "latitude", "longitude", "velocity kmh",
                "heading", "height").forEach { writer.write(it); writer.newLine() }
            writer.write("[comments]"); writer.newLine()
            writer.write("Converted from BON by eightlien"); writer.newLine()
            writer.write("[column names]"); writer.newLine()
            writer.write("sats time lat long velocity heading height"); writer.newLine()
            writer.write("[data]"); writer.newLine()
        }
        var previous: LocationData? = null
        val chunk = ByteArray(26)
        while (true) {
            val n = readFully(input, chunk) ?: break
            if (n != 26) error("不完全なBONレコード")
            val point = LocationData(chunk, previous, version)
            if (output == "nmea") writer.write(point.getNmea())
            else writer.write(VboExporter.line(point))
            writer.newLine()
            previous = point
        }
    }
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
