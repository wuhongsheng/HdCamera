package com.hd.hdcamera.rtmp

import android.graphics.*
import android.media.*
import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.annotation.GuardedBy
import androidx.annotation.UiThread
import com.hd.hdcamera.util.YuvUtil
import java.io.ByteArrayOutputStream
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
) : Encoder {

    constructor(rtmpClient: RtmpClient, outputFile: File) : this(rtmpClient) {
        this.rtmpClient = rtmpClient
        this.outputFile = outputFile
    }


    private val TAG = "---->HardEncoder<----"

    /** Android preferred mime type for AVC video.  */
    private val VIDEO_MIME_TYPE = "video/avc"
    //// 编码器类型，AAC
    private val AUDIO_MIME_TYPE = "audio/mp4a-latm"

    /** Amount of time to wait for dequeuing a buffer from the videoEncoder.  */
    private val DEQUE_TIMEOUT_USEC = 10000
    private var mAudioEncoder: MediaCodec? = null
    private var mVideoEncoder: MediaCodec? = null

    private var outputFile: File? = null
    val VFPS = 24
    val VGOP = 48
    //val mAudioSampleRate = 44100
    //val mAudioChannelCount = 1
    private val mVideoBitRate = 640_000   // 640 kbps
    //音频比特率64kbps
    val mAudioBitRate = 64 * 1024 // 64 kbps


    private var mIsRecording = false

    /** audio raw data  */
    private var mAudioRecorder: AudioRecord? = null

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
    private val mMuxerLock = Object()
    private val mEndOfVideoStreamSignal = AtomicBoolean(true)
    private val mEndOfAudioStreamSignal = AtomicBoolean(true)
    private val mEndOfAudioVideoSignal = AtomicBoolean(true)
    private val mAudioBufferInfo = MediaCodec.BufferInfo()

    /** For record the first sample written time.  */
    private val mIsFirstVideoSampleWrite = AtomicBoolean(false)
    private val mIsFirstAudioSampleWrite = AtomicBoolean(false)

    private var mPresentTimeUs: Long = 0


    init {
        Log.e(TAG, "init")
        // video thread start
        mVideoHandlerThread.start()
        mVideoHandler = Handler(mVideoHandlerThread.looper)
        // audio thread start
        mAudioHandlerThread.start()
        mAudioHandler = Handler(mAudioHandlerThread.looper)
        setupEncoder()
    }

    override fun start() {
        mIsFirstVideoSampleWrite.set(false)
        mIsFirstAudioSampleWrite.set(false)

        mEndOfVideoStreamSignal.set(false)
        mEndOfAudioStreamSignal.set(false)
        mEndOfAudioVideoSignal.set(false)

        try {
            mAudioRecorder = rtmpClient.audioChannel.audioRecord
            // check mAudioRecorder
            if (mAudioRecorder == null) {
                Log.e(TAG, "AudioRecord object cannot initialized correctly!")
                return
            }
            // audioRecord start
            //mAudioRecorder?.startRecording()
            //Log.i(TAG, "mAudioRecorder start")
        } catch (e: java.lang.IllegalStateException) {
            Log.i(TAG, "AudioRecorder start fail")
            return
        }

        try {
            // audio encoder start
            Log.i(TAG, "audioEncoder start")
            mAudioEncoder?.start()
            // video encoder start
            Log.i(TAG, "videoEncoder start")
            mVideoEncoder?.start()

        } catch (e: java.lang.IllegalStateException) {
            Log.i(TAG, "Audio/Video encoder start fail", e)
            return
        }

        try {
            synchronized(mMuxerLock) {
                mMuxer = initMediaMuxer()
            }
        } catch (e: IOException) {
            Log.i(TAG, "MediaMuxer creation failed!", e)
            return
        }
        mIsRecording = true
        mAudioHandler?.post { audioEncode() }
        mVideoHandler?.post {
            val errorOccurred: Boolean = videoEncode()
            if (!errorOccurred) {
                Log.i(TAG, "videoEncode complete")
            }
        }
    }

    override fun stop() {
        mIsRecording = false
    }

    override fun audioEncode(buffer: ByteArray, size: Int) {
        if(mIsRecording){
            val inBuffers: Array<ByteBuffer> = mAudioEncoder?.inputBuffers!!
            val inBufferIndex: Int = mAudioEncoder?.dequeueInputBuffer(-1)!!
            if (inBufferIndex >= 0) {
                val bb = inBuffers[inBufferIndex]
                bb.clear()
                bb.put(buffer, 0, size)
                ///val pts = System.nanoTime() / 1000 - mPresentTimeUs
                val pts = System.nanoTime() / 1000
                mAudioEncoder?.queueInputBuffer(inBufferIndex, 0, size, pts,  0)
            }
        }
    }

    override fun videoEncode(buffer: ByteArray, width: Int, height: Int) {
        Log.i(TAG, "videoEncode buffer:"+buffer.size)

        var frameSize = width * height * 3 / 2
        var yuv420 =  ByteArray(frameSize)
        Log.i(TAG, "videoEncode yuv420:"+yuv420.size)
        if(mIsRecording){

            //val pts: Long = System.nanoTime() / 1000 - mPresentTimeUs
            val pts: Long = System.nanoTime() / 1000

            var inBuffers: Array<ByteBuffer> = mVideoEncoder?.inputBuffers!!
            val inBufferIndex: Int = mVideoEncoder?.dequeueInputBuffer(-1)!!
            if (inBufferIndex >= 0) {
                Log.i(TAG, "queueInputBuffer")
                val bb = inBuffers[inBufferIndex]
                bb.clear()
                bb.put(buffer, 0, buffer.size)
                mVideoEncoder?.queueInputBuffer(inBufferIndex, 0, buffer.size, pts, 0)
            }
        }
    }



    @Throws(IOException::class)
    private fun initMediaMuxer(): MediaMuxer {
        val mediaMuxer: MediaMuxer
        if (outputFile != null) {
            //mSavedVideoUri = Uri.fromFile(outputFileOptions!!.getFile())
            Log.e(TAG, "initMediaMuxer:" + outputFile!!.absoluteFile)
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
        while (!videoEos && !errorOccurred && mIsRecording) {
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
                        Log.e(TAG, "mMuxer add video track")
                        mVideoTrackIndex = mMuxer?.addTrack(mVideoEncoder!!.outputFormat)!!
                        if (mAudioTrackIndex >= 0 && mVideoTrackIndex >= 0) {
                            mMuxerStarted = true
                            Log.i(TAG, "media mMuxer start")
                            mMuxer?.start()
                        }
                    }
                }
                MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    Log.i(TAG, "INFO_TRY_AGAIN_LATER")
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
                Log.i(TAG, "Muxer release")
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
        while (!audioEos && mIsRecording) {
            // Check for end of stream from main thread
            if (mEndOfAudioStreamSignal.get()) {
                Log.e(TAG,"mEndOfAudioStreamSignal is true")
                mEndOfAudioStreamSignal.set(false)
                mIsRecording = false
            }

            // get audio deque input buffer
            if (mAudioEncoder != null && mAudioRecorder != null) {
                // start to dequeue audio output buffer
                do {
                    outIndex = mAudioEncoder?.dequeueOutputBuffer(mAudioBufferInfo, 0)!!
                    when (outIndex) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> synchronized(mMuxerLock) {
                            Log.e(TAG, "mMuxer add audio track")
                            mAudioTrackIndex = mMuxer?.addTrack(mAudioEncoder?.outputFormat!!)!!
                            if (mAudioTrackIndex >= 0 && mVideoTrackIndex >= 0) {
                                mMuxerStarted = true
                                mMuxer?.start()
                            }
                        }
                        MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            Log.i(TAG, "audio INFO_TRY_AGAIN_LATER")
                        }
                        else ->// Drops out of order audio frame if the frame's earlier than last
                            // frame.
                            if (mAudioBufferInfo.presentationTimeUs > lastAudioTimestamp) {
                                audioEos = writeAudioEncodedBuffer(outIndex)
                                lastAudioTimestamp = mAudioBufferInfo.presentationTimeUs
                            } else {
                                Log.e(
                                    TAG, "Drops frame, current frame's timestamp "
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

        val format =
            MediaFormat.createVideoFormat(VIDEO_MIME_TYPE, rtmpClient.width,rtmpClient.height)
        //var mVideoColorFormat = chooseVideoEncoder();
        //颜色格式
        format.setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            COLOR_FormatYUV420Planar
        )
        //码率 所需的比特率（以比特/秒为单位）
        format.setInteger(MediaFormat.KEY_BIT_RATE,  mVideoBitRate)
        //帧率
        format.setInteger(MediaFormat.KEY_FRAME_RATE, VFPS)
        //I 帧间隔
        //format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, VGOP/VFPS)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        return format
    }

    /** Creates a [MediaFormat] using parameters for audio from the configuration  */
    private fun createAudioMediaFormat(): MediaFormat {
        val format = MediaFormat.createAudioFormat(
            AUDIO_MIME_TYPE, rtmpClient.mAudioSampleRate,
            rtmpClient.mAudioChannelCount
        )
        //// 芯片支持的AAC级别，LC
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
    fun setupEncoder() {
        Log.e(TAG, "setupEncoder")
        try {
            mVideoEncoder = MediaCodec.createEncoderByType(VIDEO_MIME_TYPE)
            mAudioEncoder = MediaCodec.createEncoderByType(AUDIO_MIME_TYPE)
        } catch (e: java.io.IOException) {
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


        // the referent PTS for video and audio encoder.
        mPresentTimeUs = System.nanoTime() / 1000
        // audio encoder setup
        mAudioEncoder?.reset()
        mAudioEncoder?.configure(
            createAudioMediaFormat(), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE
        )

        if (mAudioRecorder != null) {
            mAudioRecorder?.release()
        }

        mVideoTrackIndex = -1
        mAudioTrackIndex = -1

        mMuxerStarted = false
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
        // Get data from buffer  取出编码后的H264数据
        val outputBuffer = mVideoEncoder?.getOutputBuffer(bufferIndex)
        // Check if buffer is valid, if not then return
        if (outputBuffer == null) {
            Log.d(TAG, "OutputBuffer was null.")
            return false
        }

        // Write data to mMuxer if available  mAudioTrackIndex >= 0
        if (mVideoTrackIndex >= 0 && mVideoBufferInfo.size > 0) {
            //当前的下标位置，表示进行下一个读写操作时的起始位置；
            outputBuffer.position(mVideoBufferInfo.offset)
            //结束标记下标，表示进行下一个读写操作时的（最大）结束位置；
            outputBuffer.limit(mVideoBufferInfo.offset + mVideoBufferInfo.size)
            mVideoBufferInfo.presentationTimeUs = System.nanoTime() / 1000
            synchronized(mMuxerLock) {
                if (!mIsFirstVideoSampleWrite.get()) {
                    Log.i(
                        TAG,
                        "First video sample written."
                    )
                    mIsFirstVideoSampleWrite.set(true)
                }
                Log.i(TAG, "Write video data to mMuxer")
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
                    Log.i(TAG, "Write audio data to mMuxer")
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