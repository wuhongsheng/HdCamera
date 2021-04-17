package com.hd.hdcamera.ui

import android.Manifest
import android.app.ProgressDialog
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
import android.os.*
import android.preference.PreferenceManager
import android.provider.MediaStore
import android.text.TextUtils
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.ImageCapture
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.databinding.DataBindingUtil
import com.hd.hdcamera.R
import com.hd.hdcamera.databinding.CommonOcrActBinding
import com.hd.hdcamera.databinding.OcrIdcardActBinding
import com.hd.hdcamera.ui.ocr.Predictor
import com.hd.hdcamera.ui.ocr.Utils
import com.wildma.idcardcamera.camera.IDCardCamera
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService


class CommonOcrActivity : AppCompatActivity() {

    private var imageCapture: ImageCapture? = null

    private lateinit var outputDirectory: File
    private lateinit var cameraExecutor: ExecutorService

    private lateinit var mBinding: CommonOcrActBinding

    private val OPEN_GALLERY_REQUEST_CODE = 0
    private val TAKE_PHOTO_REQUEST_CODE = 1


    private val TAG: String = CommonOcrActivity::class.java.simpleName

    val REQUEST_LOAD_MODEL = 0
    val REQUEST_RUN_MODEL = 1
    val RESPONSE_LOAD_MODEL_SUCCESSED = 0
    val RESPONSE_LOAD_MODEL_FAILED = 1
    val RESPONSE_RUN_MODEL_SUCCESSED = 2
    val RESPONSE_RUN_MODEL_FAILED = 3

    private var receiver: Handler? = null // Receive messages from worker thread

    private var sender: Handler? = null // Send command to worker thread

    private var worker: HandlerThread? = null // Worker thread to load&run model

    private var predictor = Predictor()

    private val assetModelDirPath = "models/ocr_v1_for_cpu"
    private val assetlabelFilePath = "labels/ppocr_keys_v1.txt"

    private var pbLoadModel: ProgressDialog? = null
    private var pbRunModel: ProgressDialog? = null


    // Model settings of object detection
    private var modelPath = ""
    private var labelPath = ""
    private var imagePath = ""
    private var cpuThreadNum = 2
    private var cpuPowerMode = ""
    private var inputColorFormat = ""
    private var inputShape = longArrayOf()
    private var inputMean = floatArrayOf()
    private var inputStd = floatArrayOf()
    private var scoreThreshold = 0.1f
    private var currentPhotoPath: String? = null

    private lateinit var mContext: Context

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mBinding = DataBindingUtil.setContentView(this, R.layout.common_ocr_act)
        mContext = this
        // Prepare the worker thread for mode loading and inference
        receiver = object : Handler() {
            override fun handleMessage(msg: Message) {
                when (msg.what) {
                    RESPONSE_LOAD_MODEL_SUCCESSED -> {
                        pbLoadModel?.dismiss()
                        onLoadModelSuccessed()
                    }
                    RESPONSE_LOAD_MODEL_FAILED -> {
                        pbLoadModel?.dismiss()
                        Toast.makeText(mContext, "Load model failed!", Toast.LENGTH_SHORT).show()
                        onLoadModelFailed()
                    }
                    RESPONSE_RUN_MODEL_SUCCESSED -> {
                        pbRunModel?.dismiss()
                        onRunModelSuccessed()
                    }
                    RESPONSE_RUN_MODEL_FAILED -> {
                        pbRunModel?.dismiss()
                        Toast.makeText(mContext, "Run model failed!", Toast.LENGTH_SHORT).show()
                        onRunModelFailed()
                    }
                    else -> {
                    }
                }
            }
        }

