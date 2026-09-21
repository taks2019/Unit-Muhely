package hu.unitool.app

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.chaquo.python.Python
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.function.Consumer

data class TextItem(val id: String, val type: String, val name: String, val n: Int, val sample: String)
data class ScanFile(
    val file: String, val kind: String, val types: List<Pair<String, Int>>,
    val texts: List<TextItem>, val noTypeTree: Int, val error: String
)
data class PlainFile(val file: String, val size: Long, val sample: String)
data class ScanData(
    val files: List<ScanFile>, val plain: List<PlainFile>, val unknown: List<String>,
    val il2cpp: Boolean, val decoder: Boolean
)

class AppVm(private val app: Application) : AndroidViewModel(app) {
    val looks = LookStore(app)
    val log = mutableStateListOf<String>()
    var busy by mutableStateOf<String?>(null); private set
    val projects = mutableStateListOf<String>()
    var current by mutableStateOf<String?>(null); private set
    var scan by mutableStateOf<ScanData?>(null); private set
    var unpackNote by mutableStateOf(""); private set
    var exportStat by mutableStateOf(""); private set
    var importStat by mutableStateOf(""); private set
    var built by mutableStateOf<File?>(null); private set
    var storageOk by mutableStateOf(false); private set
    val opts = mutableStateMapOf("text" to true, "mono" to true, "image" to true, "audio" to true, "plain" to true)

    private val handler = Handler(Looper.getMainLooper())
    private val cb = Consumer<String> { s -> handler.post { addLog(s) } }
    private val prefs = app.getSharedPreferences("state", 0)

    init {
        refreshStorage()
        current = prefs.getString("current", null)
        current?.let { loadScan() }
    }

    // ------------------------------------------------------------ mappák
    fun refreshStorage() {
        storageOk = Environment.isExternalStorageManager()
        refreshProjects()
    }

