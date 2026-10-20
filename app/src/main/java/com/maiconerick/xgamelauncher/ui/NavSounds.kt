package com.maiconerick.xgamelauncher.ui

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.sin

/**
 * 8-bit style navigation blips, synthesized once (square waves with a quick decay) into
 * static AudioTracks — no assets, negligible CPU. Move = focus change, select = A,
 * back = B. Toggled/volumed from Settings → Appearance via [configure].
 */
object NavSounds {
    private const val RATE = 22050
    @Volatile private var enabled = false
    @Volatile private var volume = 0.5f
    private var move: AudioTrack? = null
    private var select: AudioTrack? = null
    private var back: AudioTrack? = null
    private var lastMove = 0L

    fun configure(enabled: Boolean, volume: Float) {
        this.volume = volume.coerceIn(0f, 1f)
        this.enabled = enabled
        if (enabled && move == null) {
            move = track(tone(listOf(1760.0 to 22)))
            select = track(tone(listOf(988.0 to 35, 1480.0 to 45)))
            back = track(tone(listOf(1175.0 to 35, 740.0 to 45)))
        }
        listOfNotNull(move, select, back).forEach { runCatching { it.setVolume(this.volume) } }
    }

    fun move() {
        // Holding the D-pad fires many focus changes; don't machine-gun the blip.
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastMove < 45) return
        lastMove = now
        play(move)
    }
    fun select() = play(select)
    fun back() = play(back)

    private fun play(t: AudioTrack?) {
        if (!enabled || t == null) return
        runCatching {
            if (t.playState == AudioTrack.PLAYSTATE_PLAYING) t.stop()
            t.reloadStaticData()
            t.play()
        }
    }

    /** Square-wave segments (Hz to ms) with a short exponential decay per segment. */
    private fun tone(segments: List<Pair<Double, Int>>): ShortArray {
        val out = ArrayList<Short>()
        segments.forEach { (hz, ms) ->
            val n = RATE * ms / 1000
            for (i in 0 until n) {
                val t = i.toDouble() / RATE
                val sq = if (sin(2 * PI * hz * t) >= 0) 1.0 else -1.0
                val env = kotlin.math.exp(-4.0 * i / n)
                out += (sq * env * 0.28 * Short.MAX_VALUE).toInt().toShort()
            }
        }
        return out.toShortArray()
    }

    private fun track(pcm: ShortArray): AudioTrack? = runCatching {
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
            )
            .setBufferSizeInBytes(pcm.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
            .also { it.write(pcm, 0, pcm.size); it.setVolume(volume) }
    }.getOrNull()
}
