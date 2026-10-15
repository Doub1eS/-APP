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
        val db = Room.databaseBuilder(applicationContext, DailyDatabase::class.java, "daily.db").build()
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(
                primary = Color(0xFF24634C), secondary = Color(0xFF766448),
                background = Color(0xFFF8F7F2), surface = Color(0xFFF8F7F2),
                surfaceVariant = Color(0xFFEDEFE5), onBackground = Color(0xFF202B25)
            )) { DailyApp(db.records()) }
        }
    }
}
