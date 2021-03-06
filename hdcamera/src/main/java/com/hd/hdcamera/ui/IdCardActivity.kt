package com.hd.hdcamera.ui

import android.Manifest
import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Message
import android.preference.PreferenceManager
import android.text.TextUtils
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.ImageCapture
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.databinding.DataBindingUtil
import com.google.gson.Gson
import com.hd.hdcamera.R
import com.hd.hdcamera.databinding.OcrIdcardActBinding
import com.hd.hdcamera.model.IdCardResult
import com.hd.hdcamera.ui.ocr.Predictor
import com.hd.hdcamera.ui.ocr.Utils
import com.hd.hdcamera.util.CommonUtil
import com.wildma.idcardcamera.camera.IDCardCamera
import java.io.File
import java.io.IOException
import java.util.concurrent.ExecutorService


class IdCardActivity : AppCompatActivity() {

    private var imageCapture: ImageCapture? = null

    private lateinit var outputDirectory: File
    private lateinit var cameraExecutor: ExecutorService

    private lateinit var mBinding:OcrIdcardActBinding


    private val TAG: String = IdCardActivity::class.java.simpleName

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
    private val currentPhotoPath: String? = null

    private lateinit var mContext: Context

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mBinding = DataBindingUtil.setContentView(this, R.layout.ocr_idcard_act)
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
                    REQUEST_LOAD_MODEL ->                         // Load model and reload test image
                        if (onLoadModel()) {
                            receiver?.sendEmptyMessage(RESPONSE_LOAD_MODEL_SUCCESSED)
                        } else {
                            receiver?.sendEmptyMessage(RESPONSE_LOAD_MODEL_FAILED)
                        }
                    REQUEST_RUN_MODEL ->                         // Run model if model is loaded
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
        val isLoaded = predictor!!.isLoaded()
        menu.findItem(R.id.open_gallery).isEnabled = isLoaded
        menu.findItem(R.id.take_photo).isEnabled = isLoaded
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> finish()
            R.id.open_gallery -> if (requestAllPermissions()) {
                //openGallery()
            }
            R.id.take_photo -> if (requestAllPermissions()) {
                //takePhoto()
                IDCardCamera.create(this).openCamera(IDCardCamera.TYPE_IDCARD_FRONT)
            }
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onResume() {
        super.onResume()
        preparedModel();
    }


    private fun requestAllPermissions(): Boolean {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            )
                != PackageManager.PERMISSION_GRANTED) {
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
        if (image != null && predictor!!.isLoaded()) {
            predictor!!.setInputImage(image)
            runModel()
        }
    }

    private fun onLoadModelFailed() {}

    private fun onRunModelFailed() {}

    private fun onRunModelSuccessed() {
        // Obtain results and update UI
        mBinding.tvInferenceTime.text = "Inference time: " + predictor!!.inferenceTime().toString() + " ms"
        val outputImage = predictor.outputImage()
        if (outputImage != null) {
            mBinding.ivInputImage.setImageBitmap(outputImage)
        }
        Log.i(TAG, "onRunModelSuccessed: " + predictor.outputResult());

        resultHandle(predictor.outputResult())

    }

    /**
     * ??????????????????
     */
    private fun resultHandle(outputResult: String) {
      var resultList:List<String> =  outputResult.split("\n")
        var userInfo = IdCardResult()
      for (item in resultList){
          if(TextUtils.isEmpty(item)){
              continue
          }
          var value = item.split(":")[1]
          value = value.replace("\r","").trim()
          if(TextUtils.isEmpty(value)){
              continue
          }else if(CommonUtil.isIDcardNumber(value)){
              userInfo.idNumber = value
          }else if(value.startsWith("???")){
              userInfo.userName = value.substring(2, value.length - 1)
          }else if(value.startsWith("??????")){
              userInfo.birthDate = value.substring(2, value.length)
          }else if(value.startsWith("???")){
            userInfo.gender = value.substring(1, 2)
          }
      }
        mBinding.tvOutputResult.text = Gson().toJson(userInfo)
        mBinding.tvOutputResult.scrollTo(0, 0)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == IDCardCamera.RESULT_CODE) {
            //?????????????????????????????????
            var  path:String = IDCardCamera.getImagePath(data);
            if (!TextUtils.isEmpty(path)) {
                if (requestCode == IDCardCamera.TYPE_IDCARD_FRONT) { //???????????????
                    //mIvFront.setImageBitmap(BitmapFactory.decodeFile(path));
                } else if (requestCode == IDCardCamera.TYPE_IDCARD_BACK) {  //???????????????
                   // mIvBack.setImageBitmap(BitmapFactory.decodeFile(path));
                }

                var image = BitmapFactory.decodeFile(path)
                ///image = Utils.rotateBitmap(image, orientation)
                onImageChanged(image)
            }
        }
    }

}