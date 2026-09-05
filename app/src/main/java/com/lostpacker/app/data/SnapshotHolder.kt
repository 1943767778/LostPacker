package com.lostpacker.app.data

import android.graphics.Bitmap

/** 跨组件传递 Bitmap（同一进程内）：截屏 / 上传图片的选择结果。 */
object SnapshotHolder {
    private var shot: Bitmap? = null
    var picked = false

    /** 文件选择结束（可能取消，bitmap 为 null）时调用 */
    fun finishPick(bmp: Bitmap?) {
        shot?.recycle()
        shot = bmp
        picked = true
    }

    fun isPicked(): Boolean = picked

    /** 取出图片并复位 */
    fun takePicked(): Bitmap? {
        val b = shot
        shot = null
        picked = false
        return b
    }

    fun release() {
        shot?.recycle()
        shot = null
        picked = false
    }
}