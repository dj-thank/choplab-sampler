package com.choplab.sampler.audio

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import com.choplab.sampler.MainActivity
import com.choplab.sampler.R
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

class PlaybackCaptureService : Service() {
    private val running = AtomicBoolean(false)
    private var audioRecord: AudioRecord? = null
    private var mediaProjection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> requestStop()
            ACTION_START -> startCapture(intent)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        requestStop()
        super.onDestroy()
    }

    @SuppressLint("MissingPermission")
    private fun startCapture(intent: Intent) {
        if (!running.compareAndSet(false, true)) return

        createNotificationChannel()
        startForeground(
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        )

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val resultData = intent.intentExtra(EXTRA_RESULT_DATA)
        if (resultCode != Activity.RESULT_OK || resultData == null) {
            failAndStop("端末音声録音の許可情報がありません")
            return
        }

        try {
            val projectionManager = requireNotNull(
                getSystemService(MediaProjectionManager::class.java),
            ) { "MediaProjectionManagerを取得できません" }
            val projection = projectionManager.getMediaProjection(resultCode, resultData)
                ?: error("端末音声録音の許可を取得できません")
            mediaProjection = projection

            val callback = object : MediaProjection.Callback() {
                override fun onStop() {
                    requestStop()
                }
            }
            projectionCallback = callback
            projection.registerCallback(callback, Handler(Looper.getMainLooper()))

            val captureConfiguration = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            val (recorder, channels) = buildAudioRecord(captureConfiguration)
            audioRecord = recorder
            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                error("端末音声の録音を開始できません")
            }

            val outputDirectory = File(cacheDir, "captures").apply { mkdirs() }
            val outputFile = File(outputDirectory, "system_${System.currentTimeMillis()}.wav")
            CaptureEventBus.publish(PlaybackCaptureState.Recording)

            worker = Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                val minBuffer = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    if (channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                )
                val buffer = ByteArray(max(minBuffer * 2, 16_384))
                var errorMessage: String? = null

                try {
                    WavFileWriter(outputFile, SAMPLE_RATE, channels).use { writer ->
                        while (running.get()) {
                            val read = recorder.read(
                                buffer,
                                0,
                                buffer.size,
                                AudioRecord.READ_BLOCKING,
                            )
                            when {
                                read > 0 -> writer.writePcm16Bytes(buffer, read)
                                read == AudioRecord.ERROR_DEAD_OBJECT -> error("端末音声との接続が切れました")
                                read < 0 && running.get() -> error("端末音声の読み取りエラー: $read")
                            }
                        }
                    }
                } catch (throwable: Throwable) {
                    errorMessage = throwable.message ?: "端末音声録音中にエラーが発生しました"
                } finally {
                    running.set(false)
                    runCatching { recorder.stop() }
                    runCatching { recorder.release() }
                    audioRecord = null
                    releaseProjection()

                    val failure = errorMessage
                    if (failure != null) {
                        outputFile.delete()
                        CaptureEventBus.publish(PlaybackCaptureState.Error(failure))
                    } else if (outputFile.exists() && outputFile.length() > 44L) {
                        CaptureEventBus.publish(PlaybackCaptureState.Completed(outputFile))
                    } else {
                        outputFile.delete()
                        CaptureEventBus.publish(PlaybackCaptureState.Error("録音可能な端末音声がありませんでした"))
                    }

                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }, "ChopLab-PlaybackCapture").apply { start() }
        } catch (throwable: Throwable) {
            failAndStop(throwable.message ?: "端末音声録音を開始できません")
        }
    }

    @SuppressLint("MissingPermission")
    private fun buildAudioRecord(
        configuration: AudioPlaybackCaptureConfiguration,
    ): Pair<AudioRecord, Int> {
        fun build(channelMask: Int): AudioRecord {
            val minimum = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                channelMask,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            check(minimum > 0) { "端末音声用バッファを作成できません" }
            return AudioRecord.Builder()
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(channelMask)
                        .build(),
                )
                .setAudioPlaybackCaptureConfig(configuration)
                .setBufferSizeInBytes(max(minimum * 2, 16_384))
                .build()
        }

        val stereo = runCatching { build(AudioFormat.CHANNEL_IN_STEREO) }.getOrNull()
        if (stereo != null && stereo.state == AudioRecord.STATE_INITIALIZED) return stereo to 2
        runCatching { stereo?.release() }

        val mono = build(AudioFormat.CHANNEL_IN_MONO)
        check(mono.state == AudioRecord.STATE_INITIALIZED) { "端末音声レコーダーを初期化できません" }
        return mono to 1
    }

    private fun requestStop() {
        if (!running.getAndSet(false)) {
            if (worker == null) {
                releaseProjection()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            return
        }
        runCatching { audioRecord?.stop() }
    }

    private fun failAndStop(message: String) {
        running.set(false)
        runCatching { audioRecord?.release() }
        audioRecord = null
        releaseProjection()
        CaptureEventBus.publish(PlaybackCaptureState.Error(message))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseProjection() {
        val projection = mediaProjection
        val callback = projectionCallback
        if (projection != null && callback != null) {
            runCatching { projection.unregisterCallback(callback) }
        }
        projectionCallback = null
        mediaProjection = null
        runCatching { projection?.stop() }
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, PlaybackCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return Notification.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_waveform)
            .setContentTitle(getString(R.string.capture_notification_title))
            .setContentText(getString(R.string.capture_notification_text))
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    R.drawable.ic_stat_waveform,
                    getString(R.string.capture_stop),
                    stopIntent,
                ).build(),
            )
            .build()
    }

    private fun createNotificationChannel() {
        val manager = requireNotNull(getSystemService(NotificationManager::class.java)) {
            "NotificationManagerを取得できません"
        }
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL,
            "端末音声録音",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "端末内で再生される録音可能な音声を取り込む間に表示されます"
        }
        manager.createNotificationChannel(channel)
    }

    @Suppress("DEPRECATION")
    private fun Intent.intentExtra(name: String): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(name, Intent::class.java)
        } else {
            getParcelableExtra(name)
        }

    companion object {
        const val ACTION_START = "com.choplab.sampler.action.START_PLAYBACK_CAPTURE"
        const val ACTION_STOP = "com.choplab.sampler.action.STOP_PLAYBACK_CAPTURE"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        private const val SAMPLE_RATE = 48_000
        private const val NOTIFICATION_CHANNEL = "playback_capture"
        private const val NOTIFICATION_ID = 42

        fun startIntent(context: Context, resultCode: Int, data: Intent): Intent =
            Intent(context, PlaybackCaptureService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)

        fun stopIntent(context: Context): Intent =
            Intent(context, PlaybackCaptureService::class.java).setAction(ACTION_STOP)
    }
}
