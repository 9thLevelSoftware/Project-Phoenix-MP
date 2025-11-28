package com.devil.phoenixproject.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Typography using system font - Material 3 Expressive (Larger, Bolder)
val Typography = Typography(
    // Display styles (large headers) - Expressive: Larger and bolder
    displayLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 64.sp,  // Expressive: Increased from 57sp
        lineHeight = 72.sp, // Expressive: Increased from 64sp
        letterSpacing = (-0.25).sp
    ),
    displayMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 50.sp,  // Expressive: Increased from 45sp
        lineHeight = 58.sp, // Expressive: Increased from 52sp
        letterSpacing = 0.sp
    ),

    // Headline styles (screen titles) - Expressive: Larger and bolder
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,  // Expressive: Changed from SemiBold to Bold
        fontSize = 36.sp,  // Expressive: Increased from 32sp
        lineHeight = 44.sp, // Expressive: Increased from 40sp
        letterSpacing = 0.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,  // Expressive: Changed from SemiBold to Bold
        fontSize = 32.sp,  // Expressive: Increased from 28sp
        lineHeight = 40.sp, // Expressive: Increased from 36sp
        letterSpacing = 0.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,  // Expressive: Changed from SemiBold to Bold
        fontSize = 28.sp,  // Expressive: Increased from 24sp
        lineHeight = 36.sp, // Expressive: Increased from 32sp
        letterSpacing = 0.sp
    ),

    // Title styles (card headers, section titles) - Expressive: Larger
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,  // Expressive: Changed from SemiBold to Bold
        fontSize = 24.sp,  // Expressive: Increased from 22sp
        lineHeight = 32.sp, // Expressive: Increased from 28sp
        letterSpacing = 0.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),

    // Body styles (content text) - Expressive: Larger
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 18.sp,  // Expressive: Increased from 16sp
        lineHeight = 26.sp, // Expressive: Increased from 24sp
        letterSpacing = 0.5.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,  // Expressive: Increased from 14sp
        lineHeight = 22.sp, // Expressive: Increased from 20sp
        letterSpacing = 0.25.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp
    ),

    // Label styles (buttons, tabs, form labels)
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    )
)
