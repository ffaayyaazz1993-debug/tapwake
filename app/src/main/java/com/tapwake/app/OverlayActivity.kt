package com.tapwake.app

import android.os.Bundle
import android.view.GestureDetector
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity

/**
 * A black screen that is awake at zero brightness. It exists only so the digitizer
 * can see a double tap while the phone looks asleep; on the double tap it restores
 * the normal brightness and finishes, handing the user back to the lock screen.
 */
class OverlayActivity : ComponentActivity() {

    private var detector: GestureDetector? = null
    private var rootView: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)

        // The service decides how bright this screen should appear: dark for the
        // tap path, a configurable glow for the raise-to-wake path.
        val brightness = intent?.getFloatExtra(EXTRA_WAKE_BRIGHTNESS, BRIGHTNESS_ASLEEP) ?: BRIGHTNESS_ASLEEP
        applyBrightness(brightness)

        Log.d("TAPWAK", "Overlay shown - waiting for double tap (brightness=$brightness)")

        val gestureListener = object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(event: MotionEvent): Boolean {
                Log.d("TAPWAK", "Double tap detected - waking screen")
                wake()
                return true
            }
        }
        val gestures = GestureDetector(this, gestureListener).apply {
            setIsLongpressEnabled(false)
        }
        detector = gestures

        val view = View(this).apply { setBackgroundColor(BLACK) }
        view.setOnTouchListener { _, event -> gestures.onTouchEvent(event) }
        rootView = view
        setContentView(view)

        activeInstance = this
    }

    override fun onDestroy() {
        if (activeInstance === this) activeInstance = null
        rootView = null
        detector = null
        super.onDestroy()
    }

    private fun wake() {
        applyBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
        finish()
    }

    private fun applyBrightness(brightness: Float) {
        val attributes = window.attributes
        attributes.screenBrightness = brightness
        window.attributes = attributes
    }

    companion object {
        private const val BLACK = 0xFF000000.toInt()
        private const val BRIGHTNESS_ASLEEP = 0.03f

        /** Intent extra: screen brightness this overlay should use when shown. */
        const val EXTRA_WAKE_BRIGHTNESS = "tapwake.wake_brightness"

        @Volatile
        private var activeInstance: OverlayActivity? = null

        /** Called when the screen comes on without a double tap - dismiss any leftover overlay. */
        fun finishActive() {
            activeInstance?.runOnUiThread { activeInstance?.finish() }
        }
    }
}
