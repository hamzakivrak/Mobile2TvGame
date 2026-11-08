package com.hk.mobile2tvgame

import android.app.Activity
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import rikka.shizuku.Shizuku
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var output: TextView
    private lateinit var versionEdit: EditText
    private lateinit var displayEdit: EditText
    private lateinit var appEdit: EditText
    private lateinit var runBtn: Button

    private var service: IUserService? = null

    private val connection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IUserService.Stub.asInterface(binder)
            runOnUiThread { setStatus("Shizuku hizmeti bagli. Testi baslatabilirsiniz.") }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            runOnUiThread { setStatus("Shizuku hizmeti koptu.") }
        }
    }

    private val permListener = Shizuku.OnRequestPermissionResultListener { _, result ->
        runOnUiThread {
            if (result == PackageManager.PERMISSION_GRANTED) bindService() else setStatus("Shizuku izni reddedildi.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        status = TextView(this).apply { textSize = 15f; text = "Hazir." }
        root.addView(status)

        versionEdit = field(root, "scrcpy-server surumu", "5.0.1")
        displayEdit = field(root, "Sanal ekran (GENISLIKxYUKSEKLIK/DPI)", "1920x1080/360")
        appEdit = field(root, "Baslatilacak uygulama (?ad ya da paket adi)", "?chrome")

        val permBtn = Button(this).apply { text = "1) Shizuku izni iste / baglan" }
        permBtn.setOnClickListener { connectShizuku() }
        root.addView(permBtn)

        runBtn = Button(this).apply { text = "2) Testi baslat (~15 sn)" }
        runBtn.setOnClickListener { runTest() }
        root.addView(runBtn)

        output = TextView(this).apply {
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(this)
        scroll.addView(output)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        Shizuku.addRequestPermissionResultListener(permListener)
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permListener)
        super.onDestroy()
    }

    private fun field(parent: LinearLayout, hint: String, value: String): EditText {
        val e = EditText(this).apply {
            this.hint = hint
            setText(value)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
        }
        parent.addView(e)
        return e
    }

    private fun setStatus(s: String) {
        status.text = s
    }

    private fun connectShizuku() {
        if (!Shizuku.pingBinder()) {
            setStatus("Shizuku calismiyor. Shizuku uygulamasini acip baslatin (kablosuz hata ayiklama ile).")
            return
        }
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            bindService()
        } else {
            setStatus("Shizuku izni isteniyor...")
            Shizuku.requestPermission(100)
        }
    }

    private fun bindService() {
        setStatus("Shizuku hizmetine baglaniliyor...")
        val args = Shizuku.UserServiceArgs(ComponentName(packageName, UserService::class.java.name))
            .daemon(false)
            .processNameSuffix("m2t")
            .debuggable(false)
            .version(1)
        Shizuku.bindUserService(args, connection)
    }

    private fun runTest() {
        val svc = service
        if (svc == null) {
            setStatus("Once 1) dugmesiyle Shizuku'ya baglanin.")
            return
        }
        val jar = try {
            assets.open("scrcpy-server.jar").use { it.readBytes() }
        } catch (e: Exception) {
            output.text = "assets/scrcpy-server.jar bulunamadi. README'ye bakin."
            return
        }
        val v = versionEdit.text.toString().trim()
        val d = displayEdit.text.toString().trim()
        val a = appEdit.text.toString().trim()
        runBtn.isEnabled = false
        setStatus("Test calisiyor, bekleyin...")
        output.text = ""
        thread {
            val result = try {
                svc.runTest(jar, v, a, d)
            } catch (e: Throwable) {
                "HATA: $e"
            }
            runOnUiThread {
                output.text = result
                setStatus("Test bitti.")
                runBtn.isEnabled = true
            }
        }
    }
}
