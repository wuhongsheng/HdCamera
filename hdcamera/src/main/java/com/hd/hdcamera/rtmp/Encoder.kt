package com.hd.hdcamera.rtmp

/**
 * description
 * @author whs
 * @date 2021/7/12
 */
class Encoder :IEncoder{


    private var encoder: IEncoder? = null

    //编码策略
    enum class EncodeStrategy {
        HARD_ENCODER, SOFT_ENCODER
    }

    fun Encoder(strategy: EncodeStrategy?) {
        setStrategy(strategy)
    }


    // 客户端通过此方法设置不同的策略
    fun setStrategy(strategy: EncodeStrategy?) {
        when (strategy) {
            EncodeStrategy.HARD_ENCODER -> encoder = SoftEncoder()
            EncodeStrategy.SOFT_ENCODER -> encoder = SoftEncoder()
            else -> throw IllegalArgumentException("There's no such strategy yet.")
        }
    }

    override fun start() {
        encoder?.start()
    }

    override fun stop() {
        encoder?.stop()
    }

    override fun audioEncode(buffer: ByteArray, size: Int) {
        encoder?.audioEncode(buffer, size)
    }

    override fun videoEncode(buffer: ByteArray, width: Int, height: Int) {
        encoder?.videoEncode(buffer, width, height)
    }

    override fun releaseResources() {
        encoder?.releaseResources()
    }
}