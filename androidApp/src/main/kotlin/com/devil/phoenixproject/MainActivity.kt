package com.devil.phoenixproject

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.devil.phoenixproject.App
import com.devil.phoenixproject.presentation.components.RequireBlePermissions
import com.devil.phoenixproject.util.ActivityHolder

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ActivityHolder.registerActivity(this)
        enableEdgeToEdge()
        setContent {
            // Require BLE permissions before showing the app
            // Permission screens have their own theme, App provides its own theme
            RequireBlePermissions {
                App()
            }
        }
    }
}