    fun baseDir(): File {
        val d = if (storageOk) File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "UnityMuhely")
        else File(app.getExternalFilesDir(null), "UnityMuhely")
        d.mkdirs()
        return d
    }

    fun projDir(name: String? = current): File? = name?.let { File(baseDir(), it) }

    fun exportDir(): File? = projDir()?.let { File(it, "export") }

    fun refreshProjects() {
        projects.clear()
        baseDir().listFiles()?.filter { it.isDirectory }?.map { it.name }?.sorted()?.let { projects.addAll(it) }
    }

    fun select(name: String) {
        current = name
        prefs.edit().putString("current", name).apply()
        built = null; exportStat = ""; importStat = ""; unpackNote = ""
        loadScan()
    }

    fun delete(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            File(baseDir(), name).deleteRecursively()
            withContext(Dispatchers.Main) {
                if (current == name) { current = null; scan = null }
                refreshProjects()
            }
        }
    }

    fun addLog(s: String) {
        log.add(s)
        while (log.size > 600) log.removeAt(0)
    }

    // ------------------------------------------------------------ háttérfeladat
    private fun job(label: String, block: suspend () -> Unit) {
        if (busy != null) return
        busy = label
        viewModelScope.launch(Dispatchers.IO) {
            try {
                block()
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) { addLog("Hiba: ${e.message}") }
            } finally {
                withContext(Dispatchers.Main) { busy = null }
            }
        }
    }

    private fun py(fn: String, vararg args: Any?): String =
        Python.getInstance().getModule("engine").callAttr(fn, *args).toString()

    // ------------------------------------------------------------ műveletek
    fun importPackage(uri: Uri) = job("Csomag másolása") {
        val ctx = app
        var display = "csomag.apk"
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0) display = c.getString(i)
            }
        }
        val ext = display.substringAfterLast('.', "apk").lowercase()
        val name = display.substringBeforeLast('.').replace(Regex("[^\\w\\-. ]"), "_").take(40).ifBlank { "projekt" }
        val dir = File(baseDir(), name).apply { mkdirs() }
        dir.listFiles()?.filter { it.name.startsWith("original.") }?.forEach { it.delete() }
        val target = File(dir, "original.$ext")
        withContext(Dispatchers.Main) { addLog("Másolás: $display") }
        ctx.contentResolver.openInputStream(uri)!!.use { i -> target.outputStream().use { o -> i.copyTo(o, 1 shl 20) } }
        withContext(Dispatchers.Main) { refreshProjects(); select(name) }
        runUnpack(target, dir)
    }

    private suspend fun runUnpack(src: File, dir: File) {
        withContext(Dispatchers.Main) { busy = "Kibontás" }
        val res = JSONObject(py("unpack", src.absolutePath, dir.absolutePath, cb))
        val notes = mutableListOf("${res.getInt("files")} fájl kibontva")
        if (res.getBoolean("il2cpp")) notes.add("IL2CPP játék")
        if (res.getBoolean("xapk")) notes.add("XAPK: a belső APK-kat még nem bontom ki külön")
        withContext(Dispatchers.Main) { unpackNote = notes.joinToString(" · ") }
    }

    fun reUnpack() {
        val dir = projDir() ?: return
        val src = dir.listFiles()?.firstOrNull { it.name.startsWith("original.") } ?: return
        job("Kibontás") { runUnpack(src, dir) }
    }

    fun runScan() {
        val dir = projDir() ?: return
        job("Scanner") {
            py("scan", dir.absolutePath, cb)
            val data = parseScan(File(dir, "scan.json").readText())
            withContext(Dispatchers.Main) { scan = data }
        }
    }

    private fun loadScan() {
        val f = projDir()?.let { File(it, "scan.json") }
        scan = if (f != null && f.exists()) runCatching { parseScan(f.readText()) }.getOrNull() else null
    }

    private fun parseScan(text: String): ScanData {
        val o = JSONObject(text)
        val files = o.getJSONArray("files").let { a ->
            (0 until a.length()).map { i ->
                val f = a.getJSONObject(i)
                val ty = f.getJSONObject("types")
                val types = ty.keys().asSequence().map { it to ty.getInt(it) }.sortedByDescending { it.second }.toList()
                val tx = f.getJSONArray("texts").let { t ->
                    (0 until t.length()).map { j ->
                        val x = t.getJSONObject(j)
                        TextItem(x.getString("id"), x.getString("type"), x.getString("name"), x.getInt("n"), x.getString("sample"))
                    }
                }
                ScanFile(f.getString("file"), f.getString("kind"), types, tx, f.getInt("no_typetree"), f.getString("error"))
            }
        }
        val plain = o.getJSONArray("plain").let { a ->
            (0 until a.length()).map { i -> a.getJSONObject(i).let { PlainFile(it.getString("file"), it.getLong("size"), it.getString("sample")) } }
        }
        val unk = o.getJSONArray("unknown").let { a -> (0 until a.length()).map { a.getString(it) } }
        return ScanData(files, plain, unk, o.getBoolean("il2cpp"), o.getBoolean("decoder"))
    }

    fun runExport() {
        val dir = projDir() ?: return
        val o = JSONObject(opts.toMap())
        job("Export") {
            val r = JSONObject(py("export", dir.absolutePath, o.toString(), cb))
            val s = "Szöveg ${r.getInt("text")} · Mono ${r.getInt("mono")} · Kép ${r.getInt("image")} · " +
                "Hang ${r.getInt("audio")} · Sima ${r.getInt("plain")} · Kihagyva ${r.getInt("skipped")}"
            withContext(Dispatchers.Main) { exportStat = s }
        }
    }

    fun runImport() {
        val dir = projDir() ?: return
        job("Import") {
            val r = JSONObject(py("import_changes", dir.absolutePath, cb))
            val s = "${r.getInt("changed_items")} elem visszaírva ${r.getInt("changed_files")} fájlba · hiba: ${r.getInt("errors")}"
            withContext(Dispatchers.Main) { importStat = s }
        }
    }

    fun runBuild() {
        val dir = projDir() ?: return
        val name = current ?: return
        job("Csomag építése") {
            val r = JSONObject(py("build", dir.absolutePath, name, cb))
            val unsigned = File(r.getString("path"))
            val finalFile = File(unsigned.parentFile, unsigned.name.replace("_unsigned", "_mod"))
            if (r.getBoolean("apk")) {
                withContext(Dispatchers.Main) { addLog("Aláírás…") }
                Signer.sign(app, unsigned, finalFile)
                unsigned.delete()
                File(finalFile.path + ".idsig").delete()
            } else {
                unsigned.renameTo(finalFile)
            }
            withContext(Dispatchers.Main) { built = finalFile; addLog("Kész: ${finalFile.absolutePath}") }
        }
    }

    // ------------------------------------------------------------ telepítés, megosztás
    private fun uriFor(f: File) = FileProvider.getUriForFile(app, "${app.packageName}.files", f)

    fun install(f: File) {
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uriFor(f), "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        app.startActivity(i)
    }

    fun share(f: File) {
        val i = Intent(Intent.ACTION_SEND).setType("application/octet-stream")
            .putExtra(Intent.EXTRA_STREAM, uriFor(f)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        app.startActivity(Intent.createChooser(i, "Megosztás").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
