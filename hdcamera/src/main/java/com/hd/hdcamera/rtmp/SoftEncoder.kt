package com.hd.hdcamera.rtmp

/**
 * 软编码器
 * @author whs
 * @date 2021/6/3
 */
class SoftEncoder :Encoder{

    override fun audioEncode(buffer: ByteArray, size: Int) {
        nativeSendAudio(buffer, size)
    }

    override fun videoEncode(buffer: ByteArray, width: Int, height: Int) {
        nativeSendVideo(buffer)
    }

    private external fun nativeSendVideo(buffer: ByteArray)

    private external fun nativeSendAudio(buffer: ByteArray, len: Int)


}