package com.hk.mobile2tvgame

import android.app.Activity
import android.content.Intent
import android.os.Bundle

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Ilk ekrani atlayarak dogrudan KumandaActivity'yi baslat
        startActivity(Intent(this, KumandaActivity::class.java))
        finish()
    }
}