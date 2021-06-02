package com.hd.hdcamera.imageanalysis

import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.hd.hdcamera.rtmp.ImageUtils
import com.hd.hdcamera.rtmp.RtmpClient
import java.nio.ByteBuffer



/**
 * 获取YUV数据
 * @author whs
 * @date 2021/3/22
 */
class RtmpAnalyzer(private val rtmp: RtmpClient) : ImageAnalysis.Analyzer {

    override fun analyze(image: ImageProxy) {
        Log.d("RtmpAnalyzer", "analyze")
        // 开启直播并且已经成功连接服务器才获取i420数据
        if (rtmp.isConnectd) {
            Log.e("RtmpAnalyzer", "rtmpClient.isConnected")
            val bytes = ImageUtils.getBytes(
                image,
                image.imageInfo.rotationDegrees,
                rtmp.width,
                rtmp.height
            )
            rtmp.sendVideo(bytes)
        }
        image.close()
    }
}