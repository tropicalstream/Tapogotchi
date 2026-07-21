package com.tapogotchi.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Synthesized SFX (no audio binaries ship). Audio-first is the X3 idiom:
 * each need has its own chirp signature, so a wearer knows hunger from
 * sickness from a mess without ever looking at the pet.
 */
class Audio(private val context: Context) {

    companion object {
        const val SELECT = 0
        const val BACK = 1
        const val EAT = 2
        const val SNACK = 3
        const val HAPPY = 4
        const val SAD = 5
        const val CLEAN = 6
        const val POOP = 7
        const val CALL_HUNGRY = 8      // two low coaxing beeps
        const val CALL_UNHAPPY = 9     // falling whistle
        const val CALL_SICK = 10       // wobbling low tone
        const val CALL_MESS = 11       // short raspberry
        const val CALL_FALSE = 12      // cheeky triple chirp (discipline me!)
        const val MEDICINE = 13
        const val DISCIPLINE = 14
        const val SLEEP = 15
        const val WAKE = 16
        const val EVOLVE = 17
        const val WIN = 18
        const val LOSE = 19
        const val DEATH = 20
        const val WALK_TICK = 21
        const val HATCH = 22
        private const val COUNT = 23
        private const val RATE = 22050
    }

    private val pool = SoundPool.Builder()
        .setMaxStreams(6)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        ).build()

    private val ids = IntArray(COUNT)
    @Volatile private var loaded = false
    @Volatile var volume = 0.8f
    private val rng = Random(5)

    fun loadAsync() {
        Thread {
            runCatching {
                val dir = File(context.cacheDir, "snd").apply { mkdirs() }
                ids[SELECT] = load(dir, "sel", buf(70) { t -> sine(760f + t * 400f, t) * exp(-t * 18f) * 0.5f })
                ids[BACK] = load(dir, "bak", buf(80) { t -> sine(420f - t * 300f, t) * exp(-t * 16f) * 0.45f })
                ids[EAT] = load(dir, "eat", munch(3))
                ids[SNACK] = load(dir, "snk", munch(2, 1.4f))
                ids[HAPPY] = load(dir, "hap", arpeggio(intArrayOf(523, 659, 784), 70, 0.6f))
                ids[SAD] = load(dir, "sad", arpeggio(intArrayOf(392, 311), 120, 0.5f))
                ids[CLEAN] = load(dir, "cln", buf(300) { t -> noise() * exp(-t * 7f) * 0.4f * (0.5f + 0.5f * sin(2.0 * PI * 6.0 * t).toFloat()) })
                ids[POOP] = load(dir, "poo", buf(140) { t -> sine(160f - t * 200f, t) * exp(-t * 12f) * 0.5f })
                ids[CALL_HUNGRY] = load(dir, "chu", doubleBeep(392f, 392f))
                ids[CALL_UNHAPPY] = load(dir, "cun", buf(420) { t -> sine(880f - t * 900f, t) * exp(-t * 4f) * 0.5f })
                ids[CALL_SICK] = load(dir, "csk", buf(600) { t -> sine(196f + 18f * sin(2.0 * PI * 5.0 * t).toFloat(), t) * exp(-t * 2.5f) * 0.55f })
                ids[CALL_MESS] = load(dir, "cms", buf(220) { t -> (noise() * 0.5f + sine(130f, t) * 0.5f) * exp(-t * 8f) })
                ids[CALL_FALSE] = load(dir, "cfa", arpeggio(intArrayOf(988, 988, 988), 55, 0.55f))
                ids[MEDICINE] = load(dir, "med", arpeggio(intArrayOf(440, 554, 659), 90, 0.55f))
                ids[DISCIPLINE] = load(dir, "dis", doubleBeep(220f, 175f))
                ids[SLEEP] = load(dir, "slp", arpeggio(intArrayOf(494, 392, 330), 150, 0.4f))
                ids[WAKE] = load(dir, "wak", arpeggio(intArrayOf(330, 392, 494), 110, 0.5f))
                ids[EVOLVE] = load(dir, "evo", arpeggio(intArrayOf(392, 494, 587, 784, 988), 95, 0.65f))
                ids[WIN] = load(dir, "win", arpeggio(intArrayOf(523, 659, 784, 1046), 100, 0.65f))
                ids[LOSE] = load(dir, "los", buf(500) { t -> sine(330f - t * 180f, t) * exp(-t * 4f) * 0.5f })
                ids[DEATH] = load(dir, "dth", buf(1400) { t -> sine(294f - t * 120f, t) * exp(-t * 1.6f) * 0.5f })
                ids[WALK_TICK] = load(dir, "wtk", buf(35) { t -> sine(1250f, t) * exp(-t * 60f) * 0.35f })
                ids[HATCH] = load(dir, "hat", buf(500) { t ->
                    (noise() * exp(-t * 22f) * 0.5f) +
                        (if (t > 0.15f) sine(784f, t - 0.15f) * exp(-(t - 0.15f) * 6f) * 0.5f else 0f)
                })
                loaded = true
            }
        }.start()
    }

    fun play(id: Int, pitch: Float = 1f, vol: Float = 1f) {
        if (!loaded || id < 0 || id >= COUNT) return
        val s = ids[id]
        if (s == 0) return
        val v = (volume * vol).coerceIn(0f, 1f)
        if (v <= 0f) return
        pool.play(s, v, v, 1, 0, pitch.coerceIn(0.5f, 2f))
    }

    fun release() { runCatching { pool.release() } }

    // ------------------------------------------------------------ synth

    private fun buf(ms: Int, gen: (Float) -> Float): ShortArray {
        val n = RATE * ms / 1000
        return ShortArray(n) { i -> (gen(i.toFloat() / RATE).coerceIn(-1f, 1f) * 30000f).toInt().toShort() }
    }
    private fun sine(f: Float, t: Float) = sin(2.0 * PI * f * t).toFloat()
    private fun noise() = rng.nextFloat() * 2f - 1f

    private fun munch(bites: Int, pitch: Float = 1f): ShortArray {
        val biteMs = 110
        return buf(biteMs * bites + 60) { t ->
            val i = (t / (biteMs / 1000f)).toInt()
            val lt = t - i * (biteMs / 1000f)
            if (i >= bites) 0f
            else (noise() * 0.55f + sine(240f * pitch, lt) * 0.4f) * exp(-lt * 26f)
        }
    }

    private fun doubleBeep(f1: Float, f2: Float): ShortArray = buf(360) { t ->
        when {
            t < 0.13f -> sine(f1, t) * exp(-t * 9f) * 0.55f
            t in 0.18f..0.34f -> sine(f2, t - 0.18f) * exp(-(t - 0.18f) * 9f) * 0.55f
            else -> 0f
        }
    }

    private fun arpeggio(freqs: IntArray, noteMs: Int, amp: Float): ShortArray {
        val total = noteMs * freqs.size + 240
        return buf(total) { t ->
            var v = 0f
            for ((i, f) in freqs.withIndex()) {
                val start = i * noteMs / 1000f
                if (t >= start) {
                    val lt = t - start
                    v += (sine(f.toFloat(), lt) + 0.3f * sine(f * 2f, lt)) * exp(-lt * 5.5f) * amp * 0.45f
                }
            }
            v
        }
    }

    // ------------------------------------------------------------- wav

    private fun DataOutputStream.wInt(v: Int) { write(v and 0xFF); write((v shr 8) and 0xFF); write((v shr 16) and 0xFF); write((v shr 24) and 0xFF) }
    private fun DataOutputStream.wShort(v: Int) { write(v and 0xFF); write((v shr 8) and 0xFF) }

    private fun load(dir: File, name: String, pcm: ShortArray): Int {
        val f = File(dir, "$name.wav")
        val dataLen = pcm.size * 2
        DataOutputStream(BufferedOutputStream(FileOutputStream(f))).use { o ->
            o.writeBytes("RIFF"); o.wInt(36 + dataLen); o.writeBytes("WAVE")
            o.writeBytes("fmt "); o.wInt(16); o.wShort(1); o.wShort(1)
            o.wInt(RATE); o.wInt(RATE * 2); o.wShort(2); o.wShort(16)
            o.writeBytes("data"); o.wInt(dataLen)
            for (s in pcm) o.wShort(s.toInt())
        }
        return pool.load(f.absolutePath, 1)
    }
}
