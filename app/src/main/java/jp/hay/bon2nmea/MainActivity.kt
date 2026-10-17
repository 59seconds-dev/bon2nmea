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
    private var pendingBatch: List<BonEntry>? = null
    private var outputFormat: String = "vbo"

    private val chooseSourceTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            persistTree(uri)
            prefs.edit().putString("source_tree", uri.toString()).apply()
            updateFolders()
            showFiles()
        }
    }
    private val chooseOutputTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            persistTree(uri)
            prefs.edit().putString("output_tree", uri.toString()).apply()
            updateFolders()
            pendingBatch?.let { batch -> pendingBatch = null; convertBatch(batch) }
        }
    }
    private val chooseSingleFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) selectSource(uri, displayName(uri))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<Button>(R.id.buttonOpen).setOnClickListener { showFiles() }
        val formatGroup = findViewById<RadioGroup>(R.id.formatGroup)
        outputFormat = prefs.getString("output_format", "vbo") ?: "vbo"
        formatGroup.check(if (outputFormat == "nmea") R.id.formatNmea else R.id.formatVbo)
        formatGroup.setOnCheckedChangeListener { _, checked ->
            outputFormat = if (checked == R.id.formatNmea) "nmea" else "vbo"
            prefs.edit().putString("output_format", outputFormat).apply()
        }
        findViewById<Button>(R.id.buttonSourceFolder).setOnClickListener { chooseSourceTree.launch(null) }
        findViewById<Button>(R.id.buttonOutputFolder).setOnClickListener { chooseOutputTree.launch(null) }
        updateFolders()
        updateStatus()
    }

    private fun updateFolders() {
        val input = tree("source_tree")
        val output = tree("output_tree")
        findViewById<TextView>(R.id.folderStatus).text =
            "入力: ${input?.name ?: "未設定"}\n保存先: ${output?.name ?: "未設定"} / BON4_Converted"
    }

    private fun updateStatus() {
        findViewById<TextView>(R.id.textView).text =
            if (source == null) "BONファイルを選択してください"
            else "$sourceName\n推定コース: $suggestedTrack\n記録開始: ${detectedTime ?: "不明"}\nGPS点数: ${samples.size}"
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
    private data class BonEntry(val uri: Uri, val name: String, val modified: Long)

    private fun showFiles() {
        val dir = tree("source_tree")
        if (dir == null) {
            chooseSourceTree.launch(null)
            return
        }
        val loading = AlertDialog.Builder(this)
            .setMessage("BONファイルを検索中…")
            .setNegativeButton("キャンセル", null)
            .create()
        loading.show()
        lifecycleScope.launch {
            try {
                val entries = withContext(Dispatchers.IO) {
                    if (!dir.canRead()) error("入力フォルダを読み取れません")
                    dir.listFiles().mapNotNull { file ->
                        val name = file.name ?: return@mapNotNull null
                        if (file.isFile && (name.endsWith(".bon4", true) || name.endsWith(".bon1", true)))
                            BonEntry(file.uri, name, file.lastModified())
                        else null
                    }
                }
                if (!loading.isShowing) return@launch
                loading.dismiss()
                if (entries.isEmpty()) {
                    AlertDialog.Builder(this@MainActivity)
                        .setMessage("BON1/BON4ファイルがありません")
                        .setPositiveButton("フォルダ変更") { _, _ -> chooseSourceTree.launch(null) }
                        .setNeutralButton("単一ファイル") { _, _ -> chooseSingleFile.launch(arrayOf("*/*")) }
                        .setNegativeButton("閉じる", null).show()
                    return@launch
                }
                val newest = prefs.getBoolean("newest_first", true)
                val ordered = if (newest) entries.sortedByDescending { it.modified }
                              else entries.sortedBy { it.name.lowercase() }
                val checked = BooleanArray(ordered.size)
                val dialog = AlertDialog.Builder(this@MainActivity)
                    .setTitle("BONファイルを選択（複数可）")
                    .setMultiChoiceItems(ordered.map { it.name }.toTypedArray(), checked) { _, which, value ->
                        checked[which] = value
                    }
                    .setPositiveButton("選択したファイルを変換") { _, _ ->
                        val selected = ordered.filterIndexed { index, _ -> checked[index] }
                        if (selected.isEmpty()) toast("ファイルを選択してください")
                        else convertBatch(selected)
                    }
                    .setNeutralButton("並び順切替") { _, _ ->
                        prefs.edit().putBoolean("newest_first", !newest).apply()
                        showFiles()
                    }
                    .setNegativeButton("キャンセル", null).create()
                dialog.show()
            } catch (e: Exception) {
                loading.dismiss()
                AlertDialog.Builder(this@MainActivity)
                    .setMessage("ファイル一覧の取得に失敗: ${e.message}")
                    .setPositiveButton("フォルダ変更") { _, _ -> chooseSourceTree.launch(null) }
                    .setNegativeButton("閉じる", null).show()
            }
        }
    }
    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) return it.getString(0) ?: "input.bon4"
        }
        return "input.bon4"
    }
    private fun selectSource(uri: Uri, name: String) {
        convertBatch(listOf(BonEntry(uri, name, 0)))
    }

    private fun convertBatch(entries: List<BonEntry>) {
        if (busy) { toast("変換中です"); return }
        if (tree("output_tree") == null) {
            pendingBatch = entries
            chooseOutputTree.launch(null)
            return
        }
        val parent = tree("output_tree") ?: return
        val format = outputFormat
        busy = true
        findViewById<Button>(R.id.buttonOpen).isEnabled = false
        findViewById<ProgressBar>(R.id.progressBar).visibility = View.VISIBLE
        lifecycleScope.launch {
            var success = 0
            val failures = mutableListOf<String>()
            try {
                val folder = withContext(Dispatchers.IO) {
                    val result = parent.findFile("BON4_Converted")
                        ?: parent.createDirectory("BON4_Converted")
                        ?: error("保存先フォルダを作成できません")
                    if (!result.isDirectory || !result.canWrite()) error("保存先フォルダに書き込めません")
                    result
                }
                for ((index, entry) in entries.withIndex()) {
                    findViewById<TextView>(R.id.textView).text =
                        "変換中 ${index + 1} / ${entries.size}\n${entry.name}\n成功: $success / 失敗: ${failures.size}"
                    try {
                        val version = when (entry.name.substringAfterLast('.', "").uppercase()) {
                            "BON1" -> LocationData.DataVersion.BON1
                            "BON4" -> LocationData.DataVersion.BON4
                            else -> error("BON1/BON4ではありません")
                        }
                        val result = withContext(Dispatchers.IO) {
                            val points = contentResolver.openInputStream(entry.uri)?.use { readSamples(it, version) }
                                ?: error("読み取りに失敗")
                            if (points.isEmpty()) error("GPSデータがありません")
                            val track = recognizeTrack(points).replace(Regex("[^a-zA-Z0-9_-]"), "_").take(40)
                            val courseFolder = folder.findFile(track)
                                ?: folder.createDirectory(track)
                                ?: error("コース別フォルダを作成できません: $track")
                            if (!courseFolder.isDirectory || !courseFolder.canWrite()) {
                                error("コース別フォルダに書き込めません: $track")
                            }
                            val firstTime = points.first().m_timestamp
                            val date = firstTime.format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
                            var name = "${date}_${track}.${format}"
                            var suffix = 1
                            while (courseFolder.findFile(name) != null) {
                                name = "${date}_${track}_${suffix++}.${format}"
                            }
                            val dest = courseFolder.createFile("application/octet-stream", name)
                                ?: error("保存ファイルを作れません")
                            try {
                                contentResolver.openInputStream(entry.uri)!!.use { input ->
                                    contentResolver.openOutputStream(dest.uri, "wt")!!.bufferedWriter().use { writer ->
                                        writeConverted(input, writer, version, format, firstTime)
                                    }
                                }
                            } catch (e: Exception) {
                                dest.delete()
                                throw e
                            }
                            name
                        }
                        success++
                        sourceName = entry.name
                        suggestedTrack = result.substringAfter("_").substringBeforeLast(".")
                    } catch (e: Exception) {
                        failures.add("${entry.name}: ${e.message ?: "不明なエラー"}")
                    }
                }
                val message = "変換完了: 成功 $success / ${entries.size} 件、失敗 ${failures.size} 件"
                findViewById<TextView>(R.id.textView).text =
                    message + if (failures.isEmpty()) "" else "\n" + failures.take(5).joinToString("\n")
                toast(message)
            } catch (e: Exception) {
                findViewById<TextView>(R.id.textView).text = "変換失敗: ${e.message}"
            } finally {
                busy = false
                findViewById<Button>(R.id.buttonOpen).isEnabled = true
                findViewById<ProgressBar>(R.id.progressBar).visibility = View.GONE
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
    private data class TrackZone(val name: String, val lat: Double, val lon: Double, val radius: Double)

    private fun recognizeTrack(points: List<LocationData>): String {
        if (points.isEmpty()) return "Unknown"
        // Coordinates are decimal degrees. Distances are only a location estimate,
        // not lap/SF recognition. Separate TC1000 and TC2000 by their track centres.
        val zones = listOf(
            TrackZone("SUGO", 38.138472, 140.778208, 850.0),
            TrackZone("TC2000", 36.150970, 139.922260, 145.0),
            TrackZone("TC1000", 36.150998, 139.924776, 125.0),
            TrackZone("Nikko", 36.662230, 139.862570, 250.0),
            TrackZone("Fuji", 35.371667, 138.926667, 1100.0)
        )
        // Use a robust median, avoiding occasional GPS jumps and pit movement.
        val lats = points.map { it.m_latitude / 60000000.0 }.filter { it in -90.0..90.0 }.sorted()
        val lons = points.map { it.m_longtitude / 60000000.0 }.filter { it in -180.0..180.0 }.sorted()
        if (lats.isEmpty() || lons.isEmpty()) return "Unknown"
        val lat = lats[lats.size / 2]
        val lon = lons[lons.size / 2]
        val match = zones.map { it to distanceMeters(lat, lon, it.lat, it.lon) }
            .filter { it.second <= it.first.radius }
            .minByOrNull { it.second }
        if (match != null) return match.first.name

        // Previously confirmed track names remain available for other circuits.
        val saved = prefs.getString("track_fingerprints", "") ?: ""
        val matches = saved.lines().mapNotNull {
            val p = it.split("|")
            if (p.size != 3) null else {
                val a = p[1].toDoubleOrNull()
                val b = p[2].toDoubleOrNull()
                if (a == null || b == null) null else Triple(p[0], a, b)
            }
        }
        val best = matches.minByOrNull { distanceMeters(lat, lon, it.second, it.third) }
        return if (best != null && distanceMeters(lat, lon, best.second, best.third) < 100.0)
            best.first else "Unknown"
    }
    private fun distanceMeters(a: Double, b: Double, c: Double, d: Double): Double {
        val lat = Math.toRadians((a + c) / 2)
        return hypot((a - c) * 111195, (b - d) * 111195 * cos(lat))
    }
    private fun writeConverted(stream: InputStream, writer: BufferedWriter,
                               version: LocationData.DataVersion, output: String, creationTime: LocalDateTime) {
        val input = BufferedInputStream(stream)
        if (readFully(input, ByteArray(8)) != 8) error("BONヘッダーが不完全です")
        if (output == "vbo") {
            // RaceChrono uses the creation date from the first VBO line.
            val creation = creationTime.format(DateTimeFormatter.ofPattern("dd/MM/yyyy 'at' HH:mm:ss"))
            writer.write("File created on $creation"); writer.newLine(); writer.newLine()
            writer.write("[header]"); writer.newLine()
            listOf("satellites", "time", "latitude", "longitude", "velocity kmh",
                "heading", "height").forEach { writer.write(it); writer.newLine() }
            writer.newLine()
            writer.write("[comments]"); writer.newLine()
            writer.write("Converted from BON by eightlien"); writer.newLine(); writer.newLine()
            writer.write("[column names]"); writer.newLine()
            writer.write("sats time lat long velocity heading height"); writer.newLine(); writer.newLine()
            writer.write("[data]"); writer.newLine()
        }
        var previous: LocationData? = null
        var pending: LocationData? = null
        val chunk = ByteArray(26)
        while (true) {
            val n = readFully(input, chunk) ?: break
            if (n != 26) error("不完全なBONレコード")
            val point = LocationData(chunk, previous, version)
            // A BON4 sample can have a one-second rollover glitch (e.g.
            // 06:32:27.890, 06:32:28.990, 06:32:28.100).
            // Fix the middle sample before writing it; RaceChrono otherwise
            // interprets the backwards time jump as a 24-hour rollover.
            val buffered = pending
            if (buffered != null) {
                if (output == "vbo" &&
                    buffered.m_timestamp.isAfter(point.m_timestamp) &&
                    java.time.Duration.between(point.m_timestamp, buffered.m_timestamp).toMillis() in 1..999
                ) {
                    buffered.m_timestamp = buffered.m_timestamp.minusSeconds(1)
                }
                writer.write(if (output == "nmea") buffered.getNmea() else VboExporter.line(buffered))
                writer.newLine()
            }
            pending = point
            previous = point
        }
        pending?.let {
            writer.write(if (output == "nmea") it.getNmea() else VboExporter.line(it))
            writer.newLine()
        }
    }
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
