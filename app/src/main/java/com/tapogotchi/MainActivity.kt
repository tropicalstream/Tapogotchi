package com.tapogotchi

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.tapogotchi.audio.Audio
import com.tapogotchi.engine.GameEngine
import com.tapogotchi.engine.Host
import com.tapogotchi.platform.StepTracker
import com.tapogotchi.platform.YawTracker
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Input (FABLE_X3_STARTER_GUIDE Part II + suite conventions):
 *  - Right temple pad swipe -> one discrete direction classified on
 *    finger-up (one gesture = one step; the TapChess standard).
 *  - Temple click arrives as a KEY (BUTTON_A / DPAD_CENTER): select.
 *    Double-tap = back. Safe Tap defers singles so doubles can cancel.
 *  - Left pad (cyttsp6) swallowed. Steps walk the pet; yaw floats it.
 */
class MainActivity : Activity(), Host {

    private lateinit var store: SettingsStore
    private lateinit var audio: Audio
    private lateinit var engine: GameEngine
    private lateinit var renderer: Renderer
    private lateinit var gameView: GameView
    private lateinit var sbsRoot: BinocularSbsLayout
    private lateinit var steps: StepTracker
    private lateinit var yaw: YawTracker

    private val handler = Handler(Looper.getMainLooper())

    private var keyDownAt = 0L
    private var keyHeld = false
    private var lastTapUpAt = 0L
    private var lastTapGuard = 0L
    private var pendingClick: Runnable? = null

    private var touchActive = false
    private var touchStartT = 0L
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var touchMoved = false
    private var systemHoldTriggered = false
    private var sumX = 0f
    private var sumY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dropFirst = true

    /** A stationary right-pad hold belongs to RayNeo's system Control Center. */
    private val systemHold = Runnable {
        if (touchActive && !touchMoved) {
            systemHoldTriggered = true
            openRayNeoControlCenter()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SettingsStore(this)
        audio = Audio(this).also { it.loadAsync() }
        engine = GameEngine(store, this)
        renderer = Renderer(engine, store)
        yaw = YawTracker(this)
        renderer.yawTracker = yaw
        steps = StepTracker(this).also { st ->
            st.onStep = { delta -> runOnUiThread { engine.onSteps(delta) } }
        }
        gameView = GameView(this, engine, renderer)
        sbsRoot = BinocularSbsLayout(this).apply { addView(gameView) }
        setContentView(sbsRoot)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()
        applySettings()
        engine.boot()
        if (checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION), 4001)
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        steps.stop()
        steps.start()
    }

    // ------------------------------------------------------------ Host

    override fun applySettings() {
        audio.volume = store.soundVolume / 10f
        gameView.frameCap30 = store.frameCap30
        sbsRoot.sbsEnabled = store.sbs
    }

    override fun sound(id: Int, pitch: Float, vol: Float) = audio.play(id, pitch, vol)

