package com.attendancefr

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.attendancefr.ui.navigation.AttendanceNavHost
import com.attendancefr.ui.theme.AttendanceFrTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AttendanceFrTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AttendanceNavHost()
                }
            }
        }
    }
}
