package com.devil.phoenixproject

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import com.devil.phoenixproject.di.initKoin

fun main() {
    initKoin()
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Vitruvian Project Phoenix",
            state = WindowState(size = DpSize(1024.dp, 768.dp))
        ) {
            App()
        }
    }
}