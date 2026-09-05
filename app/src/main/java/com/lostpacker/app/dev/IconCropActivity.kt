package com.lostpacker.app.dev

import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.lostpacker.app.R
import com.lostpacker.app.data.SnapshotHolder
import com.lostpacker.app.overlay.CropView
import com.lostpacker.app.prefs.Prefs

/**
 * 框选页：显示 Shizuku 截屏，用户在图上框出物品图标，
 * 点击“确认此图标”裁切并保存为模板。
 */
class IconCropActivity : AppCompatActivity() {

    private lateinit var cropView: CropView
    private lateinit var templateRepo: TemplateRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_icon_crop)
        Prefs.init(this)
        templateRepo = TemplateRepository(this)

        cropView = findViewById(R.id.cropView)
        cropView.setSource(SnapshotHolder.shot)

        findViewById<Button>(R.id.btnCropConfirm).setOnClickListener {
            val icon = cropView.selectedBitmap()
            if (icon == null) {
                Toast.makeText(this, "请先拖拽框出物品图标", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val label = "icon_${System.currentTimeMillis()}"
            templateRepo.save(label, icon)
            SnapshotHolder.release()
            icon.recycle()
            Toast.makeText(this, "已保存模板：$label", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        SnapshotHolder.release()
    }
}