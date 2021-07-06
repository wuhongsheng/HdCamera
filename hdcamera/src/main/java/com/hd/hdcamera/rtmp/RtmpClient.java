package com.hd.hdcamera.rtmp;

import android.util.Log;

import com.blankj.utilcode.util.ToastUtils;

import org.jetbrains.annotations.NotNull;

import java.io.File;

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

    private int bitRate = 640_000;

    private int audio_bitRate = 44100;

    @Override
    public void audioEncode(@NotNull byte[] buffer, int size) {
        encoder.audioEncode(buffer, size);
    }

    @Override
    public void videoEncode(@NotNull byte[] buffer, int width, int height) {
        encoder.videoEncode(buffer, width, height);
    }

    @Override
    public void releaseResources() {
        if(audioChannel != null){
            audioChannel.release();
            releaseVideoEnc();
            releaseAudioEnc();
            nativeDeInit();
        }
        encoder.releaseResources();
    }

    @Override
    public void start() {
        if(audioChannel != null){
            audioChannel.start();
        }
    }

    @Override
    public void stop() {
        encoder.stop();
    }

    public void encodeStart() {
        encoder.start();
    }


    //编码策略
    public enum EncodeStrategy{
        HARD_ENCODER,
        SOFT_ENCODER
    }

    private Encoder encoder;
    private boolean isConnectd;

    public AudioChannel getAudioChannel() {
        return audioChannel;
    }

    private AudioChannel audioChannel;


    public static final int VFPS = 24;

    public RtmpClient() {
        nativeInit();
    }


    public RtmpClient(EncodeStrategy encodeStrategy) {
        nativeInit();
        //初始化摄像头， 同时 创建编码器
        initVideo(bitRate);
        initAudio(audio_bitRate, 2);
        switch (encodeStrategy){
            case SOFT_ENCODER:
                encoder = new SoftEncoder();
                break;
            case HARD_ENCODER:
                encoder = new HardEncoder(this);
                break;
            default:
                throw new IllegalArgumentException("There's no such strategy yet.");
        }
    }



    public RtmpClient(@NotNull File outputFile) {
        Log.e(TAG,outputFile.getAbsolutePath());
        nativeInit();
        initVideo(bitRate);
        initAudio(audio_bitRate, 2);
        encoder = new HardEncoder(this,outputFile);
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
        isConnectd = true;
        start();
        //connect(url);
    }

    /**
     * JNICall 回调
     * @param isConnect
     */
    private void onPrepare(boolean isConnect) {
        this.isConnectd = isConnect;
        if(isConnect){
            Log.e(TAG, "服务器连接成功==================");
            start();
            Log.e(TAG, "开始直播==================");
            ToastUtils.showShort("服务器连接成功,开始直播");
        }else {
            Log.e(TAG, "服务器连接失败==================");
            ToastUtils.showShort("服务器连接失败");
        }
    }

    public void stopLive() {
        stop();
        if(audioChannel != null){
            audioChannel.stop();
        }
        isConnectd = false;
        disConnect();
        Log.e(TAG, "停止直播==================");
        ToastUtils.showShort("停止直播");
    }

    public void sendVideo(byte[] buffer) {
        encoder.videoEncode(buffer,mWidth,mHeight);
    }

    public void sendAudio(byte[] buffer, int len) {
        encoder.audioEncode(buffer,len);
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
