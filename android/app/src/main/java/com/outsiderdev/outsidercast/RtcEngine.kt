package com.outsiderdev.outsidercast

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import org.webrtc.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Rekam layar jadi video H.264/VP8 (encoder hardware) dan kirim ke tiap penonton via WebRTC. */
class RtcEngine(private val ctx: Context, projectionData: Intent, onStopped: () -> Unit) {
    private val egl = EglBase.create()
    private val factory: PeerConnectionFactory
    private val capturer: ScreenCapturerAndroid
    private val helper: SurfaceTextureHelper
    private val source: VideoSource
    val track: VideoTrack
    private val peers = ConcurrentHashMap<Int, PeerConnection>()

    // Layar yang diam tidak menghasilkan frame baru, sehingga penonton yang baru masuk
    // hanya melihat hitam. Pengulang ini mengirim ulang frame terakhir tiap 300 ms.
    private val frameLock = Any()
    private var lastFrame: VideoFrame? = null
    private var lastAt = 0L
    private val tick = android.os.Handler(android.os.HandlerThread("oc-repeat").apply { start() }.looper)
    private val repeater = object : CapturerObserver {
        override fun onCapturerStarted(ok: Boolean) { source.capturerObserver.onCapturerStarted(ok) }
        override fun onCapturerStopped() { source.capturerObserver.onCapturerStopped() }
        override fun onFrameCaptured(f: VideoFrame) {
            synchronized(frameLock) {
                lastFrame?.release(); f.retain(); lastFrame = f; lastAt = System.nanoTime()
            }
            source.capturerObserver.onFrameCaptured(f)
        }
    }
    private val repeatTask = object : Runnable {
        override fun run() {
            synchronized(frameLock) {
                val f = lastFrame
                if (f != null && System.nanoTime() - lastAt > 300_000_000L) {
                    f.buffer.retain()
                    val again = VideoFrame(f.buffer, f.rotation, System.nanoTime())
                    source.capturerObserver.onFrameCaptured(again)
                    again.release()
                }
            }
            tick.postDelayed(this, 300)
        }
    }

    init {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(ctx.applicationContext).createInitializationOptions())
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, false, false))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
        capturer = ScreenCapturerAndroid(projectionData, object : MediaProjection.Callback() {
            override fun onStop() { onStopped() }
        })
        helper = SurfaceTextureHelper.create("oc-capture", egl.eglBaseContext)
        source = factory.createVideoSource(true)
        capturer.initialize(helper, ctx, repeater)
        val (w, h) = size()
        capturer.startCapture(w, h, Cast.quality.fps)
        track = factory.createVideoTrack("screen", source)
        tick.postDelayed(repeatTask, 300)
    }

    private fun size(): Pair<Int, Int> {
        val m = ctx.resources.displayMetrics
        val scale = minOf(1f, Cast.quality.longSide.toFloat() / maxOf(m.widthPixels, m.heightPixels))
        return ((m.widthPixels * scale).toInt() / 2 * 2) to ((m.heightPixels * scale).toInt() / 2 * 2)
    }

    /** Dipanggil saat kualitas diganti atau layar diputar. */
    fun reformat() {
        val (w, h) = size()
        try { capturer.changeCaptureFormat(w, h, Cast.quality.fps) } catch (_: Exception) {}
        peers.values.forEach { applyBitrate(it) }
    }

    fun setPaused(p: Boolean) { track.setEnabled(!p) }

    private fun applyBitrate(pc: PeerConnection) {
        val n = maxOf(1, peers.size)
        val perViewer = minOf(Cast.quality.bitrate, maxOf(300_000, 20_000_000 / n))
        pc.senders.forEach { s ->
            if (s.track()?.kind() != "video") return@forEach
            val p = s.parameters
            p.encodings.forEach { e -> e.maxBitrateBps = perViewer; e.maxFramerate = Cast.quality.fps }
            p.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
            s.parameters = p
        }
    }

    /** Terima offer dari browser penonton, kembalikan answer (ICE sudah lengkap, tanpa trickle). */
    fun answer(offerSdp: String, name: String): String? {
        val id = Cast.addViewer(name)
        val gathered = CountDownLatch(1)
        val cfg = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
        }
        val pc = factory.createPeerConnection(cfg, object : PeerConnection.Observer {
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) { if (s == PeerConnection.IceGatheringState.COMPLETE) gathered.countDown() }
            override fun onConnectionChange(s: PeerConnection.PeerConnectionState) {
                if (s == PeerConnection.PeerConnectionState.FAILED || s == PeerConnection.PeerConnectionState.CLOSED) drop(id)
            }
            override fun onSignalingChange(s: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) {}
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceCandidate(c: IceCandidate) {}
            override fun onIceCandidatesRemoved(c: Array<out IceCandidate>) {}
            override fun onAddStream(s: MediaStream) {}
            override fun onRemoveStream(s: MediaStream) {}
            override fun onDataChannel(d: DataChannel) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(r: RtpReceiver, s: Array<out MediaStream>) {}
        }) ?: run { Cast.viewers.remove(id); return null }
        peers[id] = pc

        if (!await { cb -> pc.setRemoteDescription(cb, SessionDescription(SessionDescription.Type.OFFER, offerSdp)) }) { drop(id); return null }
        // Isi transceiver video milik penonton (recvonly) dengan track layar
        pc.transceivers.firstOrNull { it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO }?.let {
            it.direction = RtpTransceiver.RtpTransceiverDirection.SEND_ONLY
            it.sender.setTrack(track, false)
            it.sender.setStreams(listOf("cast"))
        }
        var answer: SessionDescription? = null
        val made = CountDownLatch(1)
        pc.createAnswer(object : SdpAdapter() {
            override fun onCreateSuccess(d: SessionDescription) { answer = d; made.countDown() }
            override fun onCreateFailure(e: String?) { made.countDown() }
        }, MediaConstraints())
        made.await(5, TimeUnit.SECONDS)
        val a = answer ?: run { drop(id); return null }
        if (!await { cb -> pc.setLocalDescription(cb, a) }) { drop(id); return null }
        gathered.await(3, TimeUnit.SECONDS)
        applyBitrate(pc)
        peers.values.forEach { applyBitrate(it) }
        return pc.localDescription?.description
    }

    private fun await(op: (SdpObserver) -> Unit): Boolean {
        val l = CountDownLatch(1); var ok = false
        op(object : SdpAdapter() {
            override fun onSetSuccess() { ok = true; l.countDown() }
            override fun onSetFailure(e: String?) { l.countDown() }
        })
        l.await(5, TimeUnit.SECONDS); return ok
    }

    private fun drop(id: Int) {
        Cast.viewers.remove(id)
        peers.remove(id)?.let { pc -> Thread { try { pc.dispose() } catch (_: Exception) {} }.start() }
    }

    fun release() {
        peers.keys.toList().forEach { id -> Cast.viewers.remove(id); try { peers.remove(id)?.close() } catch (_: Exception) {} }
        tick.removeCallbacksAndMessages(null)
        tick.looper.quitSafely()
        synchronized(frameLock) { lastFrame?.release(); lastFrame = null }
        try { capturer.stopCapture() } catch (_: Exception) {}
        try { capturer.dispose() } catch (_: Exception) {}
        try { track.dispose() } catch (_: Exception) {}
        try { source.dispose() } catch (_: Exception) {}
        try { helper.dispose() } catch (_: Exception) {}
        try { factory.dispose() } catch (_: Exception) {}
        try { egl.release() } catch (_: Exception) {}
    }

    private open class SdpAdapter : SdpObserver {
        override fun onCreateSuccess(d: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(e: String?) {}
        override fun onSetFailure(e: String?) {}
    }
}
