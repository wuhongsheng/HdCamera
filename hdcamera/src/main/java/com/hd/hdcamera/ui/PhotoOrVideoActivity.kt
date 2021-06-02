package com.hd.hdcamera.ui

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore.Video
import android.util.Log
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.annotation.NonNull
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.databinding.DataBindingUtil
import androidx.lifecycle.lifecycleScope
import com.hd.hdcamera.R
import com.hd.hdcamera.databinding.CameraActBinding
import com.hd.hdcamera.util.CommonUtil
import com.hd.hdcamera.util.OrientationLiveData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class PhotoOrVideoActivity : AppCompatActivity(), View.OnClickListener, CaptureListener {


    private lateinit var outputDirectory: File
    private lateinit var cameraExecutor: ExecutorService

    private lateinit var mBinding: CameraActBinding

    /** Live data listener for changes in the device orientation relative to the camera */
    private lateinit var relativeOrientation: OrientationLiveData
    //拍照用例
    private var imageCapture: ImageCapture? = null
    //录像用例
    private var videoCapture: VideoCapture? = null
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
    private val outputFile: File by lazy { createRecordFile(this, "mp4") }

    private lateinit var mContext:Context

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        mBinding = DataBindingUtil.setContentView(this, R.layout.camera_act)
        // Request camera permissions
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                    this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }
        mContext = this

        // Set up the listener for take photo button
        mBinding.cameraCaptureButton.setCaptureListener(this)
        mBinding.ivFlipCamera.setOnClickListener(this)
        mBinding.ivBack.setOnClickListener(this)


        outputDirectory = getOutputDirectory()
        cameraExecutor = Executors.newSingleThreadExecutor()

        // Used to rotate the output media to match device orientation
        relativeOrientation = OrientationLiveData(this, characteristics).apply {
            observe(mContext as PhotoOrVideoActivity, androidx.lifecycle.Observer {
                    orientation -> Log.d(TAG, "Orientation changed: $orientation")
            })
        }
    }


    private fun takePhoto() {
        Log.d(TAG, "takePhoto")
        // Get a stable reference of the modifiable image capture use case
        val imageCapture = imageCapture ?: return

        // Create time-stamped output file to hold the image
        val photoFile = File(
                outputDirectory,
                SimpleDateFormat(FILENAME_FORMAT, Locale.CHINA
                ).format(System.currentTimeMillis()) + ".jpg")

        // Create output options object which contains file + metadata
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        // Set up image capture listener, which is triggered after photo has
        // been taken
        imageCapture.takePicture(
                outputOptions, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
            override fun onError(exc: ImageCaptureException) {
                Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
            }

            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                val savedUri = Uri.fromFile(photoFile)
                val msg = "Photo capture succeeded: $savedUri"
                Toast.makeText(mContext, msg, Toast.LENGTH_SHORT).show()
                Log.d(TAG, msg)
            }
        })

    }

    private fun toggleFrontBackCamera() {
        lensFacing = if (CameraSelector.LENS_FACING_FRONT == lensFacing)
            CameraSelector.LENS_FACING_BACK
        else
            CameraSelector.LENS_FACING_FRONT
        startCamera()
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


           /* val imageAnalyzer = ImageAnalysis.Builder()
                    .build()
                    .also {
                        it.setAnalyzer(cameraExecutor, LuminosityAnalyzer { luma ->
                            Log.d(TAG, "Average luminosity: $luma")
                        })
                    }*/


            // Select back camera as a default
            val cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
            //拍照用例配置
            imageCapture = ImageCapture.Builder().build()
            //录像用例配置
            videoCapture = VideoCapture.Builder()
                //.setTargetAspectRatio(AspectRatio.RATIO_16_9) //设置高宽比
                //.setTargetRotation(relativeOrientation.value!!)//设置旋转角度
                //.setAudioRecordSource(MediaRecorder.AudioSource.MIC)//设置音频源麦克风
                .build()

            try {
                // Unbind use cases before rebinding
                cameraProvider.unbindAll()
                // Bind use cases to camera
              /*  cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture,videoCapture,imageAnalyzer)*/
                cameraProvider.bindToLifecycle(
                        this, cameraSelector, preview, imageCapture,videoCapture)


            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
                baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun getOutputDirectory(): File {
        val mediaDir = externalMediaDirs.firstOrNull()?.let {
            File(it, resources.getString(R.string.app_name)).apply { mkdirs() } }
        return if (mediaDir != null && mediaDir.exists())
            mediaDir else filesDir
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        private val TAG = PhotoOrVideoActivity::class.java.simpleName

        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private const val REQUEST_CODE_TAKE_VIDEO = 11

        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        private var lensFacing = CameraSelector.LENS_FACING_BACK

        /** Creates a [File] named with the current date and time */
        private fun createRecordFile(context: Context, extension: String): File {
            val sdf = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss_SSS", Locale.CHINA)
            return File(context.filesDir, "VID_${sdf.format(Date())}.$extension")
        }
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
        }else if(requestCode == REQUEST_CODE_TAKE_VIDEO){
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
        }
    }

    override fun takePictures() {
        takePhoto()
        mBinding.tvTip.visibility = View.INVISIBLE
    }

    override fun recordShort(time: Long) {

    }

    override fun recordStart() {
        mBinding.tvTip.visibility = View.INVISIBLE
        takeVideo()
        Log.d(TAG, "Recording started")
    }

    private fun takeVideo() {
        Log.d(TAG, "takeVideo")

        //开始录像
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
            return
        }
        videoCapture?.startRecording(VideoCapture.OutputFileOptions.Builder(outputFile).build(), Executors.newSingleThreadExecutor(), object :
            VideoCapture.OnVideoSavedCallback {
            override fun onVideoSaved(@NonNull file: VideoCapture.OutputFileResults) {
                lifecycleScope.launch(Dispatchers.Main) {
                    //保存视频成功回调，会在停止录制时被调用
                    Toast.makeText(mContext, file.savedUri.toString(), Toast.LENGTH_SHORT).show()
                    Log.d(TAG, "文件已保存到:" + file.savedUri.toString())
                    // Broadcasts the media file to the rest of the system
                  /*  MediaScannerConnection.scanFile(
                        mContext, arrayOf(outputFile.absolutePath), null, null
                    )*/


                    val localContentValues: ContentValues? = CommonUtil.getVideoContentValues(
                        mContext.applicationContext,
                        outputFile,
                        System.currentTimeMillis()
                    )
                    val localUri: Uri? = mContext.contentResolver
                        .insert(Video.Media.EXTERNAL_CONTENT_URI, localContentValues)

                    // Launch external activity via intent to play video recorded using our provider
                    startActivity(Intent().apply {
                        action = Intent.ACTION_VIEW
                        type = MimeTypeMap.getSingleton()
                            .getMimeTypeFromExtension(outputFile.extension)
                        val authority = "com.hd.hdcamera.fileprovider"
                        data = FileProvider.getUriForFile(mContext, authority, outputFile)
                        flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP
                    })
                }
            }
            override fun onError(videoCaptureError: Int, message: String, cause: Throwable?) {
                //保存失败的回调，可能在开始或结束录制时被调用
                Log.e(TAG, "onError: $message")
            }
        })


    }

    override fun recordEnd(time: Long) {
        //停止录制
        videoCapture?.stopRecording()
        //preview?.clear()//清除预览
    }

    override fun recordZoom(zoom: Float) {
        Log.d(TAG, "recordZoom")
    }

    override fun recordError() {
        Log.d(TAG, "recordError")
    }
}