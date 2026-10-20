package com.maiconerick.xgamelauncher.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maiconerick.xgamelauncher.ui.theme.LocalNeon
import kotlinx.coroutines.delay

@Composable
fun BootScreen(onReady: () -> Unit) {
    val neon = LocalNeon.current
    // The launcher gets killed while heavy emulators run on 2 GB devices, so every return
    // is a cold start — don't add a long splash on top of that.
    val lowPower = com.maiconerick.xgamelauncher.ui.LocalDeviceProfile.current.lowPower
    LaunchedEffect(Unit) {
        delay(if (lowPower) 150 else 900)
        onReady()
    }
    val transition = rememberInfiniteTransition(label = "boot-shimmer")
    val shimmer by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmer",
    )
    Box(Modifier.fillMaxSize().background(neon.surface)) {
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            com.maiconerick.xgamelauncher.ui.components.SpaceInvader(height = 56.dp)
            Text(
                text = "xGameLauncher",
                color = neon.inkDim,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 6.sp,
            )
            Spacer(Modifier.height(20.dp))
            Box(
                modifier = Modifier
                    .width(240.dp)
                    .height(2.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(Color(0x14FFFFFF)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.horizontalGradient(
                                0f to Color.Transparent,
                                (shimmer - 0.15f).coerceIn(0f, 1f) to Color.Transparent,
                                shimmer.coerceIn(0f, 1f) to neon.accent,
                                (shimmer + 0.15f).coerceIn(0f, 1f) to Color.Transparent,
                                1f to Color.Transparent,
                            )
                        )
                )
            }
        }
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(neon.accent),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = "MaiconErick · ${com.maiconerick.xgamelauncher.ui.DeviceProfile.defaultDeviceName().uppercase()} · v0.1",
                color = neon.inkMuted,
                fontSize = 10.sp,
                letterSpacing = 0.6.sp,
            )
        }
    }
}
