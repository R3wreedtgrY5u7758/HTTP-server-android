package com.example.httpserver2

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.*
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

class HttpServer(
    private val context: Context,
    private val port: Int,
    private val mode: Mode,
    private val htmlContent: String,
    private val rootUri: Uri?,
    private val allowUpload: Boolean,
    private val allowModify: Boolean,
    private val onLog: (String) -> Unit
) {
    enum class Mode { HTML, DIRECTORY }

    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null

    val requestsTotal = AtomicLong(0)
    val bytesSent = AtomicLong(0)
    val bytesReceived = AtomicLong(0)

    private val rootDoc: DocumentFile? by lazy {
        rootUri?.let { DocumentFile.fromTreeUri(context, it) }
    }

    fun start() {
        if (running) return
        running = true
        thread(name = "http-server") {
            try {
                serverSocket = ServerSocket(port).apply { reuseAddress = true }
                onLog("Server started on port $port")
                onLog("http://${localIp()}:$port")
                while (running) {
                    val client = try { serverSocket!!.accept() } catch (e: Exception) { break }
                    thread { handle(client) }
                }
            } catch (e: Exception) {
                onLog("Server error: ${e.message}")
            } finally {
                onLog("Server stopped")
            }
        }
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
    }

    fun isRunning() = running

    private fun handle(socket: Socket) {
        socket.use { s ->
            try {
                val rawIn = BufferedInputStream(s.getInputStream())
                val out = BufferedOutputStream(s.getOutputStream())

                val reader = BufferedReader(InputStreamReader(rawIn, Charsets.ISO_8859_1))
                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0]
                val rawPath = parts[1]
                val path = rawPath.substringBefore('?')
                val query = parseQuery(rawPath.substringAfter('?', ""))

                val headers = mutableMapOf<String, String>()
                var line: String?
                do {
                    line = reader.readLine()
                    if (line.isNullOrEmpty()) break
                    val idx = line.indexOf(':')
                    if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] =
                        line.substring(idx + 1).trim()
                } while (true)

                requestsTotal.incrementAndGet()

                when {
                    method == "GET" && (path == "/" || path == "/index.html") -> {
                        onLog("${s.inetAddress.hostAddress}  GET  $path")
                        if (mode == Mode.HTML) serveHtml(out) else serveDirectory(out, "")
                    }
                    method == "GET" && path == "/api/stats" -> serveStats(out)
                    method == "GET" && path.startsWith("/file/") -> {
                        val rel = URLDecoder.decode(path.removePrefix("/file/"), "UTF-8")
                        onLog("${s.inetAddress.hostAddress}  GET  $rel")
                        serveFile(out, rel)
                    }
                    method == "POST" && path == "/upload" && allowUpload -> {
                        val name = query["name"] ?: "upload.bin"
                        val sub = query["path"] ?: ""
                        onLog("${s.inetAddress.hostAddress}  UPLOAD  $sub/$name")
                        handleUpload(rawIn, out, headers, sub, name)
                    }
                    method == "POST" && path == "/delete" && allowModify -> {
                        val body = readBody(reader, headers)
                        val params = parseQuery(body)
                        val target = params["path"] ?: ""
                        onLog("${s.inetAddress.hostAddress}  DELETE  $target")
                        deletePath(out, target)
                    }
                    method == "GET" && mode == Mode.DIRECTORY -> {
                        onLog("GET  $path")
                        val rel = URLDecoder.decode(path.trim('/'), "UTF-8")
                        val doc = resolve(rel)
                        when {
                            doc == null -> sendError(out, 404, "Not Found")
                            doc.isDirectory -> serveDirectory(out, rel)
                            doc.isFile -> serveFile(out, rel)
                            else -> sendError(out, 404, "Not Found")
                        }
                    }
                    method == "POST" && path == "/mkdir" && allowModify -> {
                        val body = readBody(reader, headers)
                        val params = parseQuery(body)
                        onLog("${s.inetAddress.hostAddress}  MKDIR  ${params["path"]}/${params["name"]}")
                        makeDir(out, params["path"] ?: "", params["name"] ?: "")
                    }
                    else -> sendError(out, 404, "Not Found")
                }
                out.flush()
            } catch (e: Exception) {
                onLog("Client error: ${e.message}")
            }
        }
    }

    private fun parseQuery(q: String): Map<String, String> {
        if (q.isEmpty()) return emptyMap()
        return q.split("&").mapNotNull {
            val i = it.indexOf('=')
            if (i < 0) null
            else URLDecoder.decode(it.substring(0, i), "UTF-8") to
                    URLDecoder.decode(it.substring(i + 1), "UTF-8")
        }.toMap()
    }

    private fun readBody(reader: BufferedReader, headers: Map<String, String>): String {
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        if (len <= 0) return ""
        val buf = CharArray(len)
        var read = 0
        while (read < len) {
            val n = reader.read(buf, read, len - read)
            if (n < 0) break
            read += n
        }
        return String(buf, 0, read)
    }

    private fun serveHtml(out: BufferedOutputStream) {
        val bytes = htmlContent.toByteArray(Charsets.UTF_8)
        writeHeaders(out, "200 OK", "text/html; charset=utf-8", bytes.size)
        out.write(bytes); bytesSent.addAndGet(bytes.size.toLong())
    }

    private fun serveStats(out: BufferedOutputStream) {
        val json = """{"requests":${requestsTotal.get()},"bytes_sent":${bytesSent.get()},"bytes_received":${bytesReceived.get()}}"""
        val bytes = json.toByteArray(Charsets.UTF_8)
        writeHeaders(out, "200 OK", "application/json", bytes.size)
        out.write(bytes)
    }

    private fun resolve(rel: String): DocumentFile? {
        val root = rootDoc ?: return null
        if (rel.isEmpty() || rel == "/") return root
        var current: DocumentFile = root
        for (seg in rel.trim('/').split('/')) {
            if (seg.isEmpty()) continue
            current = current.findFile(seg) ?: return null
        }
        return current
    }

    private fun serveFile(out: BufferedOutputStream, rel: String) {
        val doc = resolve(rel)
        if (doc == null || !doc.isFile) { sendError(out, 404, "Not Found"); return }
        val mime = doc.type ?: guessMime(doc.name ?: "")
        val len = doc.length().toInt()
        writeHeaders(out, "200 OK", mime, len,
            "Content-Disposition: attachment; filename=\"${doc.name}\"\r\n")
        try {
            context.contentResolver.openInputStream(doc.uri)?.use { ins ->
                val buf = ByteArray(8192)
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    bytesSent.addAndGet(n.toLong())
                }
            }
        } catch (e: Exception) { onLog("Read error: ${e.message}") }
    }

    private fun serveDirectory(out: BufferedOutputStream, rel: String) {
        val dir = resolve(rel)
        if (dir == null || !dir.isDirectory) { sendError(out, 404, "Not Found"); return }

        val items = dir.listFiles().sortedWith(
            compareBy({ !it.isDirectory }, { (it.name ?: "").lowercase() })
        )

        val sb = StringBuilder()
        sb.append("<!DOCTYPE html><html><head><meta charset='utf-8'>")
        sb.append("<meta name='viewport' content='width=device-width,initial-scale=1'>")
        sb.append("<title>Files: /${rel}</title><style>")
        sb.append("body{font-family:-apple-system,Segoe UI,Roboto,sans-serif;background:#0f1115;color:#e6e6e6;margin:0;padding:20px}")
        sb.append("h1{font-size:20px;margin:0 0 16px;color:#3ea6ff}")
        sb.append(".container{max-width:900px;margin:0 auto}")
        sb.append("table{width:100%;border-collapse:collapse;font-size:14px}")
        sb.append("th,td{text-align:left;padding:8px 10px;border-bottom:1px solid #262b34}")
        sb.append("th{color:#8fa9c7;font-weight:500;font-size:12px;text-transform:uppercase;letter-spacing:.06em}")
        sb.append("a{color:#3ea6ff;text-decoration:none}a:hover{text-decoration:underline}")
        sb.append(".dir{color:#ffb454;font-weight:600}")
        sb.append(".size{color:#6b7280;font-size:12px}")
        sb.append(".actions{margin:16px 0;display:flex;gap:8px;flex-wrap:wrap}")
        sb.append(".btn{background:#1a1d23;border:1px solid #262b34;color:#e6e6e6;padding:8px 14px;border-radius:8px;cursor:pointer;font-size:13px;text-decoration:none;display:inline-block}")
        sb.append(".btn:hover{background:#262b34}")
        sb.append(".btn-danger{color:#ff7b72}")
        sb.append(".drop{border:2px dashed #3ea6ff;border-radius:12px;padding:24px;text-align:center;color:#8fa9c7;margin:16px 0}")
        sb.append(".drop.over{background:#1a1d23;border-color:#7ee787}")
        sb.append("input[type=file]{display:none}")
        sb.append(".breadcrumb{margin-bottom:12px;font-size:13px;color:#8fa9c7}")
        sb.append("</style></head><body><div class='container'>")

        sb.append("<div class='breadcrumb'><a href='/'>root</a>")
        if (rel.isNotEmpty()) {
            val parts = rel.trim('/').split('/')
            var acc = ""
            for (p in parts) {
                acc += "$p/"
                sb.append(" / <a href='/$acc'>$p</a>")
            }
        }
        sb.append("</div>")
        sb.append("<h1>📁 /${rel}</h1>")

        if (allowUpload) {
            sb.append("<div class='actions'>")
            sb.append("<label class='btn' for='fileInput'>📤 Upload files</label>")
            sb.append("<input type='file' id='fileInput' multiple>")
            if (allowModify) sb.append("<button class='btn' onclick='mkDir()'>📁 New folder</button>")
            sb.append("</div>")
            sb.append("<div class='drop' id='drop'>Drop files here or click Upload</div>")
        }

        sb.append("<table><tr><th>Name</th><th>Size</th><th></th></tr>")

        if (rel.isNotEmpty()) {
            val parent = rel.trim('/').substringBeforeLast('/', "")
            sb.append("<tr><td><a class='dir' href='/${parent}${if (parent.isEmpty()) "" else "/"}'>⬆ ..</a></td><td></td><td></td></tr>")
        }

        for (f in items) {
            val name = f.name ?: continue
            val href = "/file/${rel}${if (rel.isEmpty()) "" else "/"}$name"
            val browseHref = "/${rel}${if (rel.isEmpty()) "" else "/"}$name/"
            if (f.isDirectory) {
                sb.append("<tr><td><a class='dir' href='$browseHref'>📁 $name</a></td><td class='size'>—</td><td>")
                if (allowModify) sb.append("<button class='btn btn-danger' onclick=\"del('${rel}${if (rel.isEmpty()) "" else "/"}$name')\">Delete</button>")
                sb.append("</td></tr>")
            } else {
                sb.append("<tr><td><a href='$href'>📄 $name</a></td><td class='size'>${formatSize(f.length())}</td><td>")
                sb.append("<a class='btn' href='$href'>Download</a> ")
                if (allowModify) sb.append("<button class='btn btn-danger' onclick=\"del('${rel}${if (rel.isEmpty()) "" else "/"}$name')\">Delete</button>")
                sb.append("</td></tr>")
            }
        }
        sb.append("</table></div>")

        sb.append("""
<script>
var CUR='$rel';
function del(p){if(!confirm('Delete '+p+'?'))return;
var x=new XMLHttpRequest();x.open('POST','/delete');
x.setRequestHeader('Content-Type','application/x-www-form-urlencoded');
x.onload=function(){location.reload()};
x.send('path='+encodeURIComponent(p));}
function mkDir(){var n=prompt('Folder name:');if(!n)return;
var x=new XMLHttpRequest();x.open('POST','/mkdir');
x.setRequestHeader('Content-Type','application/x-www-form-urlencoded');
x.onload=function(){location.reload()};
x.send('path='+encodeURIComponent(CUR)+'&name='+encodeURIComponent(n));}
var drop=document.getElementById('drop');
if(drop){
['dragenter','dragover'].forEach(function(e){drop.addEventListener(e,function(ev){ev.preventDefault();drop.classList.add('over')})});
['dragleave','drop'].forEach(function(e){drop.addEventListener(e,function(ev){ev.preventDefault();drop.classList.remove('over')})});
drop.addEventListener('drop',function(ev){upload(ev.dataTransfer.files)});}
var fi=document.getElementById('fileInput');
if(fi)fi.addEventListener('change',function(){upload(fi.files)});
function upload(files){
if(!files||!files.length)return;var i=0;
function next(){if(i>=files.length){location.reload();return}
var f=files[i++];
var x=new XMLHttpRequest();
x.open('POST','/upload?name='+encodeURIComponent(f.name)+'&path='+encodeURIComponent(CUR));
x.onload=next;x.onerror=next;
if(drop)drop.textContent='Uploading: '+f.name;
x.send(f);}
next();}
</script>
""")
        sb.append("</body></html>")

        val bytes = sb.toString().toByteArray(Charsets.UTF_8)
        writeHeaders(out, "200 OK", "text/html; charset=utf-8", bytes.size)
        out.write(bytes); bytesSent.addAndGet(bytes.size.toLong())
    }

    private fun handleUpload(
        rawIn: BufferedInputStream,
        out: BufferedOutputStream,
        headers: Map<String, String>,
        subPath: String,
        name: String
    ) {
        val len = headers["content-length"]?.toLongOrNull() ?: 0
        if (len <= 0) { sendError(out, 400, "Empty body"); return }
        val dir = if (subPath.isEmpty()) rootDoc else resolve(subPath)
        if (dir == null || !dir.isDirectory) { sendError(out, 404, "No dir"); return }

        val safeName = name.replace("/", "_")
        dir.findFile(safeName)?.delete()
        val newDoc = dir.createFile("application/octet-stream", safeName)
        if (newDoc == null) { sendError(out, 500, "Cannot create file"); return }

        try {
            context.contentResolver.openOutputStream(newDoc.uri)?.use { fos ->
                val buf = ByteArray(8192)
                var remaining = len
                while (remaining > 0) {
                    val n = rawIn.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                    if (n < 0) break
                    fos.write(buf, 0, n)
                    remaining -= n
                    bytesReceived.addAndGet(n.toLong())
                }
            }
            onLog("Uploaded: $safeName (${formatSize(newDoc.length())})")
            writeHeaders(out, "200 OK", "text/plain", 2); out.write("OK".toByteArray())
        } catch (e: Exception) {
            sendError(out, 500, "Upload failed: ${e.message}")
        }
    }

    private fun deletePath(out: BufferedOutputStream, path: String) {
        val doc = resolve(path)
        if (doc == null) { sendError(out, 404, "Not Found"); return }
        val ok = doc.delete()
        writeHeaders(out, "200 OK", "text/plain", if (ok) 2 else 5)
        out.write((if (ok) "OK" else "ERROR").toByteArray())
    }

    private fun makeDir(out: BufferedOutputStream, path: String, name: String) {
        if (name.isBlank()) { sendError(out, 400, "No name"); return }
        val parent = if (path.isEmpty()) rootDoc else resolve(path)
        if (parent == null || !parent.isDirectory) { sendError(out, 404, "No parent"); return }
        val created = parent.createDirectory(name.replace("/", "_"))
        val ok = created != null
        writeHeaders(out, "200 OK", "text/plain", if (ok) 2 else 5)
        out.write((if (ok) "OK" else "ERROR").toByteArray())
    }

    private fun writeHeaders(
        out: OutputStream, status: String, mime: String, len: Int, extra: String = ""
    ) {
        val date = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("GMT") }.format(Date())
        val h = "HTTP/1.1 $status\r\nServer: AndroidHttpServer/1.0\r\nDate: $date\r\n" +
                "Content-Type: $mime\r\nContent-Length: $len\r\n$extra" +
                "Connection: close\r\n\r\n"
        out.write(h.toByteArray(Charsets.US_ASCII))
    }

    private fun sendError(out: BufferedOutputStream, code: Int, msg: String) {
        val body = "<h1>$code $msg</h1>".toByteArray()
        writeHeaders(out, "$code $msg", "text/html", body.size)
        out.write(body)
    }

    private fun guessMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html; charset=utf-8"
        "css" -> "text/css"; "js" -> "application/javascript"
        "json" -> "application/json"; "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"; "gif" -> "image/gif"
        "svg" -> "image/svg+xml"; "txt" -> "text/plain; charset=utf-8"
        "pdf" -> "application/pdf"; "mp4" -> "video/mp4"; "mp3" -> "audio/mpeg"
        "zip" -> "application/zip"
        else -> "application/octet-stream"
    }

    private fun formatSize(b: Long): String = when {
        b < 1024 -> "$b B"
        b < 1024 * 1024 -> "%.1f KB".format(b / 1024.0)
        b < 1024L * 1024 * 1024 -> "%.1f MB".format(b / 1024.0 / 1024.0)
        else -> "%.2f GB".format(b / 1024.0 / 1024.0 / 1024.0)
    }

    companion object {
        fun localIp(): String {
            try {
                for (iface in NetworkInterface.getNetworkInterfaces()) {
                    for (addr in iface.inetAddresses) {
                        if (!addr.isLoopbackAddress && addr is Inet4Address) {
                            return addr.hostAddress ?: "0.0.0.0"
                        }
                    }
                }
            } catch (_: Exception) {}
            return "0.0.0.0"
        }
    }
}
