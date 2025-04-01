package io.openvidu.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SplashActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // 🛑 This is the key line to disable Android 12+ trimmed splash!
        installSplashScreen()

        super.onCreate(savedInstanceState)

        // Optional edge-to-edge (just keeps UI modern)
        enableEdgeToEdge()

        // Your custom splash layout with logo
        setContentView(R.layout.activity_splash)

        // Show for 2 seconds then go to main screen
        lifecycleScope.launch {
            delay(2000)
            startActivity(Intent(this@SplashActivity, MainActivity::class.java))
            finish()
        }
    }
}
