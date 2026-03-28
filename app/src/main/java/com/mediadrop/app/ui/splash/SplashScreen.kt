package com.mediadrop.app.ui.splash

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mediadrop.app.ui.theme.*
import kotlinx.coroutines.delay

@Composable
fun SplashScreen(onFinished: () -> Unit) {
    var startAnimation by remember { mutableStateOf(false) }

    val logoAlpha by animateFloatAsState(
        targetValue = if (startAnimation) 1f else 0f,
        animationSpec = tween(800, easing = EaseOutCubic),
        label = "logo_alpha"
    )
    val logoScale by animateFloatAsState(
        targetValue = if (startAnimation) 1f else 0.6f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "logo_scale"
    )
    val taglineAlpha by animateFloatAsState(
        targetValue = if (startAnimation) 1f else 0f,
        animationSpec = tween(600, delayMillis = 500, easing = EaseOutCubic),
        label = "tagline_alpha"
    )
    val creditAlpha by animateFloatAsState(
        targetValue = if (startAnimation) 1f else 0f,
        animationSpec = tween(600, delayMillis = 900, easing = EaseOutCubic),
        label = "credit_alpha"
    )

    LaunchedEffect(Unit) {
        startAnimation = true
        delay(2200)
        onFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(Navy800, Navy950),
                    radius = 1200f
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        // Glow behind logo
        Box(
            modifier = Modifier
                .size(200.dp)
                .alpha(logoAlpha * 0.4f)
                .background(
                    Brush.radialGradient(listOf(GoldGlow, androidx.compose.ui.graphics.Color.Transparent)),
                )
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Logo letters
            Text(
                text = "DC",
                style = MaterialTheme.typography.displayLarge.copy(
                    fontSize = 96.sp,
                    fontWeight = FontWeight.Black,
                    brush = Brush.linearGradient(listOf(Gold400, Gold600))
                ),
                modifier = Modifier
                    .scale(logoScale)
                    .alpha(logoAlpha)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Download anything. Fast.",
                style = MaterialTheme.typography.titleMedium,
                color = Gold400.copy(alpha = 0.85f),
                modifier = Modifier.alpha(taglineAlpha),
                textAlign = TextAlign.Center
            )
        }

        // Made with love credit at bottom
        Text(
            text = "Made with ❤️ by SONU VERMA",
            style = MaterialTheme.typography.labelMedium,
            color = Gold400.copy(alpha = 0.6f),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 40.dp)
                .alpha(creditAlpha),
            textAlign = TextAlign.Center
        )
    }
}
