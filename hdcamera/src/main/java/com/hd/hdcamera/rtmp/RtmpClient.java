package com.hd.hdcamera.rtmp;

import android.util.Log;

import androidx.lifecycle.LifecycleOwner;

/**
 * description
 *
 * @author whs
 * @date 2021/5/31
 */
public class RtmpClient {

    private static final String TAG = "---->RtmpClient<----";

    static {
        System.loadLibrary("native-lib");
    }

    private final LifecycleOwner lifecycleOwner;
    private int width;
    private int height;
    private boolean isConnectd;
    //private VideoChanel videoChanel;
    private AudioChannel audioChannel;

    public RtmpClient(LifecycleOwner lifecycleOwner) {
        this.lifecycleOwner = lifecycleOwner;
        nativeInit();
    }



    public void initVideo(int width, int height, int fps, int bitRate) {
        this.width = width;
        this.height = height;
        //videoChanel = new VideoChanel(this);
        initVideoEnc(width, height, fps, bitRate);
    }

    public void initAudio(int sampleRate, int channels) {
        audioChannel = new AudioChannel(sampleRate, channels, this);
        int inputByteNum = initAudioEnc(sampleRate, channels);
        audioChannel.setInputByteNum(inputByteNum);
    }

    public void toggleCamera() {
        //videoChanel.toggleCamera();
    }


    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
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
        }else {
            Log.e(TAG, "服务器连接失败==================");
        }
    }

    public void stopLive() {
        isConnectd = false;
        audioChannel.stop();
        disConnect();
        Log.e(TAG, "停止直播==================");
    }

    public void sendVideo(byte[] buffer) {
        nativeSendVideo(buffer);
    }

    public void sendAudio(byte[] buffer, int len) {
        nativeSendAudio(buffer, len);
    }


    public void release() {
        //videoChanel.release();
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
