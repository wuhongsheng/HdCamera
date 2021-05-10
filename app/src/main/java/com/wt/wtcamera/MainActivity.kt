package com.wt.wtcamera

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.databinding.DataBindingUtil
import com.chad.library.adapter.base.BaseQuickAdapter
import com.chad.library.adapter.base.listener.OnItemClickListener
import com.hd.hdcamera.ui.CommonOcrActivity
import com.hd.hdcamera.ui.PhotoOrVideoActivity
import com.wt.wtcamera.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity(), OnItemClickListener {

    private lateinit var mBinding: ActivityMainBinding

    private val titleList = arrayOf("拍照/录像", "通用OCR", "车牌OCR");
    private val classList = arrayOf(PhotoOrVideoActivity::class.java, CommonOcrActivity::class.java, CommonOcrActivity::class.java)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mBinding = DataBindingUtil.setContentView(this, R.layout.activity_main)
        var menuAdapter = MenuAdapter(titleList.toList())
        menuAdapter.setOnItemClickListener(this)
        mBinding.rv.adapter = menuAdapter
    }

    override fun onItemClick(adapter: BaseQuickAdapter<*, *>, view: View, position: Int) {
        startActivity(Intent(this, classList[position]))
    }

}