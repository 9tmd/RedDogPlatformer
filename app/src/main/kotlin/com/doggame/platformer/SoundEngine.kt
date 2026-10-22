package com.doggame.platformer

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * 纯代码合成音效引擎（不依赖任何音频资源文件）。
 * - 音效：正弦波 + 二次谐波（温暖些）+ 包络，AudioTrack 静态模式播放一次。
 * - 背景音乐：一段较长的欢快旋律循环（经典平台跳跃游戏风格），AudioTrack 流模式后台循环。
 * 所有 AudioTrack 操作包 try/catch，音频不可用时静默降级。
 */
class SoundEngine {

    private val sampleRate = 44100

    // 波形：基波 + 一点二次谐波，更接近 8-bit 芯片音
    private fun wave(phase: Double): Double = 0.85 * sin(phase) + 0.15 * sin(2.0 * phase)

    private fun play(samples: ShortArray) {
        try {
            val track = AudioTrack(
                AudioManager.STREAM_MUSIC, sampleRate,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                samples.size * 2, AudioTrack.MODE_STATIC
            )
            track.write(samples, 0, samples.size)
            track.play()
            Thread {
                Thread.sleep(samples.size * 1000L / sampleRate + 80)
                track.stop()
                track.release()
            }.start()
        } catch (_: Exception) {
        }
    }

    private fun tone(freq: Float, durMs: Int, vol: Float = 0.5f) {
        val n = sampleRate * durMs / 1000
        val samples = ShortArray(n)
        var phase = 0.0
        for (i in 0 until n) {
            phase += 2 * PI * freq / sampleRate
            val attack = min(1f, i.toFloat() / (n * 0.05f))
            val release = min(1f, (n - i).toFloat() / (n * 0.30f))
            val env = min(attack, release)
            samples[i] = (wave(phase).toFloat() * env * vol * Short.MAX_VALUE).toInt().toShort()
        }
        play(samples)
    }

    private fun sweep(from: Float, to: Float, durMs: Int, vol: Float = 0.5f) {
        val n = sampleRate * durMs / 1000
        val samples = ShortArray(n)
        var phase = 0.0
        for (i in 0 until n) {
            val frac = i.toFloat() / n
            val f = from + (to - from) * frac
            phase += 2 * PI * f / sampleRate
            val attack = min(1f, i.toFloat() / (n * 0.05f))
            val release = min(1f, (n - i).toFloat() / (n * 0.30f))
            val env = min(attack, release)
            samples[i] = (wave(phase).toFloat() * env * vol * Short.MAX_VALUE).toInt().toShort()
        }
        play(samples)
    }

    // ---- 音效 ----
    fun jump() = sweep(380f, 820f, 130, 0.45f)

    fun collect() = tone(988f, 70, 0.45f)

    fun grow() = sweep(500f, 1200f, 180, 0.45f)

    fun shrink() = sweep(700f, 250f, 180, 0.45f)

    fun sparkle() {
        Thread {
            tone(880f, 55, 0.42f)
            Thread.sleep(55)
            tone(1318f, 65, 0.42f)
            Thread.sleep(60)
            tone(1760f, 85, 0.42f)
        }.start()
    }

    fun death() = sweep(650f, 170f, 260, 0.5f)

    fun levelWin() {
        val notes = floatArrayOf(523f, 659f, 784f, 1047f)
        notes.forEachIndexed { i, f ->
            Thread { Thread.sleep(i * 95L); tone(f, 130, 0.45f) }.start()
        }
    }

    // ---- 背景音乐 ----
    @Volatile private var musicOn = false
    private var musicThread: Thread? = null
    private var melodyPcm: ShortArray? = null

    // 欢快的主题旋律（C 大调，A/B 两段，每段一句上行一句下行），约 7 秒循环
    private val melody = listOf(
        // A 段
        523f to 180, 659f to 180, 784f to 180, 659f to 180,
        880f to 180, 784f to 180, 659f to 180, 523f to 180,
        659f to 180, 784f to 180, 880f to 180, 1047f to 180,
        784f to 180, 880f to 180, 784f to 180, 659f to 360,
        // B 段
        587f to 180, 659f to 180, 784f to 180, 659f to 180,
        880f to 180, 784f to 180, 659f to 180, 587f to 180,
        523f to 180, 659f to 180, 784f to 180, 880f to 180,
        784f to 180, 659f to 180, 523f to 360, 440f to 360,
    )

    fun startMusic() {
        if (musicOn) return
        musicOn = true
        musicThread = Thread {
            try {
                val pcm = melodyPcm ?: buildMelodyPcm().also { melodyPcm = it }
                val track = AudioTrack(
                    AudioManager.STREAM_MUSIC, sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    pcm.size * 2, AudioTrack.MODE_STREAM
                )
                track.play()
                val chunk = sampleRate / 20   // 约 50ms 一段，快速响应停止
                var pos = 0
                while (musicOn) {
                    val len = min(chunk, pcm.size - pos)
                    track.write(pcm, pos, len)
                    pos += len
                    if (pos >= pcm.size) pos = 0
                }
                track.stop()
                track.release()
            } catch (_: Exception) {
            }
        }
        musicThread!!.start()
    }

    fun stopMusic() {
        musicOn = false
        try {
            musicThread?.interrupt()
        } catch (_: Exception) {
        }
        musicThread = null
    }

    private fun buildMelodyPcm(): ShortArray {
        val total = melody.sumOf { sampleRate * it.second / 1000 }
        val buf = ShortArray(total)
        var idx = 0
        for ((freq, dur) in melody) {
            val n = sampleRate * dur / 1000
            var phase = 0.0
            for (i in 0 until n) {
                phase += 2 * PI * freq / sampleRate
                val attack = min(1f, i.toFloat() / (n * 0.08f))
                val release = min(1f, (n - i).toFloat() / (n * 0.25f))
                val env = min(attack, release)
                val v = wave(phase).toFloat() * env * 0.20f
                buf[idx++] = (v * Short.MAX_VALUE).toInt().toShort()
            }
        }
        return buf
    }
}
