package com.floattranslate.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import org.vosk.Model
import org.vosk.Recognizer
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

class FloatingService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var tvTranslation: TextView
    private lateinit var tvStatus: TextView
    private lateinit var ivMic: ImageView

    private val translatorHelper = TranslatorHelper()
    private val handler = Handler(Looper.getMainLooper())

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordThread: Thread? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    companion object {
        const val CHANNEL_ID = "FloatTranslateChannel"
        const val NOTIF_ID = 1
        const val SAMPLE_RATE = 16000
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        setupFloatingView()
        updateStatus("⏳ Loading model...")
        Thread { loadModel() }.start()
    }

    private fun loadModel() {
        try {
            val modelDir = File(filesDir, "vosk-model")
            if (!modelDir.exists() || modelDir.listFiles().isNullOrEmpty()) {
                handler.post { updateStatus("📦 Unpacking model...") }
                modelDir.mkdirs()
                unpackModelFromAssets(modelDir)
            }
            handler.post { updateStatus("🔧 Initializing...") }
            model = Model(modelDir.absolutePath)
            handler.post { updateStatus("🎙 Listening...") }
            startRecording()
        } catch (e: Exception) {
            handler.post { updateStatus("❌ ${e.message?.take(40)}") }
        }
    }

    private fun unpackModelFromAssets(destDir: File) {
        assets.open("model-small-en-us.zip").use { input ->
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    // strip top-level folder from zip path
                    val parts = entry.name.split("/").drop(1)
                    if (parts.isEmpty() || parts.all { it.isEmpty() }) {
                        entry = zip.nextEntry
                        continue
                    }
                    val relativePath = parts.joinToString("/")
                    val outFile = File(destDir, relativePath)
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { out ->
                            zip.copyTo(out)
                        }
                    }
                    entry = zip.nextEntry
                }
            }
        }
    }

    private fun startRecording() {
        try {
            recognizer = Recognizer(model, SAMPLE_RATE.toFloat())
        } catch (e: Exception) {
            handler.post { updateStatus("❌ Recognizer error: ${e.message?.take(30)}") }
            return
        }

        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(8192)

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        } catch (e: SecurityException) {
            handler.post { updateStatus("❌ Mic permission denied") }
            return
        }

        audioRecord?.startRecording()
        isRecording = true

        recordThread = Thread {
            val buffer = ByteArray(bufferSize)
            while (isRecording) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: break
                if (read > 0) {
                    val rec = recognizer ?: break
                    val chunk = buffer.copyOf(read)
                    if (rec.acceptWaveForm(chunk, read)) {
                        val resultJson = rec.result
                        processResult(resultJson)
                    } else {
                        val partial = rec.partialResult
                        processPartial(partial)
                    }
                }
            }
        }.also { it.start() }
    }

    private fun processResult(json: String) {
        try {
            val text = JSONObject(json).optString("text", "").trim()
            if (text.isBlank()) return
            handler.post { updateStatus("🔄 Translating...") }
            translatorHelper.detectAndTranslate(text,
                onResult = { translated ->
                    handler.post {
                        tvTranslation.text = translated
                        updateStatus("✅ Done")
                        handler.postDelayed({ updateStatus("🎙 Listening...") }, 3000)
                    }
                },
                onError = { err ->
                    handler.post {
                        tvTranslation.text = text
                        updateStatus("⚠ $err")
                        handler.postDelayed({ updateStatus("🎙 Listening...") }, 3000)
                    }
                }
            )
        } catch (_: Exception) {}
    }

    private fun processPartial(json: String) {
        try {
            val p = JSONObject(json).optString("partial", "").trim()
            if (p.isNotBlank()) handler.post { updateStatus("🎙 \"$p\"") }
        } catch (_: Exception) {}
    }

    private fun updateStatus(msg: String) {
        tvStatus.text = msg
    }

    private fun createNotificationChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "Float Translate", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, FloatingService::class.java).apply { action = "STOP" }
        val pi = PendingIntent.getService(this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Float Translate Active")
            .setContentText("Listening and translating...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_delete, "Stop", pi)
            .build()
    }

    private fun setupFloatingView() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        floatingView = LayoutInflater.from(this).inflate(R.layout.floating_widget, null)
        tvTranslation = floatingView.findViewById(R.id.tv_translation)
        tvStatus = floatingView.findViewById(R.id.tv_status)
        ivMic = floatingView.findViewById(R.id.iv_mic)

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 50; y = 200 }

        windowManager.addView(floatingView, layoutParams)
        makeDraggable()
        floatingView.findViewById<View>(R.id.btn_close).setOnClickListener { stopSelf() }
    }

    private fun makeDraggable() {
        var iX = 0; var iY = 0; var iTX = 0f; var iTY = 0f
        floatingView.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> { iX = layoutParams!!.x; iY = layoutParams!!.y; iTX = ev.rawX; iTY = ev.rawY; true }
                MotionEvent.ACTION_MOVE -> {
                    layoutParams!!.x = iX + (ev.rawX - iTX).toInt()
                    layoutParams!!.y = iY + (ev.rawY - iTY).toInt()
                    windowManager.updateViewLayout(floatingView, layoutParams); true
                }
                else -> false
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") stopSelf()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isRecording = false
        audioRecord?.stop(); audioRecord?.release()
        recognizer?.close(); model?.close()
        translatorHelper.close()
        try { windowManager.removeView(floatingView) } catch (_: Exception) {}
    }
}
