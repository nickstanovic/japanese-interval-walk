package com.personal.japaneseintervalwalk

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.CombinedVibration
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import kotlin.math.max

class IntervalService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val hapticAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
    private val speechAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private var wakeLock: PowerManager.WakeLock? = null
    private var tts: TextToSpeech? = null
    private val pendingSpeech = ArrayDeque<SpeechRequest>()
    private val speechCompletionCallbacks = mutableMapOf<String, () -> Unit>()
    private var ttsReady = false
    private var speechUnavailable = false
    private var speechSequence = 0
    private var running = false
    private var paused = false
    private var slow = true
    private var stopping = false
    private var completedIntervals = 0
    private var intervalEndElapsed = 0L
    private var remainingMs = INTERVAL_MS

    private data class SpeechRequest(
        val text: String,
        val onComplete: (() -> Unit)?,
    )

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!running || paused) return

            val left = currentRemainingMs()
            if (left <= 0L) {
                completedIntervals++
                slow = !slow
                remainingMs = INTERVAL_MS
                intervalEndElapsed = SystemClock.elapsedRealtime() + INTERVAL_MS
                playPaceCue(slow)
                speakLine(if (slow) SLOW_LINE else FAST_LINE)
            } else {
                remainingMs = left
            }

            broadcastStatus()
            updateNotification()
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        initializeSpeech()
        wakeLock = (getSystemService(POWER_SERVICE) as? PowerManager)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "JapaneseIntervalWalk:timer")
            ?.apply { setReferenceCounted(false) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: ACTION_REQUEST_STATUS) {
            ACTION_START -> startNewSession()
            ACTION_PAUSE -> pauseSession()
            ACTION_RESUME -> resumeSession()
            ACTION_STOP -> stopSession()
            else -> {
                broadcastStatus()
                if (!running) stopSelf(startId)
            }
        }
        return if (running) START_STICKY else START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacks(tickRunnable)
        cancelVibration()
        releaseWakeLock()
        shutdownSpeech()
        super.onDestroy()
    }

    private fun startNewSession() {
        running = true
        paused = false
        slow = true
        stopping = false
        completedIntervals = 0
        remainingMs = INTERVAL_MS
        intervalEndElapsed = SystemClock.elapsedRealtime() + INTERVAL_MS

        promoteToForeground()
        acquireWakeLock()
        playPaceCue(slowCue = true)
        speakLine(START_LINE)
        handler.removeCallbacks(tickRunnable)
        handler.post(tickRunnable)
        broadcastStatus()
    }

    private fun pauseSession() {
        if (!running || paused) {
            broadcastStatus()
            return
        }

        remainingMs = currentRemainingMs()
        paused = true
        handler.removeCallbacks(tickRunnable)
        releaseWakeLock()
        speakLine(PAUSE_LINE)
        broadcastStatus()
        updateNotification()
    }

    private fun resumeSession() {
        if (!running || !paused) {
            broadcastStatus()
            return
        }

        paused = false
        intervalEndElapsed = SystemClock.elapsedRealtime() + remainingMs
        acquireWakeLock()
        handler.removeCallbacks(tickRunnable)
        handler.post(tickRunnable)
        broadcastStatus()
        updateNotification()
    }

    private fun stopSession() {
        if (!running) {
            finishStopSession()
            return
        }
        if (stopping) return

        val summaryLine = buildStopSummary()
        remainingMs = currentRemainingMs()
        paused = true
        stopping = true
        handler.removeCallbacks(tickRunnable)
        cancelVibration()
        releaseWakeLock()
        broadcastStatus()
        updateNotification()
        speakLine(summaryLine, onComplete = { finishStopSession() })
        handler.postDelayed({
            if (stopping) finishStopSession()
        }, STOP_SUMMARY_FALLBACK_MS)
    }

    private fun finishStopSession() {
        running = false
        paused = false
        slow = true
        stopping = false
        completedIntervals = 0
        remainingMs = INTERVAL_MS
        handler.removeCallbacks(tickRunnable)
        cancelVibration()
        releaseWakeLock()
        broadcastStatus()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun promoteToForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification() {
        if (!running) return
        (getSystemService(NOTIFICATION_SERVICE) as? NotificationManager)
            ?.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val pace = if (slow) "Slow walk" else "Fast walk"
        val title = when {
            stopping -> "Wrapping up"
            paused -> "Paused on ${pace.lowercase(Locale.US)}"
            else -> pace
        }
        val text = if (stopping) {
            "Playing your workout summary"
        } else {
            "${currentRemainingMs().formatDuration()} remaining"
        }

        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            10,
            openIntent,
            pendingIntentFlags(),
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_walk)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openPendingIntent)
            .setOngoing(running)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .apply {
                if (stopping) {
                    return@apply
                }
                if (paused) {
                    addAction(
                        android.R.drawable.ic_media_play,
                        "Resume",
                        servicePendingIntent(ACTION_RESUME, 20),
                    )
                } else {
                    addAction(
                        android.R.drawable.ic_media_pause,
                        "Pause",
                        servicePendingIntent(ACTION_PAUSE, 21),
                    )
                }
                addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "Stop",
                    servicePendingIntent(ACTION_STOP, 22),
                )
            }
            .build()
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, IntervalService::class.java).setAction(action)
        return PendingIntent.getService(this, requestCode, intent, pendingIntentFlags())
    }

    private fun pendingIntentFlags(): Int =
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Walking timer",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows the active interval walking timer."
            enableVibration(false)
            setSound(null, null)
        }

        (getSystemService(NOTIFICATION_SERVICE) as? NotificationManager)
            ?.createNotificationChannel(channel)
    }

    private fun initializeSpeech() {
        tts = TextToSpeech(applicationContext) { status ->
            handler.post {
                if (status == TextToSpeech.SUCCESS) {
                    speechUnavailable = false
                    configureSpeechEngine()
                    ttsReady = true
                    drainPendingSpeech()
                } else {
                    speechUnavailable = true
                    finishPendingSpeechWithoutAudio()
                }
            }
        }
    }

    private fun configureSpeechEngine() {
        tts?.apply {
            setLanguage(Locale.US)
            voices
                ?.filter { voice ->
                    voice.locale.language == Locale.ENGLISH.language &&
                        !voice.isNetworkConnectionRequired
                }
                ?.maxByOrNull { voice ->
                    val localeBonus = if (voice.locale == Locale.US) 1_000 else 0
                    val nameBonus = if (voice.name.contains("female", ignoreCase = true)) 50 else 0
                    localeBonus + nameBonus + (voice.quality * 10) - voice.latency
                }
                ?.let { voice = it }
            setPitch(1.35f)
            setSpeechRate(1.03f)
            setAudioAttributes(speechAttributes)
            setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    completeSpeech(utteranceId)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    completeSpeech(utteranceId)
                }
            })
        }
    }

    private fun speakLine(text: String, onComplete: (() -> Unit)? = null) {
        if (speechUnavailable) {
            onComplete?.invoke()
            return
        }

        val engine = tts
        if (!ttsReady || engine == null) {
            pendingSpeech.clear()
            pendingSpeech.addLast(SpeechRequest(text, onComplete))
            return
        }

        val utteranceId = "walk_voice_${speechSequence++}"
        if (onComplete != null) {
            speechCompletionCallbacks[utteranceId] = onComplete
        }

        val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        if (result == TextToSpeech.ERROR) {
            completeSpeech(utteranceId)
        }
    }

    private fun drainPendingSpeech() {
        while (pendingSpeech.isNotEmpty()) {
            val request = pendingSpeech.removeFirst()
            speakLine(request.text, request.onComplete)
        }
    }

    private fun finishPendingSpeechWithoutAudio() {
        while (pendingSpeech.isNotEmpty()) {
            pendingSpeech.removeFirst().onComplete?.invoke()
        }
    }

    private fun completeSpeech(utteranceId: String?) {
        if (utteranceId == null) return
        handler.post {
            speechCompletionCallbacks.remove(utteranceId)?.invoke()
        }
    }

    private fun shutdownSpeech() {
        pendingSpeech.clear()
        speechCompletionCallbacks.clear()
        ttsReady = false
        speechUnavailable = false
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    private fun playPaceCue(slowCue: Boolean) {
        val effect = if (slowCue) {
            VibrationEffect.createOneShot(SLOW_CUE_MS, VibrationEffect.DEFAULT_AMPLITUDE)
        } else {
            VibrationEffect.createWaveform(
                longArrayOf(0L, FAST_CUE_PULSE_MS, FAST_CUE_GAP_MS, FAST_CUE_PULSE_MS),
                intArrayOf(0, 255, 0, 255),
                -1,
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val attributes = VibrationAttributes.Builder()
                .setUsage(VibrationAttributes.USAGE_ALARM)
                .build()
            getSystemService(VibratorManager::class.java)
                ?.vibrate(CombinedVibration.createParallel(effect), attributes)
        } else {
            @Suppress("DEPRECATION")
            (getSystemService(VIBRATOR_SERVICE) as? Vibrator)
                ?.takeIf { it.hasVibrator() }
                ?.vibrate(effect, hapticAttributes)
        }
    }

    private fun cancelVibration() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.cancel()
        } else {
            @Suppress("DEPRECATION")
            (getSystemService(VIBRATOR_SERVICE) as? Vibrator)?.cancel()
        }
    }

    private fun acquireWakeLock() {
        wakeLock?.takeIf { !it.isHeld }?.acquire(WAKE_LOCK_TIMEOUT_MS)
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
    }

    private fun currentRemainingMs(): Long = when {
        !running -> INTERVAL_MS
        paused -> max(0L, remainingMs)
        else -> max(0L, intervalEndElapsed - SystemClock.elapsedRealtime())
    }

    private fun elapsedMs(): Long {
        if (!running) return 0L
        return completedIntervals * INTERVAL_MS + (INTERVAL_MS - currentRemainingMs())
    }

    private fun buildStopSummary(): String {
        val duration = formatDurationForSpeech(elapsedMs())
        val intervalText = when (completedIntervals) {
            0 -> "You got moving and started your interval rhythm."
            1 -> "You finished 1 full interval."
            else -> "You finished $completedIntervals full intervals."
        }
        return "Workout complete! You walked for $duration. $intervalText Great job today. I'm proud of you!"
    }

    private fun broadcastStatus() {
        val status = Intent(ACTION_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_RUNNING, running)
            putExtra(EXTRA_PAUSED, paused)
            putExtra(EXTRA_SLOW, slow)
            putExtra(EXTRA_STOPPING, stopping)
            putExtra(EXTRA_REMAINING_MS, currentRemainingMs())
            putExtra(EXTRA_INTERVAL_MS, INTERVAL_MS)
            putExtra(EXTRA_COMPLETED_INTERVALS, completedIntervals)
            putExtra(EXTRA_ELAPSED_MS, elapsedMs())
        }
        sendBroadcast(status)
    }

    private fun Long.formatDuration(): String {
        val totalSeconds = max(0L, (this + 999L) / 1000L)
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return String.format(Locale.US, "%d:%02d", minutes, seconds)
    }

    private fun formatDurationForSpeech(durationMs: Long): String {
        val totalSeconds = max(0L, (durationMs + 999L) / 1000L)
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return when {
            minutes == 0L && seconds <= 1L -> "about 1 second"
            minutes == 0L -> "$seconds seconds"
            seconds == 0L -> "$minutes ${if (minutes == 1L) "minute" else "minutes"}"
            else -> "$minutes ${if (minutes == 1L) "minute" else "minutes"} and $seconds seconds"
        }
    }

    companion object {
        const val INTERVAL_MS = 3L * 60L * 1000L

        const val ACTION_START = "com.personal.japaneseintervalwalk.START"
        const val ACTION_PAUSE = "com.personal.japaneseintervalwalk.PAUSE"
        const val ACTION_RESUME = "com.personal.japaneseintervalwalk.RESUME"
        const val ACTION_STOP = "com.personal.japaneseintervalwalk.STOP"
        const val ACTION_REQUEST_STATUS = "com.personal.japaneseintervalwalk.REQUEST_STATUS"
        const val ACTION_STATUS = "com.personal.japaneseintervalwalk.STATUS"

        const val EXTRA_RUNNING = "running"
        const val EXTRA_PAUSED = "paused"
        const val EXTRA_SLOW = "slow"
        const val EXTRA_STOPPING = "stopping"
        const val EXTRA_REMAINING_MS = "remaining_ms"
        const val EXTRA_INTERVAL_MS = "interval_ms"
        const val EXTRA_COMPLETED_INTERVALS = "completed_intervals"
        const val EXTRA_ELAPSED_MS = "elapsed_ms"

        private const val START_LINE =
            "Okay, let's go! Start with a sweet slow walk. Tiny steps, big energy!"
        private const val SLOW_LINE =
            "Nice work! Slow walk now. Breathe easy and let your legs feel happy."
        private const val FAST_LINE =
            "Time to sparkle! Fast walk now. Quick little steps, you've totally got this!"
        private const val PAUSE_LINE =
            "Nice job, let's take a break. I'll keep your interval safe right here."

        private const val CHANNEL_ID = "walking_timer"
        private const val NOTIFICATION_ID = 1003
        private const val TICK_MS = 500L
        private const val WAKE_LOCK_TIMEOUT_MS = INTERVAL_MS + 30_000L
        private const val SLOW_CUE_MS = 1_200L
        private const val FAST_CUE_PULSE_MS = 420L
        private const val FAST_CUE_GAP_MS = 260L
        private const val STOP_SUMMARY_FALLBACK_MS = 12_000L
    }
}
