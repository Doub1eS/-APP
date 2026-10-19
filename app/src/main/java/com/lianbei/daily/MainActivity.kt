package com.lianbei.daily

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.*
import androidx.compose.ui.graphics.Color
import androidx.room.Room

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val db = DailyDatabase.get(applicationContext)
        setContent {
            LianbeiTheme { DailyApp(db.records(), intent.getBooleanExtra("payments", false)) }
        }
    }
}
