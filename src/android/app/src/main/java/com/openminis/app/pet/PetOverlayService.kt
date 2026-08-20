package com.openminis.app.pet

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.openminis.app.MinisApp
import com.openminis.app.R
import com.openminis.app.service.SessionActivityTracker
import com.openminis.app.speech.RecognitionError
import com.openminis.app.speech.RecognitionState
import com.openminis.app.speech.SpeechRecognitionManager
import com.openminis.app.speech.SystemSpeechRecognitionEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.random.Random

class PetOverlayService : Service() {
    companion object {
        const val ACTION_START = "dev.openminispet.action.START"
        const val ACTION_SET_STATE = "dev.openminispet.action.SET_STATE"
        const val ACTION_RELOAD = "dev.openminispet.action.RELOAD"
        const val ACTION_STOP = "dev.openminispet.action.STOP"
        const val EXTRA_STATE = "pet_state"
        private const val CHANNEL_ID = "pet_overlay"
        private const val NOTIFICATION_ID = 9417
        private const val TAG = "PetOverlayService"

        // REVIEW / WAITING are semantic Agent states, never personality flourishes.
        private val TAP_STATES = listOf(PetState.WAVING, PetState.JUMPING)
    }

    private lateinit var windowManager: WindowManager
    private var petView: PetOverlayView? = null
    private var params: WindowManager.LayoutParams? = null
    private var chatWindow: PetChatWindowView? = null
    private var chatParams: WindowManager.LayoutParams? = null
    private val main = Handler(Looper.getMainLooper())
    private var transientReset: Runnable? = null
    private var baseState: PetState = PetState.IDLE
    private var behavior: PetBehavior? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val chatEngine by lazy { PetChatEngine(this) }
    // One player for the service's lifetime. A fresh ReadAloudPlayer per
    // reply would start TTS initialisation from scratch each time (so the
    // first sentence is usually lost) and leak the engine it never shuts
    // down. Its internal queue buffers text until the engine is ready.
    private val readAloudPlayer by lazy { com.openminis.app.speech.ReadAloudPlayer(this) }
    private var askJob: Job? = null
    private var inputMode = false
    /** Retained across an async sprite decode so a service started on a dark screen
     * never starts animating/timing only because it missed the screen-off broadcast. */
    private var screenAwake = true
    /** True while a voice-capture start is still resolving; a second tap clears it to cancel. */
    @Volatile private var voiceStarting = false
    /** Non-null while the spritesheet decode/attach is in flight. */
    private var petLoadJob: Job? = null
    /** Invalidates a stale async decode when reload/hide wins the race. */
    private var mountGeneration = 0L

    private val randomMood = object : Runnable {
        override fun run() {
            val view = petView ?: return
            // Random personality only decorates genuine idle. Agent-driven
            // WAITING/RUNNING/FAILED states must not be overwritten by mood RNG,
            // and a tucked or busy-chatting pet should stay still.
            if (baseState == PetState.IDLE &&
                view.sprite.currentState() == PetState.IDLE &&
                behavior?.isTucked != true &&
                !inputMode &&
                askJob?.isActive != true
            ) {
                val next = listOf(
                    PetState.IDLE,
                    PetState.WAVING,
                    PetState.JUMPING,
                ).random()
                setTransientState(next, if (next == PetState.JUMPING) 850L else 1200L)
            }
            main.postDelayed(this, Random.nextLong(4_000L, 9_000L))
        }
    }

