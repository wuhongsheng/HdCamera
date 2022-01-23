package com.hd.hdcamera.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.databinding.DataBindingUtil
import com.hd.hdcamera.R
import com.hd.hdcamera.databinding.RtmpPushActBinding
import com.hd.hdcamera.rtmp.ImageUtils
import com.hd.hdcamera.rtmp.RtmpClient
import com.hd.hdcamera.util.OrientationLiveData
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * RTMP推流
 */
class RtmpActivity : AppCompatActivity(), View.OnClickListener {

    private lateinit var cameraExecutor: ExecutorService

    private lateinit var mBinding: RtmpPushActBinding

    /** Live data listener for changes in the device orientation relative to the camera */
    private lateinit var relativeOrientation: OrientationLiveData

    //预览对象
    private var preview: Preview? = null


    /** Detects, characterizes, and connects to a CameraDevice (used for all camera operations) */
    private val cameraManager: CameraManager by lazy {
        val context = applicationContext
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    /** [CameraCharacteristics] corresponding to the provided Camera ID */
    private val characteristics: CameraCharacteristics by lazy {
        cameraManager.getCameraCharacteristics("0")
    }


    /** File where the recording will be saved */
    private val outputFile: File by lazy {createRecordFile(this, "mp4") }

    private lateinit var mContext:Context

    private var rtmpClient:RtmpClient?=null

    companion object {
        private val TAG = RtmpActivity::class.java.simpleName
        private const val rtmpUrl = "rtmp://172.16.0.178/live/livestream"
        private const val REQUEST_CODE_PERMISSIONS = 10

        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        //默认启用后置摄像头
        private var lensFacing = CameraSelector.LENS_FACING_BACK
        /** Creates a [File] named with the current date and time */
        private fun createRecordFile(context: Context, extension: String): File {
            val sdf = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss_SSS", Locale.CHINA)
            return File(context.filesDir, "VID_${sdf.format(Date())}.$extension")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        mBinding = DataBindingUtil.setContentView(this, R.layout.rtmp_push_act)
        // Request camera permissions
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                    this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }
        mContext = this

        // Set up the listener for take photo button
        mBinding.ivFlipCamera.setOnClickListener(this)

        mBinding.btnStartPush.setOnClickListener(this)
        mBinding.btnStopPush.setOnClickListener(this)

        cameraExecutor = Executors.newSingleThreadExecutor()

        // Used to rotate the output media to match device orientation
        relativeOrientation = OrientationLiveData(this, characteristics).apply {
            observe(mContext as RtmpActivity, androidx.lifecycle.Observer {
                    orientation -> Log.d(TAG, "Orientation changed: $orientation")
            })
        }


        rtmpClient = RtmpClient(outputFile)
        //rtmpClient = RtmpClient(RtmpClient.EncodeStrategy.SOFT_ENCODER)

    }



    private fun toggleFrontBackCamera() {
        lensFacing = if (CameraSelector.LENS_FACING_FRONT == lensFacing)
            CameraSelector.LENS_FACING_BACK
        else
            CameraSelector.LENS_FACING_FRONT
        startCamera()
    }


    fun startLive() {
        rtmpClient?.startLive(rtmpUrl)
    }

    fun stopLive() {
        rtmpClient?.stopLive()
    }

    /**
     * 开启相机
     */
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener(Runnable {
            // Used to bind the lifecycle of cameras to the lifecycle owner
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // Preview
            preview = Preview.Builder()
                    .build()
                    .also {
                        it.setSurfaceProvider(mBinding.viewFinder.surfaceProvider)
                    }

            val imageAnalyzer = ImageAnalysis.Builder()
                //.setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetResolution(Size(rtmpClient!!.width, rtmpClient!!.height))
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, ImageAnalysis.Analyzer { image ->
                        val rotationDegrees = image.imageInfo.rotationDegrees
                        // insert your code here.
                        Log.e(TAG, "analyze")
                        // 开启直播并且已经成功连接服务器才获取i420数据
                        if (rtmpClient?.isConnectd!!) {
                            val bytes = ImageUtils.getBytes(
                                image,
                                image.imageInfo.rotationDegrees,
                                rtmpClient!!.width,
                                rtmpClient!!.height
                            )

                            rtmpClient?.sendVideo(bytes)
                        }
                        image.close()
                    })
                }


            // Select back camera as a default
            val cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

            try {
                // Unbind use cases before rebinding
                cameraProvider.unbindAll()
                // Bind use cases to camera
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview,imageAnalyzer)

            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
                baseContext, it) == PackageManager.PERMISSION_GRANTED
    }


    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        rtmpClient?.releaseResources()
    }



    override fun onRequestPermissionsResult(
            requestCode: Int, permissions: Array<String>, grantResults:
            IntArray) {
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this,
                        "Permissions not granted by the user.",
                        Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    override fun onClick(view: View?) {
        if(view!!.id == R.id.iv_flip_camera){
            toggleFrontBackCamera();
        }else if(view.id == R.id.iv_back){
            this.finish()
        }else if(view.id == R.id.btn_start_push){
            startLive()
        }else if(view.id == R.id.btn_stop_push){
            stopLive()
        }
    }
}