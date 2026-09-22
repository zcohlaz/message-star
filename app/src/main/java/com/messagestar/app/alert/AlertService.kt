package com.messagestar.app.alert

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import android.provider.Settings
import android.telephony.TelephonyManager
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.LifecycleService
import com.messagestar.app.data.AlertStateStore
import com.messagestar.app.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class AlertService : LifecycleService() {
    companion object {
        private const val TAG = "MessageStarAlert"
        private const val NOTIFICATION_ID = 77
        private const val MAX_ALERT_DURATION_MS = 5 * 60 * 1000L
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var player: MediaPlayer? = null
    private var fallbackRingtone: Ringtone? = null
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private var vibrator: android.os.Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var overlayView: View? = null
    private var overlayMessage: TextView? = null
    private var timeoutJob: Job? = null
    private var savedAlarmVolume: Int? = null
    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                player?.runCatching { if (!isPlaying) start() }
                fallbackRingtone?.runCatching { if (!isPlaying) play() }
            }
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                player?.runCatching { if (isPlaying) pause() }
                fallbackRingtone?.runCatching { stop() }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == AlertNotification.ACTION_STOP) {
            AlertCoordinator.stop(this, cooldown = true)
            return START_NOT_STICKY
        }

        val canUseOverlay = Settings.canDrawOverlays(this)
        startForeground(
            NOTIFICATION_ID,
            AlertNotification.build(this, useFullScreenIntent = !canUseOverlay)
        )
        acquireWakeLock()
        scheduleAutomaticStop()

        if (canUseOverlay) showOrRefreshOverlay()
        if (player == null && fallbackRingtone?.isPlaying != true) {
            serviceScope.launch { startPlayback() }
        }
        return START_NOT_STICKY
    }

    private suspend fun startPlayback() {
        runCatching {
            val repository = SettingsRepository(applicationContext)
            if (!isInCall()) startAudio(repository.currentRingtoneUri())
            if (repository.currentVibrationEnabled()) startVibration()
        }.onFailure { Log.e(TAG, "Unable to start alert media", it) }
    }

    private fun startAudio(ringtone: String?) {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        audioManager = getSystemService(AudioManager::class.java)
        raiseAlarmVolumeTemporarily()
        requestAudioFocus(attributes)

        val candidates = buildList {
            ringtone?.let(::parseUri)?.let(::add)
            add(RingtoneManager.getActualDefaultRingtoneUri(this@AlertService, RingtoneManager.TYPE_ALARM))
            add(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
            add(RingtoneManager.getActualDefaultRingtoneUri(this@AlertService, RingtoneManager.TYPE_RINGTONE))
            add(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
            add(RingtoneManager.getActualDefaultRingtoneUri(this@AlertService, RingtoneManager.TYPE_NOTIFICATION))
            add(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
        }.filterNotNull().distinct()

        for (uri in candidates) {
            val candidate = MediaPlayer()
            val started = runCatching {
                candidate.setAudioAttributes(attributes)
                candidate.setVolume(1f, 1f)
                candidate.isLooping = true
                candidate.setDataSource(this@AlertService, uri)
                candidate.prepare()
                candidate.start()
                candidate.isPlaying
            }.onFailure { Log.w(TAG, "MediaPlayer rejected alert URI: $uri", it) }
                .getOrDefault(false)
            if (started) {
                player = candidate
                Log.i(TAG, "Alert ringtone started with MediaPlayer")
                return
            }
            runCatching { candidate.release() }
        }

        startRingtoneFallback(candidates)
    }

    private fun requestAudioFocus(attributes: AudioAttributes) {
        val manager = audioManager ?: return
        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(audioFocusChangeListener)
            .setWillPauseWhenDucked(true)
            .build()
        manager.requestAudioFocus(focusRequest!!)
    }

    private fun raiseAlarmVolumeTemporarily() {
        val manager = audioManager ?: return
        runCatching {
            if (savedAlarmVolume == null) {
                savedAlarmVolume = manager.getStreamVolume(AudioManager.STREAM_ALARM)
            }
            val maximum = manager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            manager.setStreamVolume(AudioManager.STREAM_ALARM, maximum, 0)
        }.onFailure { Log.w(TAG, "Unable to raise alarm volume", it) }
    }

    private fun startRingtoneFallback(candidates: List<Uri>) {
        val uri = candidates.firstOrNull() ?: return
        runCatching {
            fallbackRingtone = RingtoneManager.getRingtone(applicationContext, uri)?.apply {
                audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    isLooping = true
                    volume = 1f
                }
                play()
            }
            if (fallbackRingtone == null) Log.e(TAG, "No playable system ringtone")
        }.onFailure { Log.e(TAG, "Ringtone fallback failed", it) }
    }

    private fun parseUri(value: String): Uri? = runCatching {
        Uri.parse(value).takeIf { it.scheme != null }
    }.getOrNull()

    private fun startVibration() {
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
        }
        if (vibrator?.hasVibrator() != true) return

        val timings = longArrayOf(0, 1000, 500)
        val effect = VibrationEffect.createWaveform(timings, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val attributes = VibrationAttributes.Builder()
                .setUsage(VibrationAttributes.USAGE_ALARM)
                .build()
            vibrator?.vibrate(effect, attributes)
        } else {
            vibrator?.vibrate(effect)
        }
    }

    @Suppress("DEPRECATION")
    private fun acquireWakeLock() {
        runCatching {
            wakeLock?.let { if (it.isHeld) it.release() }
            val powerManager = getSystemService(PowerManager::class.java)
            wakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
                "MessageStar::AlertWakeLock"
            ).apply {
                setReferenceCounted(false)
                acquire(MAX_ALERT_DURATION_MS + 10_000L)
            }
        }.onFailure { Log.w(TAG, "Unable to acquire wake lock", it) }
    }

    private fun scheduleAutomaticStop() {
        timeoutJob?.cancel()
        timeoutJob = serviceScope.launch {
            delay(MAX_ALERT_DURATION_MS)
            Log.i(TAG, "Alert stopped after five-minute safety timeout")
            AlertCoordinator.stop(this@AlertService, cooldown = true)
        }
    }

    private fun showOrRefreshOverlay() {
        val state = AlertStateStore.snapshot(this)
        overlayMessage?.text = buildOverlayMessage(state.count, state.latestSender)
        if (overlayView != null) return

        val windowManager = getSystemService(WindowManager::class.java)
        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        val overlay = FrameLayout(this).apply {
            setBackgroundColor(0xB3000000.toInt())
            isClickable = true
            isFocusable = true
            setOnClickListener { AlertCoordinator.stop(this@AlertService, cooldown = true) }
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28), dp(24), dp(28), dp(24))
            background = GradientDrawable().apply {
                setColor(Color.rgb(91, 33, 182))
                cornerRadius = dp(20).toFloat()
            }
            isClickable = true
            setOnClickListener { }
        }
        card.addView(TextView(this).apply {
            text = "重要短信"
            textSize = 28f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        })
        overlayMessage = TextView(this).apply {
            text = buildOverlayMessage(state.count, state.latestSender)
            textSize = 17f
            setTextColor(0xFFEDE9FE.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, dp(20))
        }
        card.addView(
            overlayMessage,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        card.addView(Button(this).apply {
            text = "关闭提醒"
            textSize = 18f
            minHeight = dp(54)
            setOnClickListener { AlertCoordinator.stop(this@AlertService, cooldown = true) }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        overlay.addView(card, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER
            leftMargin = dp(18)
            rightMargin = dp(18)
        })

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        runCatching {
            windowManager.addView(overlay, params)
            overlayView = overlay
        }.onFailure { Log.e(TAG, "Unable to show background alert overlay", it) }
    }

    private fun buildOverlayMessage(count: Int, sender: String): String = buildString {
        append("收到 ${count.coerceAtLeast(1)} 条重要短信")
        if (sender.isNotBlank()) append("\n发送号码：$sender")
        append("\n点击按钮或背景可关闭")
    }

    private fun removeOverlay() {
        val view = overlayView ?: return
        runCatching { getSystemService(WindowManager::class.java).removeView(view) }
            .onFailure { Log.w(TAG, "Unable to remove alert overlay", it) }
        overlayView = null
        overlayMessage = null
    }

    private fun isInCall(): Boolean = runCatching {
        val state = getSystemService(TelephonyManager::class.java).callState
        state == TelephonyManager.CALL_STATE_OFFHOOK || state == TelephonyManager.CALL_STATE_RINGING
    }.getOrDefault(false)

    private fun stopPlayback() {
        timeoutJob?.cancel()
        timeoutJob = null
        player?.runCatching { if (isPlaying) stop() }
        player?.release()
        player = null
        fallbackRingtone?.runCatching { stop() }
        fallbackRingtone = null
        vibrator?.cancel()
        vibrator = null

        val manager = audioManager
        focusRequest?.let { request -> manager?.abandonAudioFocusRequest(request) }
        focusRequest = null

        savedAlarmVolume?.let { original ->
            runCatching { manager?.setStreamVolume(AudioManager.STREAM_ALARM, original, 0) }
                .onFailure { Log.w(TAG, "Unable to restore alarm volume", it) }
        }
        savedAlarmVolume = null
        audioManager = null
        wakeLock?.let { lock -> runCatching { if (lock.isHeld) lock.release() } }
        wakeLock = null
    }

    override fun onDestroy() {
        removeOverlay()
        stopPlayback()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)
}