    /**
     * ACTION_SCREEN_ON/OFF can only be registered at runtime — a manifest
     * receiver never gets them. Without this the pet keeps animating, wandering
     * and firing mood actions against a screen nobody is looking at.
     */
    private val screenReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> setAwake(false)
                Intent.ACTION_SCREEN_ON -> setAwake(true)
            }
        }
    }

    private fun setAwake(awake: Boolean) {
        screenAwake = awake
        if (awake) {
            petView?.sprite?.resumeAnimation()
            behavior?.resumeTimers()
            main.removeCallbacks(randomMood)
            main.postDelayed(randomMood, Random.nextLong(4_000L, 9_000L))
        } else {
            petView?.sprite?.pauseAnimation()
            behavior?.pauseTimers()
            main.removeCallbacks(randomMood)
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        screenAwake = getSystemService(PowerManager::class.java)?.isInteractive ?: true
        createNotificationChannel()
        startAsForeground()
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            android.content.IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                PetPreferences.setEnabled(this, false)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RELOAD -> {
                hidePet()
                if (!attachPetOrStop(startId)) return START_NOT_STICKY
            }
            ACTION_SET_STATE -> {
                transientReset?.let(main::removeCallbacks)
                transientReset = null
                val name = intent.getStringExtra(EXTRA_STATE)
                val state = runCatching { PetState.valueOf(name.orEmpty()) }.getOrDefault(PetState.IDLE)
                val previous = baseState
                baseState = state
                if (!attachPetOrStop(startId)) return START_NOT_STICKY
                applyAgentState(previous, state)
            }
            else -> if (!attachPetOrStop(startId)) return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenReceiver) }
        main.removeCallbacks(randomMood)
        transientReset?.let(main::removeCallbacks)
        askJob?.cancel()
        serviceScope.coroutineContext[Job]?.cancel()
        stopVoiceCapture()
        runCatching { readAloudPlayer.shutdown() }
        behavior?.stop()
        hidePet()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- agent

    /**
     * Reflect an agent status change in both the animation and the bubble.
     *
     * A busy agent also wakes a tucked pet: the status is the whole point of
     * the overlay, so it must not stay hidden while work is happening. The
     * bubble is left alone while the user is chatting — their own reply
     * outranks a status ping.
     */
    private fun applyAgentState(previous: PetState, state: PetState) {
        val view = petView
        if (state != PetState.IDLE) behavior?.wakeIfTucked()

        if (
            state == PetState.IDLE &&
            (previous == PetState.RUNNING || previous == PetState.WAITING || previous == PetState.REVIEW)
        ) {
            setTransientState(PetState.WAVING, 900L)
            speak("搞定啦！")
        } else {
            view?.sprite?.setState(state)
            when (state) {
                PetState.RUNNING -> speak("正在忙…")
                PetState.WAITING -> speak("等你一下")
                PetState.REVIEW -> speak("我看看…")
                PetState.FAILED -> speak("出错了 :(")
                else -> Unit
            }
        }
    }

    /**
     * The pet service can be restored after the Agent foreground service has
     * already begun a run. Reconcile its first frame from the same tracker the
     * bridge observes instead of waiting for a later status-string emission.
     */
    private fun restoreAgentVisualState() {
        val state = PetAgentStateResolver.resolve(
            sessionCount = SessionActivityTracker.activeSessions.value.size,
            toolStatus = SessionActivityTracker.currentToolStatus.value,
        )
        if (state == baseState) return
        val previous = baseState
        baseState = state
        applyAgentState(previous, state)
    }

    private fun speak(message: String, durationMs: Long = 2_600L) {
        if (!PetPreferences.bubbleEnabled(this)) return
        // Never talk over the user's own conversation.
        if (askJob?.isActive == true || inputMode) return
        petView?.showBubble(message, durationMs)
    }

    // ----------------------------------------------------------------- chat

    /** Send a question to the model and show the (deliberately short) reply. */
    private fun ask(question: String, fromVoice: Boolean = false) {
        val view = petView ?: return
        askJob?.cancel()
        behavior?.onInteraction()

        chatWindow?.addUserMessage(question)
        chatWindow?.setInputText("")
        chatWindow?.setBusy(true)
        view.sprite.setState(PetState.REVIEW)
        if (chatWindow == null) view.showBubble("想想…", 0L)

        askJob = serviceScope.launch {
            val result = withContext(Dispatchers.IO) { chatEngine.ask(question) }
            val v = petView ?: return@launch
            chatWindow?.setBusy(false)
            result.onSuccess { reply ->
                val window = chatWindow
                if (window != null) {
                    window.addAssistantMessage(reply)
                } else if (PetPreferences.bubbleEnabled(this@PetOverlayService)) {
                    v.showBubble(reply, 9_000L)
                }
                v.sprite.setState(baseState)
                // Do not show a success reaction over an unrelated real Agent
                // task. The direct pet chat is lightweight; the agent status
                // remains the authoritative visual when it is busy.
                if (baseState == PetState.IDLE) setTransientState(PetState.WAVING, 900L)
                // A spoken question is a voice-conversation turn. Reuse the
                // App's Voice Output provider/API selection, but do not require
                // a second pet-only "read replies" toggle.
                if (fromVoice) readAloudConversation(reply)
            }.onFailure { error ->
                val message = error.message ?: "出错了"
                chatWindow?.setStatus(message)
                if (chatWindow == null) v.showBubble(message, 5_000L)
                setTransientState(PetState.FAILED, 1_400L)
            }
        }
    }

    private fun readAloudConversation(text: String) {
        // Voice conversation is best-effort: the provider/system voice is
        // resolved by ReadAloudPlayer from the same app-wide Voice Output group.
        runCatching { readAloudPlayer.speakConversation(text) }
            .onFailure { android.util.Log.w(TAG, "voice conversation output failed: ${it.message}") }
    }

    // ---------------------------------------------------------------- voice

    private fun toggleVoice() {
        if (SpeechRecognitionManager.state.value != RecognitionState.IDLE) {
            stopVoiceCapture()
            return
        }
        val window = chatWindow ?: return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            window.setStatus("需要麦克风权限，请在 App 里授予")
            return
        }

        // A second tap while the async start is still resolving counts as cancel.
        if (voiceStarting) {
            voiceStarting = false
            return
        }
        voiceStarting = true

        // Resolve the engine/model on EVERY capture from the app's canonical
        // provider configuration. The pet owns no ASR API key or model setting.
        serviceScope.launch {
            runCatching {
                val app = applicationContext as? MinisApp
                val repository = app?.providerRepositoryOrNull
                if (repository == null) {
                    voiceStarting = false
                    window.setVoiceActive(false)
                    window.setStatus("App 模型配置还没有初始化")
                    return@launch
                }
                if (withTimeoutOrNull(8_000L) { repository.awaitConfigLoaded(); true } != true) {
                    voiceStarting = false
                    window.setVoiceActive(false)
                    window.setStatus("语音模型配置加载超时")
                    return@launch
                }
                repository.ensureDefaultVoiceInputGroup()
                repository.ensureDefaultVoiceOutputGroup()
                val choice = repository.resolveVoiceInputChoice()
                SpeechRecognitionManager.selectEngine(if (choice.isSystem) "system" else "provider")
                (SpeechRecognitionManager.availableEngines()
                    .firstOrNull { it.id == "system" } as? SystemSpeechRecognitionEngine)
                    ?.preferOffline = (choice.systemPreferOffline == true)
                SpeechRecognitionManager.refreshSupportedLocales()

                // Re-check right before recording: a cancel tap cleared the flag.
                if (!voiceStarting) return@launch
                window.setVoiceActive(true)
                SpeechRecognitionManager.startRecording(
                    onPartialOrFinal = { text, isFinal ->
                        main.post {
                            val active = chatWindow ?: return@post
                            active.setInputText(text)
                            if (isFinal) {
                                active.setVoiceActive(false)
                                if (text.isNotBlank()) ask(text.trim(), fromVoice = true)
                            }
                        }
                    },
                    onError = { error, message ->
                        main.post {
                            chatWindow?.setVoiceActive(false)
                            chatWindow?.setStatus(voiceErrorText(error, message))
                            android.util.Log.w(TAG, "speech error=${error.name} msg=$message")
                        }
                    },
                )
                voiceStarting = false
            }.onFailure { error ->
                voiceStarting = false
                android.util.Log.w(TAG, "voice toggle failed: ${error.message}")
                chatWindow?.setVoiceActive(false)
                chatWindow?.setStatus(error.message ?: "语音启动失败")
            }
        }
    }

    /**
     * Keep FLAG_WATCH_OUTSIDE_TOUCH on exactly while the sprite's long-press
     * menu is open. Chat is a separate focusable window and closes by focus
     * loss/Back instead, so the sprite never needs to steal global taps.
     */
    private fun refreshOutsideWatch() {
        val lp = params ?: return
        val view = petView ?: return
        val want = view.isMenuVisible()
        val base = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        val next = if (want) base or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH else base
        if (lp.flags == next) return
        lp.flags = next
        runCatching { windowManager.updateViewLayout(view, lp) }
    }

    private fun voiceErrorText(
        error: com.openminis.app.speech.RecognitionError,
        message: String?,
    ): String = when (error) {
        RecognitionError.OEM_NO_SERVICE ->
            "当前 Voice Input 不可用；请在 App 的语音模型设置中选择系统识别或云端 ASR"
        RecognitionError.PERMISSION_DENIED -> "需要麦克风权限，请在 App 里授予"
        RecognitionError.NETWORK -> "网络不通，语音识别失败"
        RecognitionError.NO_MATCH -> "没听清，再说一次"
        RecognitionError.RECOGNIZER_BUSY -> "识别引擎忙，稍等一下"
        RecognitionError.TRANSCRIPTION_FAILED -> message ?: "转写失败"
        RecognitionError.LANGUAGE_UNSUPPORTED -> "识别引擎不支持当前语言"
        RecognitionError.AUDIO_ERROR -> "录音失败，麦克风被占用了？"
        RecognitionError.UNKNOWN -> message ?: "语音识别失败"
    }

    private fun stopVoiceCapture() {
        voiceStarting = false
        runCatching {
            if (SpeechRecognitionManager.state.value != RecognitionState.IDLE) {
                SpeechRecognitionManager.stopRecording()
            }
        }
        chatWindow?.setVoiceActive(false)
    }

    // ---------------------------------------------------------- chat window

    /**
     * Open/close the independent mini chat window. The sprite window itself is
     * never made focusable, so opening chat cannot capture the rest of the
     * screen or interfere with another application's work.
     */
    private fun setInputMode(enabled: Boolean) {
        if (inputMode == enabled) return
        inputMode = enabled

        if (!enabled) {
            stopVoiceCapture()
            val old = chatWindow
            chatWindow = null
            chatParams = null
            if (old != null) {
                runCatching {
                    getSystemService(InputMethodManager::class.java)
                        ?.hideSoftInputFromWindow(old.windowToken, 0)
                }
                runCatching { windowManager.removeView(old) }
            }
            behavior?.reset()
            return
        }

        val sprite = petView ?: run { inputMode = false; return }
        sprite.setMenuVisible(false)
        refreshOutsideWatch()
        behavior?.stop()

        val density = resources.displayMetrics.density
        val bounds = screenBounds()
        val width = (PetChatWindowView.WINDOW_WIDTH_DP * density).toInt()
            .coerceAtMost((bounds.width() - (24 * density).toInt()).coerceAtLeast(1))
        val lp = WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = ((bounds.width() - width) / 2).coerceAtLeast(0)
            y = (64 * density).toInt().coerceAtLeast(0)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

        val window = PetChatWindowView(this).apply {
            onSend = { question -> ask(question, fromVoice = false) }
            onVoiceToggle = { toggleVoice() }
            onClose = { setInputMode(false) }
            onFocusLost = { if (inputMode) setInputMode(false) }
            onDragBy = { dx, dy ->
                val current = chatParams
                if (current != null) {
                    current.x += dx
                    current.y += dy
                    val b = screenBounds()
                    current.x = current.x.coerceIn(0, (b.width() - current.width).coerceAtLeast(0))
                    current.y = current.y.coerceIn(0, (b.height() - 80).coerceAtLeast(0))
                    runCatching { windowManager.updateViewLayout(this, current) }
                }
            }
        }
        val attached = runCatching {
            windowManager.addView(window, lp)
            true
        }.getOrElse {
            android.util.Log.w(TAG, "Unable to attach pet chat mini-window: ${it.message}")
            false
        }
        if (!attached) {
            inputMode = false
            behavior?.reset()
            return
        }
        chatWindow = window
        chatParams = lp
        window.requestFocus()
        main.postDelayed({
            if (chatWindow !== window) return@postDelayed
            window.focusComposer()
            runCatching {
                getSystemService(InputMethodManager::class.java)
                    ?.showSoftInput(window.findFocus(), InputMethodManager.SHOW_IMPLICIT)
            }
        }, 120L)
    }

    // ------------------------------------------------------------- overlay

    /**
     * A persisted preference alone is not evidence that an overlay can be
     * shown. In particular, the user can turn the switch on before granting
     * SYSTEM_ALERT_WINDOW. Keeping a foreground notification alive in that
     * state says "running" while there is no pet, and no later callback asks
     * WindowManager to retry. Stop cleanly instead; PetControlActivity retries
     * after the permission page returns and MinisApp retries after restoration.
     */
    private fun attachPetOrStop(startId: Int): Boolean {
        if (!PetPreferences.isEnabled(this)) {
            hidePet()
            stopSelf(startId)
            return false
        }
        if (!Settings.canDrawOverlays(this)) {
            android.util.Log.i(TAG, "Pet enabled but overlay permission is not granted; waiting for user grant")
            hidePet()
            stopSelf(startId)
            return false
        }
        showPetIfPossible()
        if (petView == null && petLoadJob?.isActive != true) {
            // Usually a deleted/corrupt selected package. Do not hold an FGS
            // notification claiming that an invisible pet is still present.
            android.util.Log.w(TAG, "No mountable pet package is selected")
            stopSelf(startId)
            return false
        }
        return true
    }

    private fun showPetIfPossible() {
        if (petView != null || petLoadJob?.isActive == true) return
        if (!PetPreferences.isEnabled(this)) return
        if (!Settings.canDrawOverlays(this)) return
        val selected = PetPackageManager.selected(this) ?: return

        val scale = PetPreferences.scale(this)
        val widthDp = 144f * scale
        val heightDp = widthDp * selected.manifest.cellHeight / selected.manifest.cellWidth
        val density = resources.displayMetrics.density
        val spriteWidth = (widthDp * density).toInt()
        val spriteHeight = (heightDp * density).toInt()
        val (savedX, savedY) = PetPreferences.position(this)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = savedX
            y = savedY
        }

        // A corrupt or deleted spritesheet — and an OutOfMemoryError on a large
        // atlas — must not take the whole app down: MinisApp.onCreate() starts
        // this service on every cold start once the pet is enabled, so an
        // escaping throw here would be a boot loop, not a one-off crash.
        val view = PetOverlayView(this).apply {
            setSpriteSize(spriteWidth, spriteHeight)
            sprite.animationSpeed = PetPreferences.speed(this@PetOverlayService)
            sprite.setState(baseState)
        }

        // The ~11.5 MB spritesheet is decoded off the main thread; the overlay
        // is mounted only once the bitmap is ready. Decode failures (corrupt
        // sheet, OOM) are caught here instead of on the main thread, so the
        // boot-loop protection above still holds. A fresh onStartCommand while
        // this is in flight is ignored (petLoadJob), and hidePet() cancels it.
        val generation = ++mountGeneration
        petLoadJob = serviceScope.launch {
            val decoded = withContext(Dispatchers.IO) {
                runCatching { view.sprite.loadPet(selected) }
            }
            // BitmapFactory is not cancellable on every OEM. A canceled old
            // decode must never attach over a newer reload once it finally
            // returns to the main dispatcher.
            if (generation != mountGeneration) return@launch
            if (decoded.isFailure) {
                android.util.Log.w(TAG, "Unable to load pet sprites: ${decoded.exceptionOrNull()?.message}")
                petLoadJob = null
                stopSelf()
                return@launch
            }

            view.onMenuAction = { action -> handleMenu(action) }
            view.onOutsideTouch = {
                if (view.isMenuVisible()) {
                    view.setMenuVisible(false)
                    refreshOutsideWatch()
                }
            }

            val engine = PetBehavior(
                position = { params?.let { it.x to it.y } ?: (0 to 0) },
                size = {
                    val v = petView
                    val w = v?.width?.takeIf { it > 0 } ?: spriteWidth
                    val h = v?.height?.takeIf { it > 0 } ?: spriteHeight
                    w to h
                },
                bounds = { screenBounds() },
                moveTo = { x, y -> moveWindow(x, y) },
                setState = { state -> petView?.sprite?.setState(state) },
                restingState = { baseState },
                isAgentBusy = { baseState != PetState.IDLE || inputMode || askJob?.isActive == true },
                onTuckChanged = { tucked -> if (tucked) petView?.hideBubble() },
            ).apply {
                setDensity(density)
                wanderEnabled = PetPreferences.wanderEnabled(this@PetOverlayService)
                edgeSnapEnabled = PetPreferences.edgeSnapEnabled(this@PetOverlayService)
                autoHideEnabled = PetPreferences.autoHideEnabled(this@PetOverlayService)
            }
            behavior = engine

            installTouch(view, lp)
            // The view has not been attached yet, so its measured dimensions
            // are zero. Clamp against the known sprite dimensions now, then
            // once more after layout in case the actual content differs.
            clampPosition(lp, spriteWidth, spriteHeight)
            val attached = runCatching {
                windowManager.addView(view, lp)
                true
            }.getOrElse {
                android.util.Log.w(TAG, "Unable to attach pet overlay: ${it.message}")
                false
            }
            if (!attached) {
                behavior = null
                petLoadJob = null
                stopSelf()
                return@launch
            }
            params = lp
            petView = view
            petLoadJob = null
            restoreAgentVisualState()
            if (screenAwake) {
                engine.reset()
                main.removeCallbacks(randomMood)
                main.postDelayed(randomMood, 3_500L)
            } else {
                // A process can be restored while the screen is already off;
                // ACTION_SCREEN_OFF is not replayed to a newly registered
                // receiver, so retain the state sampled in onCreate.
                view.sprite.pauseAnimation()
                engine.pauseTimers()
            }
            view.post {
                if (petView !== view || params !== lp) return@post
                clampPosition(
                    lp,
                    view.width.takeIf { it > 0 } ?: spriteWidth,
                    view.height.takeIf { it > 0 } ?: spriteHeight,
                )
                runCatching { windowManager.updateViewLayout(view, lp) }
                PetPreferences.setPosition(this@PetOverlayService, lp.x, lp.y)
            }
        }
    }

    private fun handleMenu(action: PetMenuAction) {
        when (action) {
            PetMenuAction.SETTINGS -> {
                val intent = Intent(this, PetControlActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { startActivity(intent) }
            }
            PetMenuAction.TUCK -> behavior?.tuckNow()
            PetMenuAction.CLOSE -> {
                PetPreferences.setEnabled(this, false)
                stopSelf()
            }
        }
    }

    private fun installTouch(view: PetOverlayView, lp: WindowManager.LayoutParams) {
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var dragged = false
        val dragSlop = ViewConfiguration.get(this).scaledTouchSlop

        val gesture = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                // A tucked pet uses its first tap to come back into view; only
                // once it is fully visible does a tap open the conversation.
                if (behavior?.wakeIfTucked() == true) return true
                if (view.isMenuVisible()) {
                    view.setMenuVisible(false)
                    refreshOutsideWatch()
                    return true
                }
                behavior?.onInteraction()
                setInputMode(!inputMode)
                if (inputMode) setTransientState(TAP_STATES.random(), 900L)
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                behavior?.onInteraction()
                if (!PetPreferences.tapOpensApp(this@PetOverlayService)) return true
                setInputMode(false)
                val launch = packageManager.getLaunchIntentForPackage(packageName)
                if (launch == null) {
                    petView?.showBubble("打不开主界面", 1_800L)
                    return true
                }
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { startActivity(launch) }
                    .onFailure { petView?.showBubble("打不开主界面", 1_800L) }
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                behavior?.onInteraction()
                setInputMode(false)
                view.setMenuVisible(!view.isMenuVisible())
                refreshOutsideWatch()
                setTransientState(PetState.WAVING, 700L)
            }
        })

        view.sprite.setOnTouchListener { _, event ->
            gesture.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = lp.x
                    startY = lp.y
                    dragged = false
                    behavior?.onInteraction()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (abs(dx) > dragSlop || abs(dy) > dragSlop) dragged = true
                    if (dragged && !inputMode) {
                        lp.x = startX + dx
                        lp.y = startY + dy
                        clampPosition(lp)
                        runCatching { windowManager.updateViewLayout(view, lp) }
                        if (abs(dx) > 4) {
                            view.sprite.setState(
                                if (dx >= 0) PetState.RUNNING_RIGHT else PetState.RUNNING_LEFT,
                            )
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragged && !inputMode) {
                        // Persist a valid pre-snap location as well as the
                        // final edge. If the process dies during the animation,
                        // restoration still lands where the user last put it.
                        PetPreferences.setPosition(this, lp.x, lp.y)
                        setTransientState(baseState, 200L)
                        behavior?.onDragReleased { x, y -> PetPreferences.setPosition(this, x, y) }
                    }
                    // Taps are classified by the gesture detector so that
                    // single/double/long presses stay distinguishable.
                    true
                }
                else -> false
            }
        }
    }

    private fun moveWindow(x: Int, y: Int) {
        val lp = params ?: return
        val view = petView ?: return
        lp.x = x
        lp.y = y
        runCatching { windowManager.updateViewLayout(view, lp) }
    }

    private fun setTransientState(state: PetState, durationMs: Long) {
        petView?.sprite?.setState(state)
        transientReset?.let(main::removeCallbacks)
        transientReset = Runnable { petView?.sprite?.setState(baseState) }
            .also { main.postDelayed(it, durationMs) }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val lp = params ?: return
        val view = petView ?: return
        clampPosition(lp)
        runCatching { windowManager.updateViewLayout(view, lp) }
        PetPreferences.setPosition(this, lp.x, lp.y)
        // Screen geometry changed under the pet; restart the idle timers so a
        // pending tuck target computed for the old bounds is discarded.
        chatParams?.let { cp ->
            val bounds = screenBounds()
            val side = (24 * resources.displayMetrics.density).toInt()
            cp.width = cp.width.coerceAtMost((bounds.width() - side).coerceAtLeast(1))
            cp.x = cp.x.coerceIn(0, (bounds.width() - cp.width).coerceAtLeast(0))
            cp.y = cp.y.coerceIn(0, (bounds.height() - 80).coerceAtLeast(0))
            chatWindow?.let { runCatching { windowManager.updateViewLayout(it, cp) } }
        }
        behavior?.reset()
    }

    private fun screenBounds(): Rect =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds
        } else {
            @Suppress("DEPRECATION")
            val dm = android.util.DisplayMetrics().also { windowManager.defaultDisplay.getMetrics(it) }
            Rect(0, 0, dm.widthPixels, dm.heightPixels)
        }

    private fun clampPosition(
        lp: WindowManager.LayoutParams,
        contentWidth: Int = petView?.width?.takeIf { it > 0 } ?: 0,
        contentHeight: Int = petView?.height?.takeIf { it > 0 } ?: 0,
    ) {
        val bounds = screenBounds()
        val (x, y) = PetOverlayGeometry.clamp(
            x = lp.x,
            y = lp.y,
            contentWidth = contentWidth,
            contentHeight = contentHeight,
            viewportWidth = bounds.width(),
            viewportHeight = bounds.height(),
        )
        lp.x = x
        lp.y = y
    }

    private fun hidePet() {
        // Invalidate before cancellation because an in-flight BitmapFactory
        // decode may only observe cancellation after it returns to main.
        mountGeneration++
        petLoadJob?.cancel()
        petLoadJob = null
        if (inputMode || chatWindow != null) setInputMode(false)
        behavior?.stop()
        behavior = null
        petView?.let {
            it.cancelPending()
            runCatching { windowManager.removeView(it) }
        }
        petView = null
        params = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "OpenMinis Pet", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Keeps the floating pet visible"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun startAsForeground() {
        val controlIntent = Intent(this, PetControlActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, controlIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stopIntent = Intent(this, PetOverlayService::class.java).apply { action = ACTION_STOP }
        val stopPi = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("OpenMinis Pet")
            .setContentText("桌面宠物正在运行")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pi)
            .addAction(0, "停止宠物", stopPi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
}
