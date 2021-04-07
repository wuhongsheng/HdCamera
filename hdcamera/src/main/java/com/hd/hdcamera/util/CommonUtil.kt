package com.hd.hdcamera.util

import android.text.TextUtils
import java.util.regex.Pattern

/**
 * description
 * @author whs
 * @date 2021/4/7
 */
class CommonUtil {

    companion object{
        /**
         * 验证身份证号码
         * @param mobiles
         * @return
         */
        fun isIDcardNumber(mobiles: String?): Boolean {
            if (TextUtils.isEmpty(mobiles)) {
                return false
            }
            val telRegex =
                "^[1-9]\\d{5}(18|19|([23]\\d))\\d{2}((0[1-9])|(10|11|12))(([0-2][1-9])|10|20|30|31)\\d{3}[0-9Xx]$"
            val pattern = Pattern.compile(telRegex)
            val matcher = pattern.matcher(mobiles)
            return matcher.matches()
        }
    }
}