    // --------------------------------------------------------------- input

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val isTapKey = event.keyCode == KeyEvent.KEYCODE_BUTTON_A ||
            event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            event.keyCode == KeyEvent.KEYCODE_ENTER
        if (isTapKey) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (event.repeatCount == 0) {
                        keyDownAt = SystemClock.uptimeMillis()
                        keyHeld = event.isLongPress
                    } else {
                        keyHeld = true
                    }
                    // The launcher must see DOWN/repeat events to own long-hold.
                    return super.dispatchKeyEvent(event)
                }
                KeyEvent.ACTION_UP -> {
                    val now = SystemClock.uptimeMillis()
                    val held = keyHeld || event.isCanceled ||
                        maxOf(event.eventTime - event.downTime, now - keyDownAt) >= SYSTEM_HOLD_MS
                    keyDownAt = 0L
                    keyHeld = false
                    if (held) return super.dispatchKeyEvent(event)
                    handleClick(now)
                    return true
                }
            }
            return super.dispatchKeyEvent(event)
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            val dir = when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> 0
                KeyEvent.KEYCODE_DPAD_DOWN -> 1
                KeyEvent.KEYCODE_DPAD_LEFT -> 2
                KeyEvent.KEYCODE_DPAD_RIGHT -> 3
                else -> -1
            }
            if (dir >= 0) { engine.swipeDir(dir); return true }
        }
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            if (engine.onBack()) return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun handleClick(now: Long) {
        if (now - lastTapGuard < 35) return
        lastTapGuard = now
        val gap = now - lastTapUpAt
        if (gap in 40..320) {
            pendingClick?.let { handler.removeCallbacks(it) }
            pendingClick = null
            lastTapUpAt = 0
            engine.doubleTap()
            return
        }
        lastTapUpAt = now
        if (store.safeTap) {
            val r = Runnable { pendingClick = null; engine.click() }
            pendingClick = r
            handler.postDelayed(r, 300)
        } else engine.click()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val name = ev.device?.name ?: ""
        if (name.contains("cyttsp6", ignoreCase = true)) return true

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchActive = true; touchStartT = SystemClock.uptimeMillis()
                touchStartX = ev.x; touchStartY = ev.y
                touchMoved = false; systemHoldTriggered = false
                sumX = 0f; sumY = 0f; lastX = ev.x; lastY = ev.y; dropFirst = true
                handler.postDelayed(systemHold, SYSTEM_HOLD_DELAY_MS)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!touchActive) {
                    touchActive = true; touchStartT = SystemClock.uptimeMillis()
                    sumX = 0f; sumY = 0f; lastX = ev.x; lastY = ev.y; dropFirst = true
                } else {
                    var dx = ev.x - lastX; var dy = ev.y - lastY
                    lastX = ev.x; lastY = ev.y
                    val moveTolerance = max(18f, 0.04f * minOf(
                        resources.displayMetrics.widthPixels,
                        resources.displayMetrics.heightPixels
                    ))
                    if (abs(ev.x - touchStartX) > moveTolerance ||
                        abs(ev.y - touchStartY) > moveTolerance
                    ) {
                        touchMoved = true
                        handler.removeCallbacks(systemHold)
                    }
                    if (dropFirst) dropFirst = false else {
                        if (store.flipHorizontal) dx = -dx
                        if (store.flipVertical) dy = -dy
                        sumX += dx; sumY += dy
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(systemHold)
                if (touchActive && !systemHoldTriggered) resolveGesture(SystemClock.uptimeMillis())
                touchActive = false
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(systemHold)
                touchActive = false
            }
        }
        return true
    }

    /** One swipe = one discrete step, classified on finger-up (suite standard). */
    private fun resolveGesture(now: Long) {
        val dist = sqrt(sumX * sumX + sumY * sumY)
        val threshold = max(48f, 0.09f * resources.displayMetrics.widthPixels) / store.swipeSens
        if (dist >= threshold) {
            val dir = if (abs(sumX) >= abs(sumY)) { if (sumX > 0) 3 else 2 } else { if (sumY < 0) 0 else 1 }
            engine.swipeDir(dir)
        } else if (now - touchStartT <= 320) {
            handleClick(now)
        }
    }

    // ------------------------------------------------------------ lifecycle

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        applySettings()
        steps.start()
        yaw.start()
        gameView.start()
        engine.boot()   // catch up on whatever happened while paused
    }

    override fun onPause() {
        engine.onAppPause()
        steps.stop()
        yaw.stop()
        gameView.stop()
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        audio.release()
        super.onDestroy()
    }

    /** Open the genuine X3 launcher panel, with HOME as a firmware-safe escape. */
    private fun openRayNeoControlCenter() {
        val controlCenter = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("mercury://com.ffalconxr.mercury.launcher/openApp/shortcut")
        ).addCategory(Intent.CATEGORY_DEFAULT)
        runCatching { startActivity(controlCenter) }
            .onFailure {
                val home = Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { startActivity(home); finishAndRemoveTask() }
            }
    }

    private fun hideSystemBars() {
        // The X3 firmware's Control Center input monitor interoperates with the
        // legacy immersive flags. WindowInsetsController can keep the shade from
        // taking focus after the right-arm system hold on this Android build.
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
    }

    companion object {
        private const val SYSTEM_HOLD_MS = 450L
        private const val SYSTEM_HOLD_DELAY_MS = 550L
    }
}
