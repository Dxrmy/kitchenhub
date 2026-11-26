package com.kyle.kitchenhub.core.stability

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.kyle.kitchenhub.MainActivity

class SafeRecoveryActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#121318"))
            setPadding(48, 48, 48, 48)
        }
        root.addView(TextView(this).apply {
            text = "KitchenHub Safe Recovery"
            textSize = 28f
            setTextColor(Color.parseColor("#FF8A80"))
        })
        root.addView(TextView(this).apply {
            text = "Detected rapid crash loop. Choose a recovery action:"
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(0, 12, 0, 28)
        })
        fun addBtn(label: String, action: () -> Unit) {
            root.addView(Button(this).apply {
                text = label
                textSize = 18f
                setOnClickListener { action() }
            })
        }
        addBtn("Open Android Settings") {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
        addBtn("Switch Default Launcher") {
            startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        }
        addBtn("Clear App Cache & Retry") {
            cacheDir.deleteRecursively()
            CrashFailsafe.clearSafeMode(this)
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
        setContentView(root)
    }
}
