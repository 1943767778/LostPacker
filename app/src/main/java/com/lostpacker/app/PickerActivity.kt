package com.lostpacker.app

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.lostpacker.app.data.SnapshotHolder

/**
 * 透明的图片选择中转：从系统图库/文件选择一张图片，解码后放入 SnapshotHolder，
 * 供悬浮窗服务继续做框选/模板等操作。取消则放进 null。
 */
class PickerActivity : ComponentActivity() {

    private val getImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val bmp = if (uri != null) decode(uri) else null
        SnapshotHolder.finishPick(bmp)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        getImage.launch("image/*")
    }

    private fun decode(uri: Uri): Bitmap? {
        return try {
            BitmapFactory.decodeStream(contentResolver.openInputStream(uri))
        } catch (e: Exception) { null }
    }
}