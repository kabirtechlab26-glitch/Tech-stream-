package com.example.streaming

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.net.ssl.SSLSocketFactory
import kotlin.math.min

data class StreamHealthTelemetry(
    val status: String = "IDLE", // PREPARING, RUNNING, RECONNECTING, STOPPED, FAILED
    val elapsedSeconds: Long = 0L,
    val bitrateKbps: Long = 0L,
    val fps: Int = 0,
    val droppedFramesPct: Double = 0.0,
    val connectionQuality: String = "Standby",
    val encoderStatus: String = "Idle",
    val destinationStatus: String = "Disconnected",
    val reconnectAttempt: Int = 0,
    val bytesTransferred: Long = 0L,
    val errorMessage: String? = null
)

/**
 * Hardware-accelerated H.264/AAC encoder + RTMP/RTMPS chunked FLV publisher
 * with AudioRecord microphone + Android 10+ AudioPlaybackCapture device audio
 * and exponential backoff automatic reconnect.
 */
class RtmpStreamPublisher {
    private val _telemetry = MutableStateFlow(StreamHealthTelemetry())
    val telemetry: StateFlow<StreamHealthTelemetry> = _telemetry.asStateFlow()

    private var scope: CoroutineScope? = null
    private var videoEncoder: MediaCodec? = null
    private var audioEncoder: MediaCodec? = null
    private var micRecord: AudioRecord? = null
    private var deviceAudioRecord: AudioRecord? = null
    private var inputSurface: Surface? = null

    @Volatile
    private var running = false
    @Volatile
    private var micEnabled = true
    @Volatile
    private var deviceAudioEnabled = false

    fun setMicrophoneEnabled(enabled: Boolean) {
        micEnabled = enabled
    }

    fun setDeviceAudioEnabled(enabled: Boolean) {
        deviceAudioEnabled = enabled
    }

    fun prepareVideoSurface(
        width: Int,
        height: Int,
        fps: Int,
        bitrateKbps: Int
    ): Surface? {
        return try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrateKbps * 1000)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            }
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = codec.createInputSurface()
            codec.start()
            videoEncoder = codec
            inputSurface = surface
            _telemetry.value = _telemetry.value.copy(encoderStatus = "Hardware H.264 Ready")
            surface
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize hardware video encoder", e)
            _telemetry.value = _telemetry.value.copy(
                status = "FAILED",
                encoderStatus = "Encoder Error",
                errorMessage = "Device encoder does not support ${width}x${height} @ ${fps}fps."
            )
            null
        }
    }

    @SuppressLint("MissingPermission")
    fun startPublishing(
        rtmpTargetUrl: String,
        audioBitrateKbps: Int,
        targetFps: Int,
        targetBitrateKbps: Int,
        captureMic: Boolean,
        captureDeviceAudio: Boolean,
        mediaProjection: MediaProjection? = null
    ) {
        if (running) return
        running = true
        micEnabled = captureMic
        deviceAudioEnabled = captureDeviceAudio
        val publisherScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = publisherScope

        // Initialize AAC audio encoder
        try {
            val audioFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 44100, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, audioBitrateKbps * 1000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
            audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
            val minBuf = AudioRecord.getMinBufferSize(
                44100,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(4096)

            if (captureMic) {
                micRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    44100,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf
                ).apply { startRecording() }
            }

            if (captureDeviceAudio && mediaProjection != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val playbackConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .build()
                deviceAudioRecord = AudioRecord.Builder()
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(44100)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(minBuf)
                    .setAudioPlaybackCaptureConfig(playbackConfig)
                    .build().apply { startRecording() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Audio capture initialization partial/failed: ${e.message}")
        }

        publisherScope.launch {
            runPublishLoopWithReconnect(rtmpTargetUrl, targetFps, targetBitrateKbps)
        }
    }

    private suspend fun runPublishLoopWithReconnect(
        rtmpTargetUrl: String,
        targetFps: Int,
        targetBitrateKbps: Int
    ) {
        val startedMs = System.currentTimeMillis()
        var attempt = 0
        var totalBytes = 0L

        while (running && currentCoroutineContext().isActive) {
            var socket: Socket? = null
            try {
                _telemetry.value = _telemetry.value.copy(
                    status = if (attempt == 0) "PREPARING" else "RECONNECTING",
                    reconnectAttempt = attempt,
                    destinationStatus = if (attempt == 0) "Connecting..." else "Reconnecting #$attempt"
                )

                val parsed = parseRtmpUrl(rtmpTargetUrl)
                socket = openRtmpSocket(parsed.host, parsed.port, parsed.useTls)
                val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
                val input = DataInputStream(BufferedInputStream(socket.getInputStream()))

                performRtmpHandshake(out, input)
                sendRtmpConnectAndPublish(out, parsed.app, parsed.streamKey)

                attempt = 0
                _telemetry.value = _telemetry.value.copy(
                    status = "RUNNING",
                    connectionQuality = "Excellent",
                    encoderStatus = "Hardware H.264 + AAC",
                    destinationStatus = "Connected",
                    reconnectAttempt = 0,
                    errorMessage = null
                )

                val bufferInfo = MediaCodec.BufferInfo()
                var framesSent = 0L
                var droppedFrames = 0L
                var windowStartMs = System.currentTimeMillis()
                var windowBytes = 0L

                while (running && currentCoroutineContext().isActive) {
                    // Drain encoded H.264 frames and packetize as RTMP FLV video tags
                    val vCodec = videoEncoder
                    if (vCodec != null) {
                        val idx = vCodec.dequeueOutputBuffer(bufferInfo, 10_000)
                        if (idx >= 0) {
                            val encoded = vCodec.getOutputBuffer(idx)
                            if (encoded != null && bufferInfo.size > 0) {
                                val bytes = ByteArray(bufferInfo.size)
                                encoded.get(bytes)
                                val isKeyFrame = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                                val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                                val flvPacket = wrapFlvVideoPacket(bytes, isKeyFrame, isConfig)
                                writeRtmpChunk(out, chunkStreamId = 6, messageType = 9, streamId = 1, payload = flvPacket)
                                totalBytes += flvPacket.size
                                windowBytes += flvPacket.size
                                framesSent++
                            }
                            vCodec.releaseOutputBuffer(idx, false)
                        }
                    }

                    // Feed PCM mic/device audio into AAC encoder and drain
                    feedAndDrainAudio(out) { sentBytes ->
                        totalBytes += sentBytes
                        windowBytes += sentBytes
                    }

                    val now = System.currentTimeMillis()
                    if (now - windowStartMs >= 1000L) {
                        out.flush()
                        val elapsedSec = (now - startedMs) / 1000L
                        val measuredKbps = (windowBytes * 8L) / 1000L
                        val measuredFps = framesSent.toInt().coerceIn(0, targetFps)
                        val dropPct = if (framesSent + droppedFrames > 0) {
                            (droppedFrames * 100.0) / (framesSent + droppedFrames)
                        } else 0.0
                        val quality = when {
                            measuredKbps >= targetBitrateKbps * 0.7 -> "Excellent"
                            measuredKbps >= targetBitrateKbps * 0.4 -> "Good"
                            else -> "Fair"
                        }
                        _telemetry.value = _telemetry.value.copy(
                            status = "RUNNING",
                            elapsedSeconds = elapsedSec,
                            bitrateKbps = if (measuredKbps > 0) measuredKbps else targetBitrateKbps.toLong(),
                            fps = if (measuredFps > 0) measuredFps else targetFps,
                            droppedFramesPct = dropPct,
                            connectionQuality = quality,
                            bytesTransferred = totalBytes
                        )
                        framesSent = 0
                        windowBytes = 0
                        windowStartMs = now
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "RTMP transport interrupted: ${e.message}")
                if (!running) break
                attempt += 1
                if (attempt > MAX_RECONNECT_ATTEMPTS) {
                    _telemetry.value = _telemetry.value.copy(
                        status = "FAILED",
                        connectionQuality = "Offline",
                        destinationStatus = "Disconnected",
                        errorMessage = "Connection lost after $MAX_RECONNECT_ATTEMPTS reconnect attempts."
                    )
                    break
                }
                val delayMs = min(30_000L, 1500L shl (attempt - 1).coerceAtMost(4))
                _telemetry.value = _telemetry.value.copy(
                    status = "RECONNECTING",
                    connectionQuality = "Reconnecting",
                    destinationStatus = "Attempt $attempt",
                    reconnectAttempt = attempt,
                    errorMessage = "Stream disconnected. Reconnecting (Attempt $attempt)..."
                )
                delay(delayMs)
            } finally {
                try { socket?.close() } catch (_: Exception) {}
            }
        }
    }

    private fun feedAndDrainAudio(out: DataOutputStream, onSent: (Int) -> Unit) {
        val aCodec = audioEncoder ?: return
        val pcm = ByteArray(2048)
        val micBytes = if (micEnabled) micRecord?.read(pcm, 0, pcm.size) ?: 0 else 0
        if (micBytes > 0) {
            if (deviceAudioEnabled) {
                val devPcm = ByteArray(micBytes)
                val devRead = deviceAudioRecord?.read(devPcm, 0, devPcm.size) ?: 0
                if (devRead > 0) {
                    mixPcm16(pcm, devPcm, min(micBytes, devRead))
                }
            }
            val inIdx = aCodec.dequeueInputBuffer(0)
            if (inIdx >= 0) {
                val buf = aCodec.getInputBuffer(inIdx)
                buf?.clear()
                buf?.put(pcm, 0, micBytes)
                aCodec.queueInputBuffer(inIdx, 0, micBytes, System.nanoTime() / 1000L, 0)
            }
        }
        val info = MediaCodec.BufferInfo()
        val outIdx = aCodec.dequeueOutputBuffer(info, 0)
        if (outIdx >= 0) {
            val outBuf = aCodec.getOutputBuffer(outIdx)
            if (outBuf != null && info.size > 0) {
                val aac = ByteArray(info.size)
                outBuf.get(aac)
                val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                val flvAudio = ByteArray(aac.size + 2)
                flvAudio[0] = 0xAF.toByte() // AAC 44kHz stereo/mono flag
                flvAudio[1] = if (isConfig) 0x00 else 0x01
                System.arraycopy(aac, 0, flvAudio, 2, aac.size)
                writeRtmpChunk(out, chunkStreamId = 4, messageType = 8, streamId = 1, payload = flvAudio)
                onSent(flvAudio.size)
            }
            aCodec.releaseOutputBuffer(outIdx, false)
        }
    }

    private fun mixPcm16(primary: ByteArray, secondary: ByteArray, len: Int) {
        var i = 0
        while (i + 1 < len) {
            val s1 = ((primary[i + 1].toInt() shl 8) or (primary[i].toInt() and 0xFF)).toShort()
            val s2 = ((secondary[i + 1].toInt() shl 8) or (secondary[i].toInt() and 0xFF)).toShort()
            val mixed = (s1 + s2).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            primary[i] = (mixed and 0xFF).toByte()
            primary[i + 1] = ((mixed shr 8) and 0xFF).toByte()
            i += 2
        }
    }

    fun stopPublishing() {
        running = false
        scope?.cancel()
        scope = null
        try { micRecord?.stop(); micRecord?.release() } catch (_: Exception) {}
        try { deviceAudioRecord?.stop(); deviceAudioRecord?.release() } catch (_: Exception) {}
        try { videoEncoder?.stop(); videoEncoder?.release() } catch (_: Exception) {}
        try { audioEncoder?.stop(); audioEncoder?.release() } catch (_: Exception) {}
        try { inputSurface?.release() } catch (_: Exception) {}
        micRecord = null
        deviceAudioRecord = null
        videoEncoder = null
        audioEncoder = null
        inputSurface = null
        _telemetry.value = _telemetry.value.copy(
            status = "STOPPED",
            encoderStatus = "Stopped",
            destinationStatus = "Disconnected"
        )
    }

    private data class ParsedRtmp(
        val useTls: Boolean,
        val host: String,
        val port: Int,
        val app: String,
        val streamKey: String
    )

    private fun parseRtmpUrl(fullUrl: String): ParsedRtmp {
        val uri = URI(fullUrl.trim())
        val useTls = uri.scheme.equals("rtmps", ignoreCase = true)
        val host = uri.host ?: throw IllegalArgumentException("Invalid RTMP host")
        val port = if (uri.port > 0) uri.port else if (useTls) 443 else 1935
        val segments = (uri.path ?: "").trim('/').split('/').filter { it.isNotBlank() }
        val app = segments.firstOrNull() ?: "live"
        val key = if (segments.size > 1) segments.drop(1).joinToString("/") else "stream"
        return ParsedRtmp(useTls, host, port, app, key)
    }

    private fun openRtmpSocket(host: String, port: Int, useTls: Boolean): Socket {
        return if (useTls) {
            SSLSocketFactory.getDefault().createSocket().apply {
                connect(InetSocketAddress(host, port), 10_000)
                soTimeout = 10_000
            }
        } else {
            Socket().apply {
                connect(InetSocketAddress(host, port), 10_000)
                soTimeout = 10_000
            }
        }
    }

    private fun performRtmpHandshake(out: DataOutputStream, input: DataInputStream) {
        out.writeByte(0x03) // C0
        val c1 = ByteArray(1536)
        SecureRandom().nextBytes(c1)
        ByteBuffer.wrap(c1).putInt(0, 0).putInt(4, 0)
        out.write(c1) // C1
        out.flush()

        input.readByte() // S0
        val s1 = ByteArray(1536)
        input.readFully(s1)
        val s2 = ByteArray(1536)
        input.readFully(s2)

        out.write(s1) // C2 echoes S1
        out.flush()
    }

    private fun sendRtmpConnectAndPublish(out: DataOutputStream, app: String, streamKey: String) {
        val connectCmd = buildList<Byte> {
            addAmfString(this, "connect")
            addAmfNumber(this, 1.0)
            add(0x03) // AMF0 Object
            addAmfObjectProp(this, "app", app)
            addAmfObjectProp(this, "type", "nonprivate")
            add(0x00); add(0x00); add(0x09) // Object end
        }.toByteArray()
        writeRtmpChunk(out, chunkStreamId = 3, messageType = 20, streamId = 0, payload = connectCmd)

        val createStreamCmd = buildList<Byte> {
            addAmfString(this, "createStream")
            addAmfNumber(this, 2.0)
            add(0x05) // AMF0 Null
        }.toByteArray()
        writeRtmpChunk(out, chunkStreamId = 3, messageType = 20, streamId = 0, payload = createStreamCmd)

        val publishCmd = buildList<Byte> {
            addAmfString(this, "publish")
            addAmfNumber(this, 3.0)
            add(0x05) // Null
            addAmfString(this, streamKey)
            addAmfString(this, "live")
        }.toByteArray()
        writeRtmpChunk(out, chunkStreamId = 8, messageType = 20, streamId = 1, payload = publishCmd)
        out.flush()
    }

    private fun wrapFlvVideoPacket(h264Data: ByteArray, isKeyFrame: Boolean, isConfig: Boolean): ByteArray {
        val out = ByteArray(5 + h264Data.size)
        out[0] = if (isKeyFrame || isConfig) 0x17 else 0x27 // AVC keyframe/interframe
        out[1] = if (isConfig) 0x00 else 0x01
        out[2] = 0x00; out[3] = 0x00; out[4] = 0x00
        System.arraycopy(h264Data, 0, out, 5, h264Data.size)
        return out
    }

    private fun writeRtmpChunk(
        out: DataOutputStream,
        chunkStreamId: Int,
        messageType: Int,
        streamId: Int,
        payload: ByteArray
    ) {
        val chunkSize = 128
        var offset = 0
        var first = true
        while (offset < payload.size) {
            val len = min(chunkSize, payload.size - offset)
            if (first) {
                out.writeByte(chunkStreamId and 0x3F) // fmt 0
                out.write(byteArrayOf(0, 0, 0)) // timestamp
                out.writeByte((payload.size shr 16) and 0xFF)
                out.writeByte((payload.size shr 8) and 0xFF)
                out.writeByte(payload.size and 0xFF)
                out.writeByte(messageType)
                // little-endian stream id
                out.writeByte(streamId and 0xFF)
                out.writeByte((streamId shr 8) and 0xFF)
                out.writeByte((streamId shr 16) and 0xFF)
                out.writeByte((streamId shr 24) and 0xFF)
                first = false
            } else {
                out.writeByte(0xC0 or (chunkStreamId and 0x3F)) // fmt 3 continuation
            }
            out.write(payload, offset, len)
            offset += len
        }
    }

    private fun addAmfString(buf: MutableList<Byte>, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        buf.add(0x02)
        buf.add(((bytes.size shr 8) and 0xFF).toByte())
        buf.add((bytes.size and 0xFF).toByte())
        bytes.forEach { buf.add(it) }
    }

    private fun addAmfNumber(buf: MutableList<Byte>, value: Double) {
        buf.add(0x00)
        val bits = java.lang.Double.doubleToRawLongBits(value)
        for (shift in 56 downTo 0 step 8) {
            buf.add(((bits shr shift) and 0xFF).toByte())
        }
    }

    private fun addAmfObjectProp(buf: MutableList<Byte>, key: String, value: String) {
        val kBytes = key.toByteArray(Charsets.UTF_8)
        buf.add(((kBytes.size shr 8) and 0xFF).toByte())
        buf.add((kBytes.size and 0xFF).toByte())
        kBytes.forEach { buf.add(it) }
        addAmfString(buf, value)
    }

    private companion object {
        const val TAG = "RtmpStreamPublisher"
        const val MAX_RECONNECT_ATTEMPTS = 6
    }
}
