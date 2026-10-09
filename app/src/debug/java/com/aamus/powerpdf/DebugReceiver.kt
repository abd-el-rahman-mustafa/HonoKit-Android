package com.aamus.powerpdf

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Debug builds only. Drives the app from the PC, because the test phone refuses injected taps:
 *   adb shell am broadcast -n com.aamus.honokit.dev/com.aamus.powerpdf.DebugReceiver --es cmd <cmd> --es value <value>
 * Commands are listed in docs/testing.md.
 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val cmd = intent.getStringExtra("cmd") ?: return
        val value = intent.getStringExtra("value") ?: ""
        Log.i("PowerPDF", "debug: $cmd $value")
        when (cmd) {
            "roundtrip" -> RoundTrip.start(context.applicationContext, value)
            "ocr" -> OcrSpike.start(context.applicationContext, value)
            "recframes" -> if (value == "off") com.aamus.powerpdf.ui.tools.FrameRecorder.on = false else com.aamus.powerpdf.ui.tools.FrameRecorder.start(context.applicationContext)
            else -> DebugBus.send(cmd, value)
        }
    }
}
