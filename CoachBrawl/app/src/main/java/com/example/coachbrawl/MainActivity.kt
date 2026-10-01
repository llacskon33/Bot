package com.example.coachbrawl

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.*

class MainActivity : Activity() {
    private val req = 42
    private lateinit var key: EditText

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val prefs = getSharedPreferences("c", MODE_PRIVATE)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 96, 48, 48) }
        box.addView(TextView(this).apply {
            text = "Coach Brawl\n\n1. Pega tu clave de la API de Anthropic.\n2. Pulsa Iniciar y acepta los permisos.\n3. Abre Brawl Stars: verás consejos en pantalla."
            textSize = 16f
        })
        key = EditText(this).apply {
            hint = "sk-ant-..."; setText(prefs.getString("k", ""))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        box.addView(key)
        box.addView(Button(this).apply { text = "Iniciar coach"; setOnClickListener { start(prefs) } })
        box.addView(Button(this).apply { text = "Detener"; setOnClickListener { stopService(Intent(this@MainActivity, CoachService::class.java)) } })
        setContentView(box)
    }

    private fun start(prefs: android.content.SharedPreferences) {
        prefs.edit().putString("k", key.text.toString().trim()).apply()
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Permite mostrar sobre otras apps y vuelve a pulsar Iniciar", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), req)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(rc: Int, res: Int, data: Intent?) {
        super.onActivityResult(rc, res, data)
        if (rc == req && res == RESULT_OK && data != null) {
            startForegroundService(Intent(this, CoachService::class.java).putExtra("code", res).putExtra("data", data))
            moveTaskToBack(true)
        }
    }
}
