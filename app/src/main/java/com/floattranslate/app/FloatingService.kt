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
import org.vosk.android.StorageService
import java.io.IOException

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
        const val BUFFER_SIZE = 4096
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        setupFloatingView()
        updateStatus("⏳ Loading model...")
        loadVoskModel()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Float Translate", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Floating translation service" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, FloatingService::class.java).apply { action = "STOP" }
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Float Translate Active")
            .setContentText("Listening and translating to English...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_delete, "Stop", stopPending)
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
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 50; y = 200
        }

        windowManager.addView(floatingView, layoutParams)
        makeDraggable()
        floatingView.findViewById<View>(R.id.btn_close).setOnClickListener { stopSelf() }
    }

    private fun makeDraggable() {
        var initX = 0; var initY = 0
        var initTX = 0f; var initTY = 0f
        var moved = false

        floatingView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = layoutParams!!.x; initY = layoutParams!!.y
                    initTX = event.rawX; initTY = event.rawY
                    moved = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initTX).toInt()
                    val dy = (event.rawY - initTY).toInt()
                    if (Math.abs(dx) > 5 || Math.abs(dy) > 5) moved = true
                    layoutParams!!.x = initX + dx
                    layoutParams!!.y = initY + dy
                    windowManager.updateViewLayout(floatingView, layoutParams)
                    true
                }
                else -> false
            }
        }
    }

    private fun loadVoskModel() {
        StorageService.unpack(this, "model-small-en-us", "model",
            { model ->
                this.model = model
                handler.post { updateStatus("🎙 Listening...") }
                startRecording()
            },
            { exception ->
                handler.post { updateStatus("❌ Model load failed") }
            }
        )
    }

    private fun startRecording() {
        try {
            recognizer = Recognizer(model, SAMPLE_RATE.toFloat())
        } catch (e: IOException) {
            handler.post { updateStatus("❌ Recognizer error") }
            return
        }

        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(BUFFER_SIZE)

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
            val buffer = ShortArray(BUFFER_SIZE / 2)
            while (isRecording) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: break
                if (read > 0) {
                    val byteBuffer = ByteArray(read * 2)
                    for (i in 0 until read) {
                        byteBuffer[i * 2] = (buffer[i].toInt() and 0xFF).toByte()
                        byteBuffer[i * 2 + 1] = (buffer[i].toInt() shr 8 and 0xFF).toByte()
                    }

                    val rec = recognizer ?: break
                    if (rec.acceptWaveForm(byteBuffer, byteBuffer.size)) {
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
            val text = org.json.JSONObject(json).optString("text", "").trim()
            if (text.isBlank()) return
            handler.post { updateStatus("🔄 Translating...") }
            translatorHelper.detectAndTranslate(
                text,
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
            val partial = org.json.JSONObject(json).optString("partial", "").trim()
            if (partial.isNotBlank()) {
                handler.post { updateStatus("🎙 \"$partial\"") }
            }
        } catch (_: Exception) {}
    }

    private fun updateStatus(msg: String) {
        tvStatus.text = msg
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") stopSelf()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isRecording = false
        audioRecord?.stop()
        audioRecord?.release()
        recognizer?.close()
        model?.close()
        translatorHelper.close()
        try { windowManager.removeView(floatingView) } catch (_: Exception) {}
    }
}
