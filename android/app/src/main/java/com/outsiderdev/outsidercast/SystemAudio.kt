package com.outsiderdev.outsidercast

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import java.nio.ByteBuffer

/**
 * Menangkap suara yang diputar aplikasi di HP (YouTube, video, musik) lewat
 * AudioPlaybackCapture (Android 10+), lalu menimpa buffer mikrofon WebRTC dengan suara itu.
 * Suara mikrofon/ruangan tidak ikut terkirim.
 */
class SystemAudio(private val projection: () -> MediaProjection?) {
    private var rec: AudioRecord? = null
    private var tmp = ByteArray(0)
    @Volatile var failed = false; private set

    @SuppressLint("MissingPermission")
    private fun open(rate: Int, channels: Int): AudioRecord? {
        if (Build.VERSION.SDK_INT < 29 || failed) return null
        val mp = projection() ?: return null
        return try {
            val cfg = AudioPlaybackCaptureConfiguration.Builder(mp)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val chMask = if (channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
            val fmt = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(rate).setChannelMask(chMask).build()
            val min = AudioRecord.getMinBufferSize(rate, chMask, AudioFormat.ENCODING_PCM_16BIT)
            AudioRecord.Builder().setAudioFormat(fmt).setBufferSizeInBytes(maxOf(min, rate * channels * 2 / 5))
                .setAudioPlaybackCaptureConfig(cfg).build().also { it.startRecording() }
        } catch (_: Throwable) { failed = true; null }
    }

    /** Dipanggil WebRTC tiap ±10 ms dengan buffer mikrofon; isinya diganti suara HP. */
    fun fill(sampleRate: Int, channels: Int, buf: ByteBuffer) {
        val r = rec ?: open(sampleRate, channels)?.also { rec = it } ?: run { silence(buf); return }
        val size = buf.capacity()
        if (tmp.size != size) tmp = ByteArray(size)
        val got = try { r.read(tmp, 0, size, AudioRecord.READ_NON_BLOCKING) } catch (_: Throwable) { 0 }
        if (got < size) java.util.Arrays.fill(tmp, maxOf(got, 0), size, 0)
        if (!Cast.audioOn) java.util.Arrays.fill(tmp, 0)
        buf.clear(); buf.put(tmp, 0, size); buf.rewind()
    }

    private fun silence(buf: ByteBuffer) {
        val size = buf.capacity()
        if (tmp.size != size) tmp = ByteArray(size)
        java.util.Arrays.fill(tmp, 0)
        buf.clear(); buf.put(tmp, 0, size); buf.rewind()
    }

    fun release() { try { rec?.stop() } catch (_: Throwable) {}; try { rec?.release() } catch (_: Throwable) {}; rec = null }
}
