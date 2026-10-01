package com.example.coachbrawl

import android.annotation.SuppressLint
import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.Base64
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

@Suppress("DEPRECATION")
class CoachService : Service() {
    companion object {
        const val INTERVAL_MS = 5000L                    // cada cuánto se analiza la pantalla
        const val MODEL = "claude-haiku-4-5-20251001"    // rápido y barato
        const val SYSTEM = "Eres un entrenador de Brawl Stars. Recibes una captura de la partida del jugador. " +
            "Responde en español con UNA frase de máximo 12 palabras con la acción más útil ahora: posición, munición, súper, peligro u objetivo. " +
            "Si la imagen no es una partida de Brawl Stars, responde 'Abre Brawl Stars'. No inventes lo que no se ve."
    }

    private val h = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var proj: MediaProjection? = null
    private var vd: VirtualDisplay? = null
    private var ir: ImageReader? = null
    private var tv: TextView? = null
    private var wm: WindowManager? = null
    private var busy = false
    private var w = 0
    private var hh = 0
    private val tick = object : Runnable { override fun run() { grab(); h.postDelayed(this, INTERVAL_MS) } }

    override fun onBind(i: Intent?) = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        if (i == null || i.action == "STOP") { stopSelf(); return START_NOT_STICKY }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("coach", "Coach", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 0, Intent(this, CoachService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "coach").setContentTitle("Coach Brawl activo")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .addAction(Notification.Action.Builder(0, "Detener", stop).build()).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) else startForeground(1, n)
        val mpm = getSystemService(MediaProjectionManager::class.java)
        proj = mpm.getMediaProjection(i.getIntExtra("code", 0), i.getParcelableExtra<Intent>("data")!!)
        proj!!.registerCallback(object : MediaProjection.Callback() { override fun onStop() { stopSelf() } }, h)
        overlay(); setup()
        h.postDelayed(tick, 3000)
        return START_NOT_STICKY
    }

    private fun overlay() {
        wm = getSystemService(WindowManager::class.java)
        tv = TextView(this).apply {
            text = "Coach listo…"; setTextColor(Color.WHITE); textSize = 15f
            setPadding(24, 12, 24, 12); setBackgroundColor(0x99000000.toInt())
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = 24 }
        wm!!.addView(tv, lp)
    }

    // Reduce la captura a la mitad y se adapta a la rotación (Brawl Stars va en horizontal)
    private fun setup() {
        val m = DisplayMetrics()
        wm!!.defaultDisplay.getRealMetrics(m)
        val nw = m.widthPixels / 2; val nh = m.heightPixels / 2
        if (nw == w && nh == hh && vd != null) return
        w = nw; hh = nh
        val old = ir
        ir = ImageReader.newInstance(w, hh, PixelFormat.RGBA_8888, 2)
        if (vd == null) {
            vd = proj!!.createVirtualDisplay("coach", w, hh, m.densityDpi / 2, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, ir!!.surface, null, null)
        } else {
            vd!!.resize(w, hh, m.densityDpi / 2); vd!!.surface = ir!!.surface
        }
        old?.close()
    }

    private fun grab() {
        if (busy) return
        setup()
        val img = ir?.acquireLatestImage() ?: return
        val pl = img.planes[0]
        val full = Bitmap.createBitmap(pl.rowStride / pl.pixelStride, img.height, Bitmap.Config.ARGB_8888)
        full.copyPixelsFromBuffer(pl.buffer)
        val crop = Bitmap.createBitmap(full, 0, 0, img.width, img.height)
        img.close()
        val o = ByteArrayOutputStream()
        crop.compress(Bitmap.CompressFormat.JPEG, 55, o)
        val b64 = Base64.encodeToString(o.toByteArray(), Base64.NO_WRAP)
        busy = true
        io.execute {
            val r = try { ask(b64) } catch (e: Exception) { "Error de red" }
            h.post { tv?.text = r; busy = false }
        }
    }

    private fun ask(b64: String): String {
        val key = getSharedPreferences("c", MODE_PRIVATE).getString("k", "") ?: ""
        val img = JSONObject().put("type", "image").put("source",
            JSONObject().put("type", "base64").put("media_type", "image/jpeg").put("data", b64))
        val txt = JSONObject().put("type", "text").put("text", "Dame el consejo ahora.")
        val body = JSONObject().put("model", MODEL).put("max_tokens", 100).put("system", SYSTEM)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", JSONArray().put(img).put(txt))))
        val c = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 10000; c.readTimeout = 20000
        c.setRequestProperty("x-api-key", key)
        c.setRequestProperty("anthropic-version", "2023-06-01")
        c.setRequestProperty("content-type", "application/json")
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        if (c.responseCode >= 400) return "Error ${c.responseCode}: revisa tu clave o saldo"
        val s = c.inputStream.bufferedReader().readText()
        return JSONObject(s).getJSONArray("content").getJSONObject(0).getString("text")
    }

    override fun onDestroy() {
        h.removeCallbacksAndMessages(null)
        tv?.let { try { wm?.removeView(it) } catch (_: Exception) {} }
        vd?.release(); ir?.close(); proj?.stop(); io.shutdown()
        super.onDestroy()
    }
}
