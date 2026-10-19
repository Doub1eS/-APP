package com.lianbei.daily

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private var showPayments by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        CaptureStore.migrateLegacy(applicationContext)
        showPayments = intent.getBooleanExtra("payments", false)
        val db = DailyDatabase.get(applicationContext)
        val dao = db.records()
        // The capture process only queues payments; this process owns Room and writes them.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                // Heal a background watch that a force stop or a reboot removed.
                runCatching {
                    val state = withContext(Dispatchers.IO) { CaptureStore.state(applicationContext) }
                    if (state["enabled"] == "true") {
                        withContext(Dispatchers.IO) {
                            // The watch service also repairs a listener binding that died with an old process.
                            if (state["watch_wanted"] != "false") PaymentWatchService.start(applicationContext)
                            else if (!healed) {
                                healed = true
                                PaymentListener.requestRebindNow(applicationContext)
                            }
                        }
                    }
                }
                while (true) {
                    runCatching { withContext(Dispatchers.IO) { CaptureDrain.drain(applicationContext, dao) } }
                    delay(2_000)
                }
            }
        }
        setContent {
            LianbeiTheme {
                DailyApp(dao, showPayments, onPaymentsConsumed = { showPayments = false })
            }
        }
    }

    /** Tapping the pending payment notification must open the inbox even when the app is running. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("payments", false)) showPayments = true
    }

    private companion object {
        /** One blind rebind per UI process is enough; the watch service repairs itself afterwards. */
        @Volatile var healed = false
    }
}
