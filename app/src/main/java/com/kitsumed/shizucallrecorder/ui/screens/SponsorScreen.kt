/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 * Copyright (C) 2026-present kitsumed (Med)
 * This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 * The full license text is available in the LICENSE file at the root of this project.
 * This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.ui.screens

import android.content.pm.PackageManager
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kitsumed.shizucallrecorder.R
import com.kitsumed.shizucallrecorder.system.openGithub
import com.kitsumed.shizucallrecorder.system.openGithubSponsor
import com.kitsumed.shizucallrecorder.ui.theme.ShizucallrecorderTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

private val GithubPink = Color(0xFFDB61A2)
private val GithubGold = Color(0xFFE3B341)

@Composable
private fun StaggeredFadePop(
    index: Int,
    delayPerItem: Int = 80, // Milliseconds between every card animation
    content: @Composable () -> Unit
) {
    val isPreview = LocalInspectionMode.current

    // Shared animation values
    val alpha = remember { Animatable(if (isPreview) 1f else 0f) }
    val scale = remember { Animatable(if (isPreview) 1f else 0.85f) } // Starts slightly smaller
    val translateY = remember { Animatable(if (isPreview) 0f else 100f) } // Starts lower down

    LaunchedEffect(Unit) {
        if (!isPreview) {
            // Wait for this item specific turn in the queue
            delay((index * delayPerItem).toLong())

            // Launch all animations in parallel
            launch {
                alpha.animateTo(
                    targetValue = 1f,
                    animationSpec = spring(stiffness = Spring.StiffnessLow)
                )
            }
            launch {
                scale.animateTo(
                    targetValue = 1f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessLow
                    )
                )
            }
            launch {
                translateY.animateTo(
                    targetValue = 0f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessLow
                    )
                )
            }
        }
    }

    Box(
        modifier = Modifier.graphicsLayer {
            this.alpha = alpha.value
            this.scaleX = scale.value
            this.scaleY = scale.value
            this.translationY = translateY.value
        }
    ) {
        content()
    }
}

/**
 * A friendly, non-intrusive screen explaining ways to support the project and the developer.
 *
 * @param onDismiss Called when the user clicks the "Maybe Later" or dismiss button.
 * @param modifier  Optional size/position modifier forwarded to the root [Surface].
 */
