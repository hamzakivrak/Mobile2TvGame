package com.hk.mobile2tvgame

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
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

    private val permListener = Shizuku.OnRequestPermissionResultListener { _, result ->
        runOnUiThread {
            setStatus(
                if (result == PackageManager.PERMISSION_GRANTED) "Shizuku izni verildi. Testi baslatabilirsiniz."
                else "Shizuku izni reddedildi."
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        status = TextView(this).apply { textSize = 15f; text = "Hazir. (surum 0.4)" }
        root.addView(status)

        versionEdit = field(root, "scrcpy-server surumu", "5.0.1")
        displayEdit = field(root, "Sanal ekran (GENISLIKxYUKSEKLIK/DPI)", "1920x1080/360")
        appEdit = field(root, "Baslatilacak uygulamanin PAKET adi", "com.android.chrome")

        val permBtn = Button(this).apply { text = "1) Shizuku izni iste" }
        permBtn.setOnClickListener { checkShizuku() }
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

    private fun checkShizuku() {
        if (!Shizuku.pingBinder()) {
            setStatus("Shizuku calismiyor. Shizuku uygulamasini acip baslatin.")
            return
        }
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            setStatus("Shizuku hazir (izin var). Testi baslatabilirsiniz.")
        } else {
            setStatus("Shizuku izni isteniyor...")
            Shizuku.requestPermission(100)
        }
    }

    private fun runTest() {
        if (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            setStatus("Once 1) dugmesiyle Shizuku iznini alin.")
            return
        }
        val apk = applicationInfo.sourceDir
        val v = versionEdit.text.toString().trim()
        val d = displayEdit.text.toString().trim()
        val a = appEdit.text.toString().trim()
        runBtn.isEnabled = false
        setStatus("Test calisiyor, bekleyin...")
        output.text = ""
        thread {
            try {
                // Shizuku.newProcess ozel (private) oldugu icin yansima ile cagrilir
                val m = Shizuku::class.java.getDeclaredMethod(
                    "newProcess",
                    Array<String>::class.java, Array<String>::class.java, String::class.java
                )
                m.isAccessible = true
                val sh = "CLASSPATH='$apk' /system/bin/app_process / com.hk.mobile2tvgame.RelayMain " +
                        "'$apk' '$v' '$a' '$d' 2>&1"
                val proc = m.invoke(null, arrayOf("sh", "-c", sh), null, null) as Process
                proc.inputStream.bufferedReader().forEachLine { line ->
                    runOnUiThread { output.append(line + "\n") }
                }
                runOnUiThread {
                    output.append("\n(surec kodu: ${try { proc.waitFor() } catch (e: Exception) { e.toString() }})\n")
                    setStatus("Test bitti.")
                    runBtn.isEnabled = true
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    output.append("HATA: $e\n")
                    (e.cause)?.let { output.append("Neden: $it\n") }
                    setStatus("Hata (asagiya bakin).")
                    runBtn.isEnabled = true
                }
            }
        }
    }
}
