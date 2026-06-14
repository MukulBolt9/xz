package vip.mystery0.pixel.meter.ui.health

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Required by Health Connect:
 * - Android 13 and below: launched via ACTION_SHOW_PERMISSIONS_RATIONALE
 * - Android 14+: launched via the activity-alias with VIEW_PERMISSION_USAGE
 *
 * Shows a simple privacy policy explaining how NowBrief uses health data.
 * Without this activity properly implemented, HC won't show NowBrief in its
 * "App permissions" list on any Android version.
 */
class HealthConnectPermissionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .padding(24.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = "NowBrief — Health Data",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Text(
                            text = "How NowBrief uses your health data",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )

                        Text(
                            text = "NowBrief reads the following data from Health Connect to show you a personalised daily brief:",
                            fontSize = 14.sp,
                            lineHeight = 22.sp
                        )

                        listOf(
                            "👟 Steps — to show your daily step count and distance",
                            "😴 Sleep — to show sleep duration and stages (deep/REM/light)",
                            "❤️ Heart Rate — to show resting, current, and max BPM",
                            "🔥 Calories — to show active and total calories burned",
                            "📍 Distance — GPS-accurate distance from walks and runs",
                            "💨 Blood Oxygen (SpO₂) — to show oxygen saturation"
                        ).forEach { item ->
                            Text(
                                text = item,
                                fontSize = 14.sp,
                                lineHeight = 20.sp
                            )
                        }

                        Divider()

                        Text(
                            text = "Data privacy",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold
                        )

                        Text(
                            text = "• All health data is read locally on your device only\n" +
                                   "• No health data is uploaded to any server\n" +
                                   "• Data is only used to display stats in the Vitals tab\n" +
                                   "• NowBrief never writes health data to Health Connect\n" +
                                   "• You can revoke access at any time in Health Connect settings",
                            fontSize = 14.sp,
                            lineHeight = 22.sp
                        )

                        Button(
                            onClick = { finish() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Got it")
                        }
                    }
                }
            }
        }
    }
}
