package com.maiconerick.xgamelauncher.tracker

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.maiconerick.xgamelauncher.R

/**
 * SoundPool wrapper for lid open / lid closed / game start / game finish cues. Same wav
 * files xPTT uses (pixel_start / pixel_finish), played directly (no notification post) so
 * the effect is instantaneous and doesn't spam the shade.
 */
object SoundFx {

    @Volatile private var pool: SoundPool? = null
    @Volatile private var idStart: Int = 0
    @Volatile private var idFinish: Int = 0
    @Volatile private var loaded = false
    @Volatile private var loadedCount = 0
    /** Cue requested before the samples finished loading (first launch after a cold start). */
    @Volatile private var pending: Int = 0

    fun init(ctx: Context) {
        if (pool != null) return
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val p = SoundPool.Builder().setMaxStreams(2).setAudioAttributes(attrs).build()
        idStart = p.load(ctx, R.raw.pixel_start, 1)
        idFinish = p.load(ctx, R.raw.pixel_finish, 1)
        p.setOnLoadCompleteListener { sp, _, status ->
            if (status != 0) return@setOnLoadCompleteListener
            loadedCount++
            if (loadedCount >= 2) {
                loaded = true
                // Play what was asked for while loading instead of silently dropping it.
                pending.takeIf { it != 0 }?.let { sp.play(it, 1f, 1f, 0, 0, 1f) }
                pending = 0
            }
        }
        pool = p
    }

    fun playStart() = play(idStart)
    fun playFinish() = play(idFinish)

    private fun play(id: Int) {
        val p = pool ?: return
        if (loaded) p.play(id, 1f, 1f, 0, 0, 1f) else pending = id
    }

    fun release() {
        pool?.release(); pool = null; loaded = false; loadedCount = 0; pending = 0
    }
}
