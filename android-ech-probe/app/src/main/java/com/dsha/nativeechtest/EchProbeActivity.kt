package com.dsha.nativeechtest

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import java.util.concurrent.Executors

class EchProbeActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var output: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        output = TextView(this).apply {
            textSize = 16f
            setPadding(24, 24, 24, 24)
        }
        setContentView(output)
        launch(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        launch(intent)
    }

    private fun launch(intent: Intent) {
        val args = intent.extras?.let { Bundle(it) }
        if (args == null || !args.containsKey("config_b64")) {
            output.text = "Native ECH platform probe\nWaiting for host-generated loopback test inputs."
            return
        }
        output.text = "Running strict ECH loopback probe..."
        worker.execute {
            val result = EchPlatformProbe.run(args).toString()
            Log.i(EchPlatformProbe.LOG_TAG, result)
            runOnUiThread { if (!isDestroyed) output.text = result }
        }
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }
}
