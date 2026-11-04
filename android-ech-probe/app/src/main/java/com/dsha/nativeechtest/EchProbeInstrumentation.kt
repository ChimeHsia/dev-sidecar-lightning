package com.dsha.nativeechtest

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.util.Log

/** Same-package instrumentation, avoiding AndroidX/test-runner or browser dependencies. */
class EchProbeInstrumentation : Instrumentation() {
    private var inputs = Bundle()

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        inputs = arguments?.let { Bundle(it) } ?: Bundle()
        start()
    }

    override fun onStart() {
        val result = EchPlatformProbe.run(inputs)
        val json = result.toString()
        Log.i(EchPlatformProbe.LOG_TAG, json)
        finish(
            if (result.optBoolean("pass")) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            Bundle().apply { putString("result_json", json) }
        )
    }
}
