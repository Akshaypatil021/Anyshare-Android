package com.example.anyshare.utils

import android.app.Activity
import android.content.Intent
import android.os.Build
import com.example.anyshare.R

/**
 * ActivityTransitionHelper provides smooth, fluid transitions between screens and tabs,
 * eliminating the jarring system window launch popups and respecting the user's animation toggle.
 */
object ActivityTransitionHelper {

    /**
     * Seamlessly navigates between the primary bottom navigation tabs (Receive, Send, Settings)
     * using FLAG_ACTIVITY_REORDER_TO_FRONT to preserve state without recreating activities.
     */
    fun navigateToTab(activity: Activity, targetClass: Class<out Activity>) {
        if (activity.javaClass == targetClass) return

        val intent = Intent(activity, targetClass).apply {
            flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        }
        activity.startActivity(intent)
        applyTabTransition(activity)
    }

    /**
     * Applies a gentle cross-fade transition so the bottom bar remains static
     * and content fades seamlessly into view like a single-page app.
     */
    fun applyTabTransition(activity: Activity) {
        val animationsEnabled = try {
            DeviceConfigManager(activity).isAnimationsEnabled()
        } catch (_: Exception) {
            true
        }

        if (!animationsEnabled) {
            applyZeroTransition(activity)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { // Android 14+ (API 34+)
            activity.overrideActivityTransition(
                Activity.OVERRIDE_TRANSITION_OPEN,
                R.anim.fade_in_tab,
                R.anim.fade_out_tab
            )
            activity.overrideActivityTransition(
                Activity.OVERRIDE_TRANSITION_CLOSE,
                R.anim.fade_in_tab,
                R.anim.fade_out_tab
            )
        } else {
            @Suppress("DEPRECATION")
            activity.overridePendingTransition(R.anim.fade_in_tab, R.anim.fade_out_tab)
        }
    }

    /**
     * Launches a child screen (History, Scanner, Transfer) with a smooth horizontal slide.
     */
    fun startActivityWithSlide(activity: Activity, intent: Intent) {
        activity.startActivity(intent)

        val animationsEnabled = try {
            DeviceConfigManager(activity).isAnimationsEnabled()
        } catch (_: Exception) {
            true
        }

        if (!animationsEnabled) {
            applyZeroTransition(activity)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            activity.overrideActivityTransition(
                Activity.OVERRIDE_TRANSITION_OPEN,
                R.anim.slide_in_right,
                R.anim.slide_out_left
            )
        } else {
            @Suppress("DEPRECATION")
            activity.overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
        }
    }

    /**
     * Finishes a child activity with a smooth exit slide back to the caller.
     */
    fun finishWithSlide(activity: Activity) {
        activity.finish()
        applySlideCloseTransition(activity)
    }

    /**
     * Applies slide close animation when an activity finishes.
     */
    fun applySlideCloseTransition(activity: Activity) {
        val animationsEnabled = try {
            DeviceConfigManager(activity).isAnimationsEnabled()
        } catch (_: Exception) {
            true
        }

        if (!animationsEnabled) {
            applyZeroTransition(activity)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            activity.overrideActivityTransition(
                Activity.OVERRIDE_TRANSITION_CLOSE,
                R.anim.slide_in_left,
                R.anim.slide_out_right
            )
        } else {
            @Suppress("DEPRECATION")
            activity.overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
        }
    }

    private fun applyZeroTransition(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0)
            activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            activity.overridePendingTransition(0, 0)
        }
    }
}
