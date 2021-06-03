package com.hd.hdcamera.rtmp;

import android.util.Log;
import android.util.Size;

import androidx.annotation.NonNull;

import com.blankj.utilcode.util.ToastUtils;

import org.jetbrains.annotations.NotNull;

/**
 * description
 *
 * @author whs
 * @date 2021/5/31
 */
public class RtmpClient implements Encoder{
    static {
        System.loadLibrary("native-lib");
    }
    private static final String TAG = "---->RtmpClient<----";
    private int mWidth = 480, mHeight = 640;

    @Override
    public void audioEncode(@NotNull byte[] buffer, int size) {
        encoder.audioEncode(buffer, size);
    }

    @Override
    public void videoEncode(@NotNull byte[] buffer, int width, int height) {
        encoder.videoEncode(buffer, width, height);
    }


    //编码策略
    public enum EncodeStrategy{
        HARD_ENCODER,
        SOFT_ENCODER
    }

 /*   public void setEncodeStrategy(EncodeStrategy encodeStrategy) {
        this.encodeStrategy = encodeStrategy;
    }

    private EncodeStrategy encodeStrategy = EncodeStrategy.SOFT_ENCODER;*/

    private Encoder encoder;

    private boolean isConnectd;
    private AudioChannel audioChannel;

    public static final int VFPS = 24;


    public RtmpClient() {
        nativeInit();
    }

    public RtmpClient(EncodeStrategy encodeStrategy) {
        switch (encodeStrategy){
            case SOFT_ENCODER:
                encoder = new SoftEncoder();
                break;
            case HARD_ENCODER:
                encoder = new HardEncoder();
                break;
            default:
                throw new IllegalArgumentException("There's no such strategy yet.");
        }
        nativeInit();
    }



    public void initVideo(int width, int height, int fps, int bitRate) {
        this.mWidth = width;
        this.mHeight = height;
        initVideoEnc(width, height, fps, bitRate);
    }

    public void initVideo(@NonNull Size resolution, int bitRate) {
        initVideoEnc(resolution.getWidth(), resolution.getHeight(), VFPS, bitRate);
    }

    public void initVideo(int bitRate) {
        initVideoEnc(mWidth, mHeight, VFPS, bitRate);
    }


    public void initAudio(int sampleRate, int channels) {
        audioChannel = new AudioChannel(sampleRate, channels, this);
        int inputByteNum = initAudioEnc(sampleRate, channels);
        audioChannel.setInputByteNum(inputByteNum);
    }



    public int getWidth() {
        return mWidth;
    }

    public int getHeight() {
        return mHeight;
    }

    public boolean isConnectd() {
        return isConnectd;
    }

    public void startLive(String url) {
        connect(url);
    }

    /**
     * JNICall 回调
     * @param isConnect
     */
    private void onPrepare(boolean isConnect) {
        this.isConnectd = isConnect;
        if(isConnect){
            Log.e(TAG, "服务器连接成功==================");
            audioChannel.start();
            Log.e(TAG, "开始直播==================");
            ToastUtils.showShort("服务器连接成功,开始直播");
        }else {
            Log.e(TAG, "服务器连接失败==================");
            ToastUtils.showShort("服务器连接失败");
        }
    }

    public void stopLive() {
        isConnectd = false;
        audioChannel.stop();
        disConnect();
        Log.e(TAG, "停止直播==================");
        ToastUtils.showShort("停止直播");
    }

    public void sendVideo(byte[] buffer) {
        nativeSendVideo(buffer);

    }

    public void sendAudio(byte[] buffer, int len) {
        nativeSendAudio(buffer, len);
    }


    public void release() {
        audioChannel.release();
        releaseVideoEnc();
        releaseAudioEnc();
        nativeDeInit();
    }


    private native void connect(String url);

    private native void disConnect();

    private native void nativeInit();

    private native void initVideoEnc(int width, int height, int fps, int bitRate);

    private native void releaseVideoEnc();

    private native void nativeDeInit();

    private native void nativeSendVideo(byte[] buffer);

    private native int initAudioEnc(int sampleRate, int channels);

    private native void releaseAudioEnc();

    private native void nativeSendAudio(byte[] buffer, int len);
}
