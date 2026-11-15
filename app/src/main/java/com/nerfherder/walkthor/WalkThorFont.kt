package com.nerfherder.walkthor

import android.content.Context
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat

/**
 * Space Grotesk, WalkThor's typeface, for what WalkThor draws outside its Compose screens (the
 * keyboard, the FPS pill, messages, the close-all countdown), so everything reads as one app.
 */
object WalkThorFont {
    private val cache = HashMap<Int, Typeface>()

    /** [weight]: 400, 500, 600 or 700. Falls back to the system font if it can't be loaded. */
    fun get(context: Context, weight: Int = 500): Typeface = synchronized(cache) {
        cache.getOrPut(weight) {
            val res = when {
                weight >= 700 -> R.font.space_grotesk_700
                weight >= 600 -> R.font.space_grotesk_600
                weight >= 500 -> R.font.space_grotesk_500
                else -> R.font.space_grotesk_400
            }
            runCatching { ResourcesCompat.getFont(context.applicationContext, res) }.getOrNull()
                ?: Typeface.create(Typeface.DEFAULT, if (weight >= 600) Typeface.BOLD else Typeface.NORMAL)
        }
    }
}
