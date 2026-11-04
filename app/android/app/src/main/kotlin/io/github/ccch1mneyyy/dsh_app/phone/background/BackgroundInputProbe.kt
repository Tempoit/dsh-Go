package io.github.ccch1mneyyy.dsh_app.phone.background

import android.app.Presentation
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Inert touch receiver on the gate's disposable display; never opens a user app. */
internal class BackgroundInputProbe(private val context: Context, private val display: Display) : AutoCloseable {
    private val ui = Handler(Looper.getMainLooper())
    private val closed = AtomicBoolean(false)
    private val probe = InputProbe(display.displayId)
    private var presentation: Presentation? = null // Main looper only.

    fun verify(send: (InputProbe.Target, Long) -> Unit) {
        if (Looper.myLooper() == ui.looper || display.displayId <= Display.DEFAULT_DISPLAY || !display.isValid)
            throw InputProbe.Failure("inputWindowNotReady")
        if (!ui.post {
            if (closed.get()) return@post
            try {
                val dialog = Presentation(context, display, android.R.style.Theme_Material_Light_NoActionBar)
                presentation = dialog
                dialog.setCancelable(false)
                val window = checkNotNull(dialog.window)
                // Keep Presentation's window-context type; changing it breaks Android 12+ tokens.
                window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
                window.setWindowAnimations(0)
                val targetDisplay = display
                val view = object : View(dialog.context) {
                    private var frameRequested = false

                    override fun onDraw(canvas: Canvas) { canvas.drawColor(Color.BLACK) }

                    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
                        super.onSizeChanged(w, h, oldw, oldh)
                        if (frameRequested || w < 4 || h < 4) return
                        frameRequested = true
                        viewTreeObserver.registerFrameCommitCallback {
                            ui.post {
                                if (!closed.get() && isAttachedToWindow && targetDisplay.isValid &&
                                    this.display?.displayId == targetDisplay.displayId) {
                                    val location = IntArray(2)
                                    getLocationOnScreen(location)
                                    probe.ready(InputProbe.Target(targetDisplay.displayId,
                                        location[0] + width / 2, location[1] + height / 2))
                                }
                            }
                        }
                    }

                    override fun onTouchEvent(event: MotionEvent): Boolean {
                        if (!closed.get()) probe.accept(this.display?.displayId ?: Display.INVALID_DISPLAY,
                            event.downTime, event.actionMasked, event.rawX, event.rawY)
                        return true
                    }
                }
                dialog.setContentView(view)
                dialog.show()
                window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            } catch (e: Exception) {
                probe.fail(if (e is SecurityException) e else InputProbe.Failure("inputWindowNotReady"))
            }
        }) throw InputProbe.Failure("inputWindowNotReady")
        probe.verify(now = SystemClock::uptimeMillis, send = { target, time ->
            if (closed.get() || !display.isValid) throw InputProbe.Failure("inputWindowNotReady")
            send(target, time)
        })
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val cleanup = FutureTask<Unit> { presentation?.dismiss(); presentation = null }
        // A timed-out creation cannot outlive cleanup in this same queue. The core releases
        // the display even if this acknowledgement times out; never report that as success.
        var interrupted = Thread.interrupted()
        try {
            if (Looper.myLooper() == ui.looper) cleanup.run()
            else if (!ui.post(cleanup)) throw InputProbe.Failure("inputProbeCleanupFailed")
            cleanup.get(2000, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            interrupted = true
            throw InputProbe.Failure("inputProbeCleanupFailed")
        } catch (e: Exception) {
            throw InputProbe.Failure("inputProbeCleanupFailed")
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }
}
