package vip.mystery0.pixel.meter.ui.health

import android.app.Activity
import android.os.Bundle

/**
 * Required by Health Connect — shown when the user opens the HC permissions screen
 * and taps "See app's privacy policy". Keep it minimal; just finish.
 * You can later replace with a proper privacy policy WebView.
 */
class HealthConnectPermissionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }
}
