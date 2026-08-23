package com.learn.antilazy

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView

/** Visible reminder above other apps. Requires the user-granted overlay permission. */
@SuppressLint("StaticFieldLeak") // Holds only an application-context WindowManager view.
object OverlayReminder {

    private const val AUTO_DISMISS_MS = 20_000L

    private data class Message(val title: String, val text: String)

    private val handler = Handler(Looper.getMainLooper())
    private val pendingMessages = ArrayDeque<Message>()
    private var appContext: Context? = null
    private var windowManager: WindowManager? = null
    private var currentView: View? = null
    private val autoDismiss = Runnable { dismiss() }

    fun canShow(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun show(context: Context, title: String, text: String): Boolean {
        if (!canShow(context) || Looper.myLooper() != Looper.getMainLooper()) return false
        appContext = context.applicationContext
        val message = Message(title, text)
        if (currentView != null) {
            pendingMessages.addLast(message)
            return true
        }
        return showNow(context.applicationContext, message)
    }

    @SuppressLint("InflateParams") // WindowManager, not a ViewGroup, supplies layout params.
    private fun showNow(context: Context, message: Message): Boolean {
        val wm = context.getSystemService(WindowManager::class.java) ?: return false
        val view = LayoutInflater.from(context).inflate(R.layout.activity_reminder, null)
        view.findViewById<TextView>(R.id.tv_reminder_title).text = message.title
        view.findViewById<TextView>(R.id.tv_reminder_text).text = message.text
        view.findViewById<Button>(R.id.btn_dismiss).setOnClickListener { dismiss() }
        view.findViewById<View>(R.id.reminder_root).setOnClickListener { dismiss() }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        return runCatching {
            wm.addView(view, params)
            windowManager = wm
            currentView = view
            handler.postDelayed(autoDismiss, AUTO_DISMISS_MS)
        }.isSuccess
    }

    fun dismiss() {
        handler.removeCallbacks(autoDismiss)
        val view = currentView ?: return
        runCatching { windowManager?.removeViewImmediate(view) }
        currentView = null
        windowManager = null
        val next = pendingMessages.removeFirstOrNull()
        val context = appContext
        if (next != null && context != null) {
            handler.post {
                if (!showNow(context, next)) pendingMessages.clear()
            }
        }
    }

    fun dismissAll() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { dismissAll() }
            return
        }
        pendingMessages.clear()
        handler.removeCallbacks(autoDismiss)
        val view = currentView
        if (view != null) runCatching { windowManager?.removeViewImmediate(view) }
        currentView = null
        windowManager = null
        appContext = null
    }
}
