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
    private lateinit var startBtn: Button
    private lateinit var scroll: ScrollView

    @Volatile
    private var proc: Process? = null

    private val permListener = Shizuku.OnRequestPermissionResultListener { _, result ->
        runOnUiThread {
            setStatus(
                if (result == PackageManager.PERMISSION_GRANTED) "Shizuku izni verildi."
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
        status = TextView(this).apply { textSize = 15f; text = "Hazir. (surum 0.5)" }
        root.addView(status)

        versionEdit = field(root, "scrcpy-server surumu", "5.0.1")
        displayEdit = field(root, "Sanal ekran (GENISLIKxYUKSEKLIK/DPI)", "1920x1080/360")
        appEdit = field(root, "Baslatilacak uygulamanin PAKET adi", "com.android.chrome")

        val permBtn = Button(this).apply { text = "1) Shizuku izni iste" }
        permBtn.setOnClickListener { checkShizuku() }
        root.addView(permBtn)

        startBtn = Button(this).apply { text = "2) Yayini baslat (TV bekler)" }
        startBtn.setOnClickListener { startStream() }
        root.addView(startBtn)

        val stopBtn = Button(this).apply { text = "3) Durdur" }
        stopBtn.setOnClickListener { stopStream() }
        root.addView(stopBtn)

        output = TextView(this).apply {
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        scroll = ScrollView(this)
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
            setStatus("Shizuku hazir (izin var).")
        } else {
            setStatus("Shizuku izni isteniyor...")
            Shizuku.requestPermission(100)
        }
    }

    private fun stopStream() {
        try { proc?.destroy() } catch (_: Throwable) {}
        proc = null
        setStatus("Durduruldu.")
        startBtn.isEnabled = true
    }

    private fun startStream() {
        if (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            setStatus("Once 1) dugmesiyle Shizuku iznini alin.")
            return
        }
        if (proc != null) {
            setStatus("Yayin zaten calisiyor. Once Durdur'a basin.")
            return
        }
        val apk = applicationInfo.sourceDir
        val v = versionEdit.text.toString().trim()
        val d = displayEdit.text.toString().trim()
        val a = appEdit.text.toString().trim()
        startBtn.isEnabled = false
        setStatus("Yayin hazirlaniyor. TV uygulamasini acin.")
        output.text = ""
        thread {
            try {
                // Shizuku.newProcess ozel (private) oldugu icin yansima ile cagrilir
                val m = Shizuku::class.java.getDeclaredMethod(
                    "newProcess",
                    Array<String>::class.java, Array<String>::class.java, String::class.java
                )
                m.isAccessible = true
                val sh = "export CLASSPATH='$apk'; exec /system/bin/app_process / " +
                        "com.hk.mobile2tvgame.RelayMain '$apk' '$v' '$a' '$d' 27183 2>&1"
                val p = m.invoke(null, arrayOf("sh", "-c", sh), null, null) as Process
                proc = p
                p.inputStream.bufferedReader().forEachLine { line ->
                    runOnUiThread {
                        output.append(line + "\n")
                        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
                    }
                }
                runOnUiThread {
                    output.append("\n(surec bitti)\n")
                    setStatus("Yayin bitti.")
                    startBtn.isEnabled = true
                    proc = null
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    output.append("HATA: $e\n")
                    (e.cause)?.let { output.append("Neden: $it\n") }
                    setStatus("Hata (asagiya bakin).")
                    startBtn.isEnabled = true
                    proc = null
                }
            }
        }
    }
}