        worker = HandlerThread("Predictor Worker")
        worker?.start()
        sender = object : Handler(worker!!.looper) {
            override fun handleMessage(msg: Message) {
                when (msg.what) {
                    REQUEST_LOAD_MODEL ->// Load model and reload test image
                        if (onLoadModel()) {
                            receiver?.sendEmptyMessage(RESPONSE_LOAD_MODEL_SUCCESSED)
                        } else {
                            receiver?.sendEmptyMessage(RESPONSE_LOAD_MODEL_FAILED)
                        }
                    REQUEST_RUN_MODEL ->// Run model if model is loaded
                        if (onRunModel()) {
                            receiver?.sendEmptyMessage(RESPONSE_RUN_MODEL_SUCCESSED)
                        } else {
                            receiver?.sendEmptyMessage(RESPONSE_RUN_MODEL_FAILED)
                        }
                }
            }
        }
    }


    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        val inflater = menuInflater
        inflater.inflate(R.menu.menu_action_options, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val isLoaded = predictor.isLoaded()
        menu.findItem(R.id.open_gallery).isEnabled = isLoaded
        menu.findItem(R.id.take_photo).isEnabled = isLoaded
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> finish()
            R.id.open_gallery -> if (requestAllPermissions()) {
                openGallery()
            }
            R.id.take_photo -> if (requestAllPermissions()) {
                takePhoto()
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun openGallery() {
        val intent = Intent(Intent.ACTION_PICK, null)
        intent.setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*")
        startActivityForResult(
            intent, OPEN_GALLERY_REQUEST_CODE
        )
    }

    private fun takePhoto() {
        val takePictureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        // Ensure that there's a camera activity to handle the intent
        if (takePictureIntent.resolveActivity(packageManager) != null) {
            // Create the File where the photo should go
            var photoFile: File? = null
            try {
                photoFile = createImageFile()
            } catch (ex: IOException) {
                Log.e("MainActitity", ex.message, ex)
                Toast.makeText(mContext, "Create Camera temp file failed: " + ex.message, Toast.LENGTH_SHORT).show()
            }
            // Continue only if the File was successfully created
            if (photoFile != null) {
                Log.i(TAG, "FILEPATH " + getExternalFilesDir("Pictures")!!.absolutePath)
                val photoURI = FileProvider.getUriForFile(
                    this,
                    "com.baidu.paddle.lite.demo.ocr.fileprovider",
                    photoFile
                )
                currentPhotoPath = photoFile.absolutePath
                takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, photoURI)
                startActivityForResult(
                    takePictureIntent,
                    TAKE_PHOTO_REQUEST_CODE
                )
                Log.i(
                    TAG,
                    "startActivityForResult finished"
                )
            }
        }
    }


    @Throws(IOException::class)
    private fun createImageFile(): File? {
        // Create an image file name
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())
        val imageFileName = "JPEG_" + timeStamp + "_"
        val storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        return File.createTempFile(
            imageFileName,  /* prefix */
            ".bmp",  /* suffix */
            storageDir /* directory */
        )
    }

    override fun onResume() {
        super.onResume()
        preparedModel()
    }


    private fun requestAllPermissions(): Boolean {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            )
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.CAMERA
                ),
                0
            )
            return false
        }
        return true
    }


    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String?>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults[0] != PackageManager.PERMISSION_GRANTED || grantResults[1] != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Permission Denied", Toast.LENGTH_SHORT).show()
        }
    }

    private fun preparedModel() {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        var settingsChanged = false
        val model_path = sharedPreferences.getString(
            getString(R.string.MODEL_PATH_KEY),
            getString(R.string.MODEL_PATH_DEFAULT)
        )
        val label_path = sharedPreferences.getString(
            getString(R.string.LABEL_PATH_KEY),
            getString(R.string.LABEL_PATH_DEFAULT)
        )
        val image_path = sharedPreferences.getString(
            getString(R.string.IMAGE_PATH_KEY),
            getString(R.string.IMAGE_PATH_DEFAULT)
        )
        settingsChanged = settingsChanged or !model_path.equals(modelPath, ignoreCase = true)
        settingsChanged = settingsChanged or !label_path.equals(labelPath, ignoreCase = true)
        settingsChanged = settingsChanged or !image_path.equals(imagePath, ignoreCase = true)
        val cpu_thread_num = sharedPreferences.getString(
            getString(R.string.CPU_THREAD_NUM_KEY),
            getString(R.string.CPU_THREAD_NUM_DEFAULT)
        )!!.toInt()
        settingsChanged = settingsChanged or (cpu_thread_num != cpuThreadNum)
        val cpu_power_mode = sharedPreferences.getString(
            getString(R.string.CPU_POWER_MODE_KEY),
            getString(R.string.CPU_POWER_MODE_DEFAULT)
        )
        settingsChanged = settingsChanged or !cpu_power_mode.equals(cpuPowerMode, ignoreCase = true)
        val input_color_format = sharedPreferences.getString(
            getString(R.string.INPUT_COLOR_FORMAT_KEY),
            getString(R.string.INPUT_COLOR_FORMAT_DEFAULT)
        )
        settingsChanged = settingsChanged or !input_color_format.equals(
            inputColorFormat,
            ignoreCase = true
        )
        val input_shape: LongArray = Utils.parseLongsFromString(
            sharedPreferences.getString(
                getString(
                    R.string.INPUT_SHAPE_KEY
                ),
                getString(R.string.INPUT_SHAPE_DEFAULT)
            ), ","
        )
        val input_mean: FloatArray = Utils.parseFloatsFromString(
            sharedPreferences.getString(
                getString(
                    R.string.INPUT_MEAN_KEY
                ),
                getString(R.string.INPUT_MEAN_DEFAULT)
            ), ","
        )
        val input_std: FloatArray = Utils.parseFloatsFromString(
            sharedPreferences.getString(
                getString(
                    R.string.INPUT_STD_KEY
                ), getString(R.string.INPUT_STD_DEFAULT)
            ), ","
        )
        settingsChanged = settingsChanged or (input_shape.size != inputShape.size)
        settingsChanged = settingsChanged or (input_mean.size != inputMean.size)
        settingsChanged = settingsChanged or (input_std.size != inputStd.size)
        if (!settingsChanged) {
            for (i in input_shape.indices) {
                settingsChanged = settingsChanged or (input_shape[i] != inputShape.get(i))
            }
            for (i in input_mean.indices) {
                settingsChanged = settingsChanged or (input_mean[i] != inputMean.get(i))
            }
            for (i in input_std.indices) {
                settingsChanged = settingsChanged or (input_std[i] != inputStd.get(i))
            }
        }
        val score_threshold = sharedPreferences.getString(
            getString(R.string.SCORE_THRESHOLD_KEY),
            getString(R.string.SCORE_THRESHOLD_DEFAULT)
        )!!.toFloat()
        settingsChanged = settingsChanged or (scoreThreshold != score_threshold)
        if (settingsChanged) {
            modelPath = model_path!!
            labelPath = label_path!!
            imagePath = image_path!!
            cpuThreadNum = cpu_thread_num
            cpuPowerMode = cpu_power_mode!!
            inputColorFormat = input_color_format!!
            inputShape = input_shape
            inputMean = input_mean
            inputStd = input_std
            scoreThreshold = score_threshold
            // Update UI
            mBinding.tvInputSetting.text = """
        Model: ${modelPath.substring(modelPath.lastIndexOf("/") + 1)}
        CPU Thread Num: $cpuThreadNum
        CPU Power Mode: $cpuPowerMode
        """.trimIndent()
            mBinding.tvInputSetting.scrollTo(0, 0)
            // Reload model if configure has been changed
            loadModel()
        }
    }

    fun loadModel() {
        pbLoadModel = ProgressDialog.show(this, "", "Loading model...", false, false)
        sender!!.sendEmptyMessage(REQUEST_LOAD_MODEL)
    }

    fun runModel() {
        pbRunModel = ProgressDialog.show(this, "", "Running model...", false, false)
        sender!!.sendEmptyMessage(REQUEST_RUN_MODEL)
    }


    fun onLoadModel(): Boolean {
        return predictor.init(
            mContext, modelPath, labelPath, cpuThreadNum,
            cpuPowerMode,
            inputColorFormat,
            inputShape, inputMean,
            inputStd, scoreThreshold
        )
    }

    fun onRunModel(): Boolean {
        return predictor.isLoaded() && predictor.runModel()
    }


    fun onLoadModelSuccessed() {
        // Load test image from path and run model
        try {
            if (imagePath.isEmpty()) {
                return
            }
            var image: Bitmap? = null
            // Read test image file from custom path if the first character of mode path is '/', otherwise read test
            // image file from assets
            image = if (imagePath.substring(0, 1) != "/") {
                val imageStream = assets.open(imagePath)
                BitmapFactory.decodeStream(imageStream)
            } else {
                if (!File(imagePath).exists()) {
                    return
                }
                BitmapFactory.decodeFile(imagePath)
            }
            if (image != null && predictor.isLoaded()) {
                predictor.setInputImage(image)
                runModel()
            }
        } catch (e: IOException) {
            Toast.makeText(mContext, "Load image failed!", Toast.LENGTH_SHORT).show()
            e.printStackTrace()
        }
    }

    private fun onImageChanged(image: Bitmap?) {
        // Rerun model if users pick test image from gallery or camera
        if (image != null && predictor.isLoaded()) {
            predictor.setInputImage(image)
            runModel()
        }
    }

    private fun onLoadModelFailed() {}

    private fun onRunModelFailed() {}

    private fun onRunModelSuccessed() {
        // Obtain results and update UI
        mBinding.tvInferenceTime.text =
            "Inference time: " + predictor.inferenceTime().toString() + " ms"
        val outputImage = predictor.outputImage()
        if (outputImage != null) {
            mBinding.ivInputImage.setImageBitmap(outputImage)
        }
        Log.i(TAG, "onRunModelSuccessed: " + predictor.outputResult())
        mBinding.tvOutputResult.text = predictor.outputResult()
        mBinding.tvOutputResult.scrollTo(0, 0)
    }


    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK) {
            if (requestCode == OPEN_GALLERY_REQUEST_CODE) {
                if (data == null) {
                    return
                }
                try {
                    var resolver: ContentResolver = contentResolver
                    var uri: Uri? = data.data
                    var image: Bitmap = MediaStore.Images.Media.getBitmap(contentResolver, uri)
                    var proj = arrayOf(MediaStore.Images.Media.DATA)
                    var cursor: Cursor = managedQuery(uri, proj, null, null, null)
                    cursor.moveToFirst()
                    onImageChanged(image)
                } catch (e: IOException) {
                    Log.e(TAG, e.toString())
                }
            } else if (requestCode == TAKE_PHOTO_REQUEST_CODE) {
                if (currentPhotoPath != null) {
                    var exif: ExifInterface? = null
                    try {
                        exif = ExifInterface(currentPhotoPath!!)
                    } catch (e: IOException) {
                        e.printStackTrace()
                    }
                    val orientation = exif!!.getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_UNDEFINED
                    )
                    Log.i(TAG, "rotation $orientation")
                    var image = BitmapFactory.decodeFile(currentPhotoPath)
                    image = Utils.rotateBitmap(image, orientation)
                    onImageChanged(image)
                } else {
                    Log.e(TAG, "currentPhotoPath is null")

                }
            }
        }
    }

}