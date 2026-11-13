package com.example.dualcam

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.ParcelFileDescriptor
import android.util.Size
import android.view.Surface

/** Codificador H.264 que recibe cuadros por una Surface y los guarda en MP4. */
class VideoEncoder(val width: Int, val height: Int, private val pfd: ParcelFileDescriptor) {

    private val codec: MediaCodec = MediaCodec.createEncoderByType(MIME)
    val inputSurface: Surface
    private val muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private var track = -1
    private var muxerStarted = false
    private val info = MediaCodec.BufferInfo()

    init {
        val format = MediaFormat.createVideoFormat(MIME, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, (width * height * 30 * 0.25).toInt())
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = codec.createInputSurface()
        codec.start()
    }

    fun drain(endOfStream: Boolean) {
        if (endOfStream) codec.signalEndOfInputStream()
        var tries = 0
        while (true) {
            val idx = codec.dequeueOutputBuffer(info, if (endOfStream) 10_000L else 0L)
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream || ++tries > 300) return
                }
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!muxerStarted) {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                }
                idx >= 0 -> {
                    val buf = codec.getOutputBuffer(idx)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (buf != null && info.size > 0 && muxerStarted) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        muxer.writeSampleData(track, buf, info)
                    }
                    codec.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    fun release() {
        try { codec.stop() } catch (_: Exception) {}
        codec.release()
        inputSurface.release()
        try { if (muxerStarted) muxer.stop() } catch (_: Exception) {}
        muxer.release()
        try { pfd.close() } catch (_: Exception) {}
    }

    companion object {
        private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC

        /** Tamaño por ojo (apaisado 4:3) que el codificador acepta en lado a lado. */
        fun pickEyeSize(): Size {
            val candidates = listOf(Size(1440, 1080), Size(1280, 960), Size(960, 720), Size(640, 480))
            return try {
                val c = MediaCodec.createEncoderByType(MIME)
                val caps = c.codecInfo.getCapabilitiesForType(MIME).videoCapabilities
                c.release()
                candidates.firstOrNull { caps.isSizeSupported(it.width * 2, it.height) }
                    ?: candidates.last()
            } catch (e: Exception) {
                Size(960, 720)
            }
        }
    }
}
