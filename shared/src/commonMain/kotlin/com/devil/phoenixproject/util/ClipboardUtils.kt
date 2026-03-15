package com.devil.phoenixproject.util

import androidx.compose.runtime.Composable

@Composable
expect fun rememberCopyTextToClipboard(): (String) -> Unit
