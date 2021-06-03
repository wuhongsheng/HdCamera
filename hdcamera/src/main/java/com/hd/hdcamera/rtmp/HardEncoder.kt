package com.hd.hdcamera.rtmp

import android.media.MediaCodec
import java.nio.ByteBuffer

/**
 * 硬编码
 * @author whs
 * @date 2021/6/3
 */
class HardEncoder :Encoder{
    private lateinit var mAudioEncoder: MediaCodec
    private lateinit var mVideoEncoder: MediaCodec


    override fun audioEncode(buffer: ByteArray, size: Int) {
        TODO("Not yet implemented")
    }

    override fun videoEncode(buffer: ByteArray, width: Int, height: Int) {
        TODO("Not yet implemented")
    }


    private fun onProcessedYuvFrame(yuvFrame: ByteArray, pts: Long) {
        val inBuffers: Array<ByteBuffer> = mVideoEncoder.inputBuffers as Array<ByteBuffer>
        val outBuffers: Array<ByteBuffer> = mVideoEncoder.outputBuffers
        val inBufferIndex: Int = mVideoEncoder.dequeueInputBuffer(-1)
        if (inBufferIndex >= 0) {
            val bb = inBuffers[inBufferIndex]
            bb.clear()
            bb.put(yuvFrame, 0, yuvFrame.size)
            mVideoEncoder.queueInputBuffer(inBufferIndex, 0, yuvFrame.size, pts, 0)
        }
        while (true) {
            val vebi = MediaCodec.BufferInfo()
            val outBufferIndex: Int = mVideoEncoder.dequeueOutputBuffer(vebi, 0)
            if (outBufferIndex >= 0) {
                val bb = outBuffers[outBufferIndex]
                onEncodedAnnexbFrame(bb, vebi)
                mVideoEncoder.releaseOutputBuffer(outBufferIndex, false)
            } else {
                break
            }
        }
    }

    // when got encoded h264 es stream.
    private fun onEncodedAnnexbFrame(es: ByteBuffer, bi: MediaCodec.BufferInfo) {
        /*mp4Muxer.writeSampleData(videoMp4Track, es.duplicate(), bi)
        flvMuxer.writeSampleData(videoFlvTrack, es, bi)*/
    }

    // when got encoded aac raw stream.
    private fun onEncodedAacFrame(es: ByteBuffer, bi: MediaCodec.BufferInfo) {
      /*  mp4Muxer.writeSampleData(audioMp4Track, es.duplicate(), bi)
        flvMuxer.writeSampleData(audioFlvTrack, es, bi)*/
    }
}