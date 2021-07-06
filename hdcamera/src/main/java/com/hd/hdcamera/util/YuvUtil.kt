package com.hd.hdcamera.util

import android.graphics.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * description
 * @author whs
 * @date 2021/7/5
 */
class YuvUtil {
    companion object{

        /**
         * yuv 转 bitmap
         */
        fun yuv2Bitmap(buffer: ByteArray, width: Int, height: Int): Bitmap {
            var nv21 = I420ToNV21(buffer,width, height)
            var yuv = YuvImage(nv21, ImageFormat.NV21, width, height, null)
            var stream = ByteArrayOutputStream()
            yuv.compressToJpeg(Rect(0, 0, width, height), 100, stream)
            var bitmap = BitmapFactory.decodeByteArray(stream.toByteArray(),
                0, stream.size())
            stream.close()
            return bitmap
        }


        /**
         * I420转nv21
         */
        fun I420ToNV21(data: ByteArray, width: Int, height: Int): ByteArray? {
            val ret = ByteArray(data.size)
            val total = width * height
            val bufferY = ByteBuffer.wrap(ret, 0, total)
            val bufferVU = ByteBuffer.wrap(ret, total, total / 2)
            bufferY.put(data, 0, total)
            var i = 0
            while (i < total / 4) {
                bufferVU.put(data[i + total + total / 4])
                bufferVU.put(data[total + i])
                i += 1
            }
            return ret
        }

    }
}