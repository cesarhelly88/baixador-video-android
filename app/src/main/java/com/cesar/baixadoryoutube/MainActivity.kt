package com.cesar.baixadoryoutube

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDL.UpdateChannel
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var urlInput: EditText
    private lateinit var pasteBtn: Button
    private lateinit var formatSpinner: Spinner
    private lateinit var downloadBtn: Button
    private lateinit var cancelBtn: Button
    private lateinit var updateBtn: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView

    @Volatile private var engineReady = false
    @Volatile private var downloading = false

    private val processId = "BaixadorVideoDownload"

    private val workDir: File by lazy {
        (getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir)
            .resolve("trabalho")
            .also { it.mkdirs() }
    }

    private data class DownloadMode(
        val title: String,
        val kind: String,
        val format: String? = null
    ) {
        override fun toString(): String = title
    }

    private val modes = listOf(
        DownloadMode("Vídeo MP4 — melhor qualidade", "video", "bv*[ext=mp4]+ba[ext=m4a]/b[ext=mp4]/bv*+ba/b"),
        DownloadMode("Vídeo MP4 — até 1080p", "video", "bv*[height<=1080][ext=mp4]+ba[ext=m4a]/b[height<=1080][ext=mp4]/bv*[height<=1080]+ba/b[height<=1080]"),
        DownloadMode("Vídeo MP4 — até 720p", "video", "bv*[height<=720][ext=mp4]+ba[ext=m4a]/b[height<=720][ext=mp4]/bv*[height<=720]+ba/b[height<=720]"),
        DownloadMode("Áudio MP3 — melhor qualidade", "mp3")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        urlInput = findViewById(R.id.urlInput)
        pasteBtn = findViewById(R.id.pasteBtn)
        formatSpinner = findViewById(R.id.formatSpinner)
        downloadBtn = findViewById(R.id.downloadBtn)
        cancelBtn = findViewById(R.id.cancelBtn)
        updateBtn = findViewById(R.id.updateBtn)
        progressBar = findViewById(R.id.progressBar)
        statusText = findViewById(R.id.statusText)

        formatSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            modes
        )

        downloadBtn.isEnabled = false

        pasteBtn.setOnClickListener { pasteClipboard() }
        downloadBtn.setOnClickListener { startDownload() }
        cancelBtn.setOnClickListener { cancelDownload() }
        updateBtn.setOnClickListener { updateEngine() }

        handleSharedText(intent)
        initializeEngine()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleSharedText(intent)
    }

    private fun handleSharedText(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            extractYoutubeUrl(text)?.let {
                urlInput.setText(it)
                status("Link recebido pelo menu Compartilhar.")
            }
        }
    }

    private fun extractYoutubeUrl(text: String): String? {
        val regex = Regex("""https?://[^\s]+""")
        return regex.findAll(text)
            .map { it.value.trimEnd('.', ',', ';', ')', ']', '}') }
            .firstOrNull {
                it.contains("youtube.com", ignoreCase = true) ||
                it.contains("youtu.be", ignoreCase = true)
            }
    }

    private fun pasteClipboard() {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip
            ?.getItemAt(0)
            ?.coerceToText(this)
            ?.toString()
            .orEmpty()

        val link = extractYoutubeUrl(text) ?: text.trim()
        if (link.isBlank()) {
            toast("A área de transferência está vazia.")
            return
        }
        urlInput.setText(link)
    }

    private fun initializeEngine() {
        status("Inicializando o motor de download...")
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                YoutubeDL.getInstance().init(applicationContext)
                FFmpeg.getInstance().init(applicationContext)
                engineReady = true
                withContext(Dispatchers.Main) {
                    status("Pronto. Cole um link do YouTube.")
                    downloadBtn.isEnabled = true
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    status("Falha ao iniciar: ${shortError(e)}")
                    downloadBtn.isEnabled = false
                }
            }
        }
    }

    private fun updateEngine() {
        if (!engineReady || downloading) return
        updateBtn.isEnabled = false
        downloadBtn.isEnabled = false
        status("Atualizando o yt-dlp...")
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                YoutubeDL.getInstance().updateYoutubeDL(applicationContext, UpdateChannel.NIGHTLY)
                withContext(Dispatchers.Main) {
                    status("Motor atualizado. Pronto para baixar.")
                    toast("Atualização concluída.")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    status("Não foi possível atualizar agora: ${shortError(e)}")
                }
            } finally {
                withContext(Dispatchers.Main) {
                    updateBtn.isEnabled = true
                    downloadBtn.isEnabled = engineReady
                }
            }
        }
    }

    private fun startDownload() {
        if (!engineReady || downloading) return

        val url = urlInput.text.toString().trim()
        if (!isYoutubeUrl(url)) {
            urlInput.error = "Cole um link válido do YouTube."
            return
        }

        if (Build.VERSION.SDK_INT <= 28 &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                50
            )
            toast("Autorize o armazenamento e toque em BAIXAR novamente.")
            return
        }

        val mode = modes[formatSpinner.selectedItemPosition]
        downloading = true
        progressBar.progress = 0
        downloadBtn.isEnabled = false
        updateBtn.isEnabled = false
        cancelBtn.visibility = android.view.View.VISIBLE
        status("Preparando download...")

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                cleanWorkDir()

                val request = YoutubeDLRequest(url)
                request.addOption("--no-playlist")
                request.addOption("--no-mtime")
                request.addOption("--newline")
                request.addOption("--retries", "10")
                request.addOption("--fragment-retries", "10")
                request.addOption(
                    "-o",
                    "${workDir.absolutePath}/%(title).100s [%(id)s].%(ext)s"
                )

                if (mode.kind == "mp3") {
                    request.addOption("-x")
                    request.addOption("--audio-format", "mp3")
                    request.addOption("--audio-quality", "0")
                } else {
                    request.addOption("-f", mode.format!!)
                    request.addOption("--merge-output-format", "mp4")
                }

                YoutubeDL.getInstance().execute(
                    request,
                    processId
                ) { progress, eta, line ->
                    runOnUiThread {
                        progressBar.progress = progress.toInt().coerceIn(0, 100)
                        val etaText = if (eta > 0) " • faltam ~${eta}s" else ""
                        val cleanLine = line
                            .replace(Regex("""\u001B\[[;\d]*m"""), "")
                            .takeLast(160)
                        status("${progress.toInt()}%$etaText\n$cleanLine")
                    }
                    kotlin.Unit
                }

                val finishedFiles = workDir.listFiles()
                    ?.filter { it.isFile && isFinishedMedia(it) }
                    .orEmpty()

                if (finishedFiles.isEmpty()) {
                    throw IllegalStateException("O download terminou, mas não encontrei o arquivo final.")
                }

                val published = mutableListOf<String>()
                for (file in finishedFiles) {
                    publishToDownloads(file)
                    published += file.name
                }

                withContext(Dispatchers.Main) {
                    progressBar.progress = 100
                    status(
                        "Concluído!\nSalvo em Downloads/Baixador de Vídeo\n" +
                        published.joinToString("\n")
                    )
                    toast("Download concluído.")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    status("Erro no download:\n${shortError(e)}")
                }
            } finally {
                downloading = false
                withContext(Dispatchers.Main) {
                    cancelBtn.visibility = android.view.View.GONE
                    downloadBtn.isEnabled = engineReady
                    updateBtn.isEnabled = engineReady
                }
            }
        }
    }

    private fun cancelDownload() {
        if (!downloading) return
        try {
            YoutubeDL.getInstance().destroyProcessById(processId)
            status("Cancelando download...")
        } catch (e: Exception) {
            status("Não foi possível cancelar: ${shortError(e)}")
        }
    }

    private fun isYoutubeUrl(url: String): Boolean {
        return url.startsWith("http", ignoreCase = true) &&
            (url.contains("youtube.com", ignoreCase = true) ||
             url.contains("youtu.be", ignoreCase = true))
    }

    private fun cleanWorkDir() {
        workDir.listFiles()?.forEach {
            if (it.isFile) it.delete()
            else it.deleteRecursively()
        }
    }

    private fun isFinishedMedia(file: File): Boolean {
        val n = file.name.lowercase()
        if (n.endsWith(".part") || n.endsWith(".ytdl") || n.endsWith(".temp")) return false
        return listOf(".mp4", ".mkv", ".webm", ".mp3", ".m4a", ".opus", ".aac")
            .any { n.endsWith(it) }
    }

    private fun publishToDownloads(source: File): Uri? {
        val mime = mimeFor(source)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, source.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/Baixador de Vídeo"
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }

            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI

            val uri = resolver.insert(collection, values)
                ?: throw IllegalStateException("Não consegui criar o arquivo em Downloads.")

            try {
                resolver.openOutputStream(uri)?.use { out ->
                    FileInputStream(source).use { input ->
                        input.copyTo(out)
                    }
                } ?: throw IllegalStateException("Não consegui gravar o arquivo em Downloads.")

                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                return uri
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
        } else {
            val folder = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "Baixador de Vídeo"
            )
            if (!folder.exists()) folder.mkdirs()
            val dest = uniqueFile(folder, source.name)
            FileInputStream(source).use { input ->
                FileOutputStream(dest).use { output ->
                    input.copyTo(output)
                }
            }
            return Uri.fromFile(dest)
        }
    }

    private fun uniqueFile(folder: File, name: String): File {
        var candidate = File(folder, name)
        if (!candidate.exists()) return candidate

        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 2
        while (candidate.exists()) {
            candidate = File(folder, "$base ($i)$ext")
            i++
        }
        return candidate
    }

    private fun mimeFor(file: File): String {
        val ext = file.extension.lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: when (ext) {
                "mp3" -> "audio/mpeg"
                "m4a" -> "audio/mp4"
                "opus" -> "audio/opus"
                "webm" -> "video/webm"
                "mkv" -> "video/x-matroska"
                else -> "video/mp4"
            }
    }

    private fun shortError(e: Exception): String {
        return (e.message ?: e.javaClass.simpleName)
            .replace(workDir.absolutePath, "[pasta temporária]")
            .takeLast(800)
    }

    private fun status(text: String) {
        statusText.text = text
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }
}
