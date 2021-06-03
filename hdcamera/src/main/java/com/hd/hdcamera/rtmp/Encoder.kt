package com.hd.hdcamera.rtmp

/**
 * 编码器接口
 * @author whs
 * @date 2021/6/3
 */
interface Encoder {
    fun audioEncode(buffer: ByteArray,size:Int)
    fun videoEncode(buffer: ByteArray,width:Int, height:Int)
}