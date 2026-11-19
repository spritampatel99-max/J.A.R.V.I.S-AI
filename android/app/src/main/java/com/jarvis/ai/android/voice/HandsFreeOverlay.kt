package com.jarvis.ai.android.voice

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A small floating card that shows what Jarvis is doing while another app is on screen ("Listening…", the answer, the
 * call countdown with a Cancel button). It needs "Display over other apps"; without it nothing is shown and everything
 * else still works. While it is visible, Android also lets the app open other apps from the background.
 *
 * Must be used on the main thread.
 */
class HandsFreeOverlay(context: Context) {

    private val context = context.applicationContext
    private val windows = this.context.getSystemService(WindowManager::class.java)

    private var root: LinearLayout? = null
    private var message: TextView? = null
    private var cancel: Button? = null

    /** Called when the person taps Cancel on the card. */
    var onCancel: (() -> Unit)? = null

    val isAllowed: Boolean get() = Settings.canDrawOverlays(context)

    fun show(text: String, cancellable: Boolean) {
        if (!isAllowed) return
        val card = root ?: build()?.also { root = it } ?: return
        message?.text = text
        cancel?.visibility = if (cancellable) View.VISIBLE else View.GONE
        card.visibility = View.VISIBLE
    }

    fun hide() {
        val card = root ?: return
        root = null
        message = null
        cancel = null
        try {
            windows.removeView(card)
        } catch (e: IllegalArgumentException) {
            // Already gone.
        }
    }

    private fun build(): LinearLayout? {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val title = TextView(context).apply {
            text = "J.A.R.V.I.S."
            setTextColor(Color.parseColor("#4FD8FF"))
            textSize = 11f
            letterSpacing = 0.15f
        }
        val body = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            maxLines = 8
        }
        val stop = Button(context).apply {
            text = "Cancel"
            visibility = View.GONE
            setOnClickListener { onCancel?.invoke() }
        }
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F00D1222"))
                cornerRadius = dp(22).toFloat()
                setStroke(dp(1), Color.parseColor("#664FD8FF"))
            }
            addView(title)
            addView(body)
            addView(stop)
        }
        val params = WindowManager.LayoutParams(
            (context.resources.displayMetrics.widthPixels * 0.92f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(48)
        }
        return try {
            windows.addView(card, params)
            message = body
            cancel = stop
            card
        } catch (e: RuntimeException) {
            // Permission revoked in the meantime, or the window could not be added: show nothing.
            null
        }
    }
}