@Composable
fun SponsorScreen(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    // Calculate days since first install
    val daysUsed by produceState(initialValue = 0L, key1 = context) {
        value = withContext(Dispatchers.IO) {
            try {
                val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                val diffMs = System.currentTimeMillis() - packageInfo.firstInstallTime
                TimeUnit.MILLISECONDS.toDays(diffMs).coerceAtLeast(1)
            } catch (e: PackageManager.NameNotFoundException) {
                0L // Fallback to 0
            }
        }
    }

    // Heart Pulse Animation
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val heartScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 3600

                // First Beat (Lub)
                1.0f at 0 using FastOutSlowInEasing
                1.4f at 150 using FastOutSlowInEasing
                1.0f at 300 using LinearEasing

                // Second Beat (Dub)
                1.4f at 450 using FastOutSlowInEasing
                1.0f at 600 using LinearEasing

                // Rest period (3000ms pause)
                1.0f at 3600
            },
            repeatMode = RepeatMode.Restart
        ),
        label = "heartPulse"
    )

    Surface(
        modifier = modifier
            .navigationBarsPadding()
            .fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .padding(top = 24.dp, bottom = 16.dp),
        ) {
            // Header
            StaggeredFadePop(index = 0) {
                Text(
                    text = stringResource(R.string.sponsor_title),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Scrollable Content
            Column(
                modifier = Modifier
                    .weight(1f)
                    // The fading edge mask logic
                    .graphicsLayer { alpha = 0.99f } // Forces composition layer for BlendMode
                    .drawWithCache {
                        val fadeHeight = 66.dp.toPx() // Starts a bit earlier
                        val fadeBrush = Brush.verticalGradient(
                            // Adding a midway stop creates a progressive, curved fade out
                            0.0f to Color.Black,
                            0.4f to Color.Black.copy(alpha = 0.4f),
                            1.0f to Color.Transparent,
                            startY = (size.height - fadeHeight).coerceAtLeast(0f),
                            endY = size.height
                        )
                        onDrawWithContent {
                            drawContent()
                            drawRect(
                                brush = fadeBrush,
                                blendMode = BlendMode.DstIn // Keeps content where mask is solid, fades where transparent
                            )
                        }
                    }
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Bio Card
                StaggeredFadePop(index = 1) {
                    BioCard()
                }

                StaggeredFadePop(index = 2) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = stringResource(R.string.sponsor_days_used, daysUsed),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }

                // Ways to Support Section
                StaggeredFadePop(index = 3) {
                    Text(
                        text = stringResource(R.string.sponsor_way_to_help),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp, start = 8.dp)
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    StaggeredFadePop(index = 4) {
                        SupportOption(
                            icon = Icons.Default.Favorite,
                            title = stringResource(R.string.sponsor_way_github_sponsor_title),
                            description = stringResource(R.string.sponsor_way_github_sponsor_description),
                            iconTint = GithubPink
                        )
                    }
                    StaggeredFadePop(index = 5) {
                        SupportOption(
                            icon = Icons.Default.Star,
                            title = stringResource(R.string.sponsor_way_github_star_title),
                            description = stringResource(R.string.sponsor_way_github_star_description),
                            iconTint = GithubGold
                        )
                    }
                    StaggeredFadePop(index = 6) {
                        SupportOption(
                            icon = Icons.Default.Code,
                            title = stringResource(R.string.sponsor_way_contribute_title),
                            description = stringResource(R.string.sponsor_way_contribute_description)
                        )
                    }
                    StaggeredFadePop(index = 7) {
                        SupportOption(
                            icon = Icons.Default.Share,
                            title = stringResource(R.string.sponsor_way_share_title),
                            description = stringResource(R.string.sponsor_way_share_description)
                        )
                    }
                }

                // Extra spacer so the bottom item clears the fade mask completely when fully scrolled down
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Footer / Actions
            StaggeredFadePop(index = 8) {
                Column {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Button(
                            onClick = { context.openGithubSponsor() },
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Icon(
                                imageVector = Icons.Default.FavoriteBorder,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(24.dp)
                                    .graphicsLayer {
                                        scaleX = heartScale
                                        scaleY = heartScale
                                    },
                                tint = GithubPink
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.sponsor_github_sponsor_button),
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { context.openGithub() },
                                modifier = Modifier.weight(1f),
                                shape = MaterialTheme.shapes.medium
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Star,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = GithubGold
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(text = stringResource(R.string.sponsor_github_star_button))
                            }

                            TextButton(
                                onClick = onDismiss,
                                modifier = Modifier.weight(1f),
                                shape = MaterialTheme.shapes.medium
                            ) {
                                Text(text = stringResource(R.string.sponsor_maybe_later_button))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SupportOption(
    icon: ImageVector,
    title: String,
    description: String,
    iconTint: Color = MaterialTheme.colorScheme.primary
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            headlineContent = { Text(title, fontWeight = FontWeight.SemiBold) },
            supportingContent = {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall.copy(
                        lineBreak = LineBreak.Paragraph,
                        hyphens = Hyphens.Auto
                    )
                )
            },
            leadingContent = {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(30.dp),
                )
            }
        )
    }
}

@Composable
fun BioCard() {
    val shineProgress = remember { Animatable(0f) }
    val bodyTextColor = MaterialTheme.colorScheme.onSurfaceVariant
    val flashColorGlobal = MaterialTheme.colorScheme.secondary.copy(alpha = 0.28f)

    LaunchedEffect(Unit) {
        delay(800L) // Initial delay before the first shine starts
        while (true) {
            // Instantly reset to the start
            shineProgress.snapTo(0f)

            // Animate across the card smoothly
            shineProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = 1400,
                    easing = LinearEasing
                )
            )

            delay(14000L) // Pause for 14 seconds before the next shine
        }
    }

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .drawWithCache {
                val width = size.width
                val height = size.height

                val xStart = (width * 2 * shineProgress.value) - width
                val yStart = (height * 2 * shineProgress.value) - height

                val gradientBrush = Brush.linearGradient(
                    colors = listOf(Color.Transparent, flashColorGlobal, Color.Transparent),
                    start = Offset(xStart, yStart),
                    end = Offset(xStart + width, yStart + height)
                )

                onDrawWithContent {
                    drawContent()

                    val outline = RoundedCornerShape(24.dp).createOutline(size, layoutDirection, this)
                    drawOutline(
                        outline = outline,
                        brush = gradientBrush
                    )
                }
            },
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.sponsor_body_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.sponsor_body),
                color = bodyTextColor,
                style = MaterialTheme.typography.bodyMedium.copy(
                    lineBreak = LineBreak.Paragraph,
                    hyphens = Hyphens.Auto
                )
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SponsorScreenPreview() {
    ShizucallrecorderTheme(darkTheme = true, dynamicColor = true) {
        SponsorScreen(onDismiss = {})
    }
}