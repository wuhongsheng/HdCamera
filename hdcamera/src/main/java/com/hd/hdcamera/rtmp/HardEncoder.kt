package com.hd.hdcamera.rtmp

import android.Manifest
import android.annotation.SuppressLint
import android.media.*
import android.media.MediaCodecInfo.CodecCapabilities
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.annotation.GuardedBy
import androidx.annotation.RequiresPermission
import androidx.annotation.UiThread
import androidx.camera.core.VideoCapture
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 硬编码
 * @author whs
 * @date 2021/6/3
 */
class HardEncoder(
    var rtmpClient: RtmpClient
) :Encoder{

    constructor(rtmpClient: RtmpClient, outputFile: File) : this(rtmpClient) {
        this.rtmpClient = rtmpClient
        this.outputFile = outputFile
    }


    private val TAG = "---->HardEncoder<----"
    /** Android preferred mime type for AVC video.  */
    private val VIDEO_MIME_TYPE = "video/avc"
    private val AUDIO_MIME_TYPE = "audio/mp4a-latm"
    /** Amount of time to wait for dequeuing a buffer from the videoEncoder.  */
    private val DEQUE_TIMEOUT_USEC = 10000
    private var mAudioEncoder: MediaCodec? = null
    private var mVideoEncoder: MediaCodec? = null

    private var outputFile:File? = null
    val VFPS = 24
    val VGOP = 48
    val mAudioSampleRate = 44100
    val mAudioChannelCount = 2
    private val bitRate = 640000
    val mAudioBitRate = 64 * 1024 // 64 kbps

    /** audio raw data  */
    private var mAudioRecorder: AudioRecord? = null
    private val mAudioBufferSize = 0

    /** The muxer that writes the encoding data to file.  */
    @GuardedBy("mMuxerLock")
    private var mMuxer: MediaMuxer? = null
    private var mMuxerStarted = false
    /** The index of the video track used by the muxer.  */
    private var mVideoTrackIndex = 0
    /** The index of the audio track used by the muxer.  */
    private var mAudioTrackIndex = 0

    /** Thread on which all encoding occurs.  */
    private val mVideoHandlerThread = HandlerThread(TAG + "video encoding thread")
    private var mVideoHandler: Handler? = null

    /** Thread on which audio encoding occurs.  */
    private val mAudioHandlerThread = HandlerThread(TAG + "audio encoding thread")
    private var mAudioHandler: Handler? = null


    private val mVideoBufferInfo = MediaCodec.BufferInfo()
    private val mMuxerLock = Any()
    private val mEndOfVideoStreamSignal = AtomicBoolean(true)
    private val mEndOfAudioStreamSignal = AtomicBoolean(true)
    private val mEndOfAudioVideoSignal = AtomicBoolean(true)
    private val mAudioBufferInfo = MediaCodec.BufferInfo()

    /** For record the first sample written time.  */
    private val mIsFirstVideoSampleWrite = AtomicBoolean(false)
    private val mIsFirstAudioSampleWrite = AtomicBoolean(false)

    init {
        Log.e(TAG,"init")
        // video thread start
        mVideoHandlerThread.start()
        mVideoHandler = Handler(mVideoHandlerThread.looper)
        // audio thread start
        mAudioHandlerThread.start()
        mAudioHandler = Handler(mAudioHandlerThread.looper)
        setupEncoder()
    }

    override fun audioEncode(buffer: ByteArray, size: Int) {
        try {
            // audio encoder start
            Log.i(TAG, "audioEncoder start")
            mAudioEncoder?.start()
        } catch (e: java.lang.IllegalStateException) {
            Log.i(TAG, "Audio encoder start fail", e)
            return
        }

        try {
            synchronized(mMuxerLock) {
                mMuxer = initMediaMuxer()
                //mMuxer!!.setOrientationHint(getRelativeRotation(attachedCamera))
            }
        } catch (e: IOException) {
            Log.i(TAG, "MediaMuxer creation failed! $e")
            return
        }

        mAudioHandler?.post { audioEncode() }
    }


    @SuppressLint("UnsafeNewApiCall")
    @Throws(IOException::class)
    private fun initMediaMuxer(): MediaMuxer {
        val mediaMuxer: MediaMuxer
        if (outputFile != null) {
            //mSavedVideoUri = Uri.fromFile(outputFileOptions!!.getFile())
            mediaMuxer = MediaMuxer(
                outputFile!!.absolutePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            )
        } else {
            throw IllegalArgumentException(
                "The OutputFileOptions should assign before recording"
            )
        }
        return mediaMuxer
    }

    override fun videoEncode(buffer: ByteArray, width: Int, height: Int) {
        try {
            // video encoder start
            Log.i(TAG, "videoEncoder start")
            mVideoEncoder?.start()

        } catch (e: java.lang.IllegalStateException) {
            Log.i(TAG, "Video encoder start fail", e)
            return
        }
        mVideoHandler?.post {
            val errorOccurred: Boolean = videoEncode()
            if (!errorOccurred) {
                Log.i(TAG, "videoEncode complete")
            }
        }
    }


    override fun releaseResources() {
        mVideoHandlerThread.quitSafely()
        // audio encoder release
        mAudioHandlerThread.quitSafely()
        if (mAudioEncoder != null) {
            mAudioEncoder?.release()
            mAudioEncoder = null
        }
        if (mAudioRecorder != null) {
            mAudioRecorder!!.release()
            mAudioRecorder = null
        }
    }


    /**
     * Encoding which runs indefinitely until end of stream is signaled. This should not run on the
     * main thread otherwise it will cause the application to block.
     *
     * @return returns `true` if an error condition occurred, otherwise returns `false`
     */
    fun videoEncode(): Boolean {
        // Main encoding loop. Exits on end of stream.
        var errorOccurred = false
        var videoEos = false
        while (!videoEos && !errorOccurred) {
            // Check for end of stream from main thread
            if (mEndOfVideoStreamSignal.get()) {
                mVideoEncoder?.signalEndOfInputStream()
                mEndOfVideoStreamSignal.set(false)
            }

            // Deque buffer to check for processing step
            val outputBufferId = mVideoEncoder?.dequeueOutputBuffer(
                mVideoBufferInfo,
                DEQUE_TIMEOUT_USEC.toLong()
            )
            when (outputBufferId) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (mMuxerStarted) {
                        Log.i(TAG, "Unexpected change in video encoding format.")
                        errorOccurred = true
                    }
                    synchronized(mMuxerLock) {
                        mVideoTrackIndex = mMuxer?.addTrack(mVideoEncoder!!.outputFormat)!!
                        if (mAudioTrackIndex >= 0 && mVideoTrackIndex >= 0) {
                            mMuxerStarted = true
                            Log.i(TAG, "media mMuxer start")
                            mMuxer?.start()
                        }
                    }
                }
                MediaCodec.INFO_TRY_AGAIN_LATER -> {
                }
                else -> videoEos = writeVideoEncodedBuffer(outputBufferId!!)
            }
        }
        try {
            Log.i(TAG, "videoEncoder stop")
            mVideoEncoder?.stop()
        } catch (e: java.lang.IllegalStateException) {
            Log.i(TAG, "Video encoder stop failed!")
            errorOccurred = true
        }
        try {
            // new MediaMuxer instance required for each new file written, and release current one.
            synchronized(mMuxerLock) {
                if (mMuxer != null) {
                    if (mMuxerStarted) {
                        mMuxer?.stop()
                    }
                    mMuxer?.release()
                    mMuxer = null
                }
            }
        } catch (e: java.lang.IllegalStateException) {
            //videoSavedCallback.onError(VideoCapture.ERROR_MUXER, "Muxer stop failed!", e)
            Log.i(TAG, "Muxer stop failed!")
            errorOccurred = true
        }

        mMuxerStarted = false

        // notify the UI thread that the video recording has finished
        mEndOfAudioVideoSignal.set(true)
        Log.i(TAG, "Video encode thread end.")
        return errorOccurred
    }


    fun audioEncode(): Boolean {
        // Audio encoding loop. Exits on end of stream.
        var audioEos = false
        var outIndex: Int
        var lastAudioTimestamp: Long = 0
        while (!audioEos) {
            // Check for end of stream from main thread
            if (mEndOfAudioStreamSignal.get()) {
                mEndOfAudioStreamSignal.set(false)
            }

            // get audio deque input buffer
            if (mAudioEncoder != null && mAudioRecorder != null) {
                val index = mAudioEncoder?.dequeueInputBuffer(-1)!!
                if (index >= 0) {
                    val buffer = getInputBuffer(mAudioEncoder!!, index)
                    buffer!!.clear()
                    val length = mAudioRecorder!!.read(buffer, mAudioBufferSize)
                    if (length > 0) {
                        mAudioEncoder?.queueInputBuffer(
                            index,
                            0,
                            length,
                            System.nanoTime() / 1000,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                    }
                }
                // start to dequeue audio output buffer
                do {
                    outIndex = mAudioEncoder?.dequeueOutputBuffer(mAudioBufferInfo, 0)!!
                    when (outIndex) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> synchronized(mMuxerLock) {
                            mAudioTrackIndex = mMuxer?.addTrack(mAudioEncoder?.outputFormat!!)!!
                            if (mAudioTrackIndex >= 0 && mVideoTrackIndex >= 0) {
                                mMuxerStarted = true
                                mMuxer?.start()
                            }
                        }
                        MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        }
                        else ->// Drops out of order audio frame if the frame's earlier than last
                            // frame.
                            if (mAudioBufferInfo.presentationTimeUs > lastAudioTimestamp) {
                                audioEos = writeAudioEncodedBuffer(outIndex)
                                lastAudioTimestamp = mAudioBufferInfo.presentationTimeUs
                            } else {
                                Log.w(
                                    TAG,
                                    "Drops frame, current frame's timestamp "
                                            + mAudioBufferInfo.presentationTimeUs
                                            + " is earlier that last frame "
                                            + lastAudioTimestamp
                                )
                                // Releases this frame from output buffer
                                mAudioEncoder?.releaseOutputBuffer(outIndex, false)
                            }
                    }
                } while (outIndex >= 0 && !audioEos) // end of dequeue output buffer
            }
        } // end of while loop

        // Audio Stop
        try {
            Log.i(TAG, "audioRecorder stop")
            mAudioRecorder?.stop()
        } catch (e: java.lang.IllegalStateException) {
            Log.i(TAG, "Audio recorder stop failed!")
            /* videoSavedCallback.onError(
                 VideoCapture.ERROR_ENCODER, "Audio recorder stop failed!", e
             )*/
        }
        try {
            mAudioEncoder?.stop()
        } catch (e: java.lang.IllegalStateException) {
            Log.i(TAG, "Audio encoder stop failed!")
            /* videoSavedCallback.onError(
                 VideoCapture.ERROR_ENCODER,
                 "Audio encoder stop failed!", e
             )*/
        }
        Log.i(TAG, "Audio encode thread end")
        // Use AtomicBoolean to signal because MediaCodec.signalEndOfInputStream() is not thread
        // safe
        mEndOfVideoStreamSignal.set(true)
        return false
    }


    /** Creates a [MediaFormat] using parameters from the configuration  */
    private fun createMediaFormat(): MediaFormat {
        val format = MediaFormat.createVideoFormat(VIDEO_MIME_TYPE, rtmpClient.width, rtmpClient.height)
        //颜色格式
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, CodecCapabilities.COLOR_FormatSurface)
        //码率
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
        //帧率
        format.setInteger(MediaFormat.KEY_FRAME_RATE, VFPS)
        //I 帧间隔
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, VGOP/VFPS)
        return format
    }

    /** Creates a [MediaFormat] using parameters for audio from the configuration  */
    private fun createAudioMediaFormat(): MediaFormat {
        val format = MediaFormat.createAudioFormat(
            AUDIO_MIME_TYPE, mAudioSampleRate,
            mAudioChannelCount
        )
        format.setInteger(
            MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC
        )
        format.setInteger(MediaFormat.KEY_BIT_RATE, mAudioBitRate)
        return format
    }


    /**
     * Setup the [MediaCodec] for encoding video from a camera [Surface] and encoding
     * audio from selected audio source.
     */
    @UiThread
    /* synthetic accessor */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun setupEncoder() {
        Log.e(TAG,"setupEncoder")
        try
        {
            mVideoEncoder = MediaCodec.createEncoderByType(VIDEO_MIME_TYPE)
            mAudioEncoder = MediaCodec.createEncoderByType(AUDIO_MIME_TYPE)
        }catch (  e:java.io.IOException)
        {
            throw IllegalStateException("Unable to create MediaCodec due to: " + e.cause)
        }

        // video encoder setup
        mVideoEncoder?.reset()
        mVideoEncoder?.configure(
            createMediaFormat(),
            null, /*surface*/
            null,  /*crypto*/
            MediaCodec.CONFIGURE_FLAG_ENCODE
        )

        // audio encoder setup
        //setAudioParametersByCamcorderProfile(resolution, cameraId)
        mAudioEncoder?.reset()
        mAudioEncoder?.configure(
            createAudioMediaFormat(), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE
        )
        if (mAudioRecorder != null) {
            mAudioRecorder?.release()
        }
        mAudioRecorder = chooseAudioRecord()
        // check mAudioRecorder
        if (mAudioRecorder == null) {
            Log.e(TAG, "AudioRecord object cannot initialized correctly!")
        }
        mVideoTrackIndex = -1
        mAudioTrackIndex = -1
        mMuxerStarted = false

    }


    fun chooseAudioRecord(): AudioRecord? {
        var mic: AudioRecord? = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,mAudioSampleRate,
            AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT, getPcmBufferSize() * 4
        )
        if (mic!!.state != AudioRecord.STATE_INITIALIZED) {
            mic = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                mAudioSampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                getPcmBufferSize() * 4
            )
            if (mic.state != AudioRecord.STATE_INITIALIZED) {
                mic = null
            } /*else {
                net.ossrs.yasea.SrsEncoder.aChannelConfig = AudioFormat.CHANNEL_IN_MONO
            }*/
        } /*else {
            net.ossrs.yasea.SrsEncoder.aChannelConfig = AudioFormat.CHANNEL_IN_STEREO
        }*/
        return mic
    }


    private fun getPcmBufferSize(): Int {
        val pcmBufSize = AudioRecord.getMinBufferSize(
            mAudioSampleRate, AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        ) + 8191
        return pcmBufSize - pcmBufSize % 8192
    }


    /**
     * Write a buffer that has been encoded to file.
     *
     * @param bufferIndex the index of the buffer in the videoEncoder that has available data
     * @return returns true if this buffer is the end of the stream
     */
    private fun writeVideoEncodedBuffer(bufferIndex: Int): Boolean {
        if (bufferIndex < 0) {
            Log.e(TAG, "Output buffer should not have negative index: $bufferIndex")
            return false
        }
        // Get data from buffer
        val outputBuffer = mVideoEncoder?.getOutputBuffer(bufferIndex)

        // Check if buffer is valid, if not then return
        if (outputBuffer == null) {
            Log.d(TAG, "OutputBuffer was null.")
            return false
        }

        // Write data to mMuxer if available
        if (mAudioTrackIndex >= 0 && mVideoTrackIndex >= 0 && mVideoBufferInfo.size > 0) {
            outputBuffer.position(mVideoBufferInfo.offset)
            outputBuffer.limit(mVideoBufferInfo.offset + mVideoBufferInfo.size)
            mVideoBufferInfo.presentationTimeUs = System.nanoTime() / 1000
            synchronized(mMuxerLock) {
                if (!mIsFirstVideoSampleWrite.get()) {
                    Log.i(TAG,
                        "First video sample written."
                    )
                    mIsFirstVideoSampleWrite.set(true)
                }
                mMuxer?.writeSampleData(mVideoTrackIndex, outputBuffer, mVideoBufferInfo)
            }
        }

        // Release data
        mVideoEncoder?.releaseOutputBuffer(bufferIndex, false)

        // Return true if EOS is set
        return mVideoBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
    }

    private fun writeAudioEncodedBuffer(bufferIndex: Int): Boolean {
        val buffer: ByteBuffer = getOutputBuffer(mAudioEncoder!!, bufferIndex)!!
        buffer.position(mAudioBufferInfo.offset)
        if (mAudioTrackIndex >= 0 && mVideoTrackIndex >= 0 && mAudioBufferInfo.size > 0 && mAudioBufferInfo.presentationTimeUs > 0) {
            try {
                synchronized(mMuxerLock) {
                    if (!mIsFirstAudioSampleWrite.get()) {
                        Log.i(
                            TAG,
                            "First audio sample written."
                        )
                        mIsFirstAudioSampleWrite.set(true)
                    }
                    mMuxer?.writeSampleData(mAudioTrackIndex, buffer, mAudioBufferInfo)
                }
            } catch (e: Exception) {
                Log.e(
                    TAG,
                    "audio error:size="
                            + mAudioBufferInfo.size
                            + "/offset="
                            + mAudioBufferInfo.offset
                            + "/timeUs="
                            + mAudioBufferInfo.presentationTimeUs
                )
                e.printStackTrace()
            }
        }
        mAudioEncoder?.releaseOutputBuffer(bufferIndex, false)
        return (mAudioBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
    }

    private fun getInputBuffer(codec: MediaCodec, index: Int): ByteBuffer? {
        return codec.getInputBuffer(index)
    }

    private fun getOutputBuffer(codec: MediaCodec, index: Int): ByteBuffer? {
        return codec.getOutputBuffer(index)
    }
}