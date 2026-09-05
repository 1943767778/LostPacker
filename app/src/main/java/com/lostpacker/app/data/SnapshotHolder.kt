package com.lostpacker.app.data

import android.graphics.Bitmap

/** 跨 Activity 传递 Shizuku 截屏 Bitmap（同一进程内使用） */
object SnapshotHolder {
    var shot: Bitmap? = null

    fun release() {
        if (shot != null) { shot?.recycle(); shot = null }
    }
}