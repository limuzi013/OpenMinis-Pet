package com.openminis.app.pet

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.openminis.app.ui.settings.getThemeMode
import kotlin.math.roundToInt

/**
 * Focusable mini-window used by the desktop pet conversation.
 *
 * It deliberately lives in a SECOND TYPE_APPLICATION_OVERLAY window instead
 * of resizing/re-flagging the sprite overlay. That keeps the pet itself
 * permanently NOT_FOCUSABLE, so an agent can keep working and the user can
 * continue using whatever app is underneath. Tapping another app causes this
 * window to lose focus and close; Back does the same.
 */
class PetChatWindowView(context: Context) : LinearLayout(context) {
    companion object {
        const val WINDOW_WIDTH_DP = 344
        private const val IDLE_STATUS = "使用 App 默认模型；对话保存到「桌面宠物」会话"
    }

    var onSend: ((String) -> Unit)? = null
    var onVoiceToggle: (() -> Unit)? = null
    var onClose: (() -> Unit)? = null
    var onFocusLost: (() -> Unit)? = null
    var onDragBy: ((Int, Int) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val dark = when (getThemeMode(context)) {
        1 -> false
        2 -> true
        else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    }
    private val card = if (dark) Color.rgb(28, 28, 30) else Color.WHITE
    private val elevated = if (dark) Color.rgb(44, 44, 46) else Color.rgb(247, 247, 250)
    private val textPrimary = if (dark) Color.rgb(222, 228, 226) else Color.rgb(23, 29, 28)
    private val textSecondary = if (dark) Color.rgb(190, 201, 198) else Color.rgb(63, 73, 71)
    private val outline = if (dark) Color.rgb(56, 56, 58) else Color.rgb(209, 209, 214)
    private val accent = if (dark) Color.rgb(106, 148, 206) else Color.rgb(82, 138, 210)

    private val transcript = LinearLayout(context)
    private val transcriptScroll = ScrollView(context)
    private val status = TextView(context)
    private val input = object : EditText(context) {
        override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                onClose?.invoke()
                return true
            }
            return super.onKeyPreIme(keyCode, event)
        }
    }
    private val mic = Button(context)
    private val send = Button(context)
    private var everHadWindowFocus = false

    init {
        orientation = VERTICAL
        isFocusable = true
        isFocusableInTouchMode = true
        elevation = dp(12).toFloat()
        setPadding(dp(14), dp(10), dp(14), dp(12))
        background = rounded(card, 18f, outline, 1f)

        addView(buildHeader(), LayoutParams(LayoutParams.MATCH_PARENT, dp(42)))

        status.apply {
            text = IDLE_STATUS
            textSize = 11f
            setTextColor(textSecondary)
            setPadding(dp(2), 0, dp(2), dp(7))
        }
        addView(status, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        transcript.orientation = VERTICAL
        transcript.setPadding(dp(2), dp(2), dp(2), dp(6))
        transcriptScroll.apply {
            isFillViewport = false
            addView(transcript, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        addView(transcriptScroll, LayoutParams(LayoutParams.MATCH_PARENT, dp(154)))

        addView(buildComposer(), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private fun buildHeader(): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), 0, 0, 0)
        }
        val title = TextView(context).apply {
            text = "桌面宠物"
            textSize = 16f
            setTextColor(textPrimary)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val hint = TextView(context).apply {
            text = "  小窗"
            textSize = 11f
            setTextColor(textSecondary)
        }
        val close = Button(context).apply {
            text = "×"
            textSize = 20f
            setTextColor(textSecondary)
            background = rounded(Color.TRANSPARENT, 10f)
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(9), 0, dp(9), 0)
            setOnClickListener { onClose?.invoke() }
        }
        row.addView(title, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        row.addView(hint, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        row.addView(close, LayoutParams(dp(42), dp(38)))

        var downX = 0f
        var downY = 0f
        row.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).roundToInt()
                    val dy = (event.rawY - downY).roundToInt()
                    if (dx != 0 || dy != 0) {
                        onDragBy?.invoke(dx, dy)
                        downX = event.rawX
                        downY = event.rawY
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
        return row
    }

    private fun buildComposer(): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = rounded(elevated, 15f, outline, 1f)
        }

        mic.apply {
            text = "🎙"
            textSize = 16f
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setTextColor(textPrimary)
            background = rounded(Color.TRANSPARENT, 10f)
            setPadding(dp(4), dp(2), dp(4), dp(2))
            setOnClickListener { onVoiceToggle?.invoke() }
        }
        row.addView(mic, LayoutParams(dp(42), dp(42)))

        input.apply {
            hint = "给宠物发消息…"
            setHintTextColor(textSecondary)
            setTextColor(textPrimary)
            textSize = 14f
            background = null
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE
            maxLines = 4
            imeOptions = EditorInfo.IME_ACTION_SEND
            setPadding(dp(5), dp(8), dp(5), dp(7))
            setSingleLine(false)
            setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_SEND ||
                    (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP)
                ) {
                    submit()
                    true
                } else false
            }
        }
        row.addView(input, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        send.apply {
            text = "↑"
            textSize = 19f
            setTextColor(Color.WHITE)
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            background = rounded(accent, 11f)
            setPadding(0, 0, 0, 0)
            setOnClickListener { submit() }
        }
        row.addView(send, LayoutParams(dp(40), dp(40)))
        return row
    }

    private fun submit() {
        val text = input.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        input.setText("")
        onSend?.invoke(text)
    }

    fun focusComposer() {
        input.requestFocus()
    }

    fun setInputText(text: String) {
        input.setText(text)
        input.setSelection(input.text?.length ?: 0)
    }

    fun setBusy(busy: Boolean) {
        send.isEnabled = !busy
        status.text = if (busy) "Minis 正在思考…" else IDLE_STATUS
    }

    fun setVoiceActive(active: Boolean) {
        mic.text = if (active) "■" else "🎙"
        status.text = if (active) "正在听…" else if (send.isEnabled) IDLE_STATUS else "Minis 正在思考…"
    }

    fun setStatus(message: String) {
        status.text = message
    }

    fun addUserMessage(message: String) = addMessage("你", message, true)
    fun addAssistantMessage(message: String) = addMessage("Minis", message, false)

    private fun addMessage(label: String, message: String, user: Boolean) {
        val wrap = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = if (user) Gravity.END else Gravity.START
            setPadding(0, dp(4), 0, dp(4))
        }
        val role = TextView(context).apply {
            text = label
            textSize = 10f
            setTextColor(textSecondary)
            setPadding(dp(6), 0, dp(6), dp(3))
        }
        val body = TextView(context).apply {
            text = message
            textSize = 13f
            setTextColor(textPrimary)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = rounded(if (user) elevated else Color.TRANSPARENT, 13f, if (user) outline else Color.TRANSPARENT, if (user) 1f else 0f)
            maxWidth = dp(286)
        }
        wrap.addView(role, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        wrap.addView(body, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        transcript.addView(wrap, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        transcriptScroll.post { transcriptScroll.fullScroll(FOCUS_DOWN) }
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) {
            everHadWindowFocus = true
        } else if (everHadWindowFocus && isAttachedToWindow) {
            onFocusLost?.invoke()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            onClose?.invoke()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun rounded(fill: Int, radiusDp: Float, stroke: Int = Color.TRANSPARENT, strokeDp: Float = 0f) =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusDp * density
            setColor(fill)
            if (strokeDp > 0f && Color.alpha(stroke) > 0) setStroke(dp(strokeDp), stroke)
        }

    private fun dp(value: Int): Int = (value * density).roundToInt()
    private fun dp(value: Float): Int = (value * density).roundToInt()
}
