/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kitsumed.shizucallrecorder.AppUrls
import com.kitsumed.shizucallrecorder.BuildConfig
import com.kitsumed.shizucallrecorder.R
import com.kitsumed.shizucallrecorder.ui.theme.ShizuCallRecorderTheme
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay


/**
 * One-time disclaimer screen shown on first launch.
 *
 * @param onContinue Called when the user presses the enabled "Continue" button. The caller
 *                   ([AppNavigation]) then persists the acceptance flag and triggers a refresh
 *                   (recompose) so the router advances to the Permissions screen.
 * @param modifier   Optional size/position modifier forwarded to the root [Surface].
 */
@Composable
fun DisclaimerScreen(onContinue: () -> Unit, modifier: Modifier = Modifier) {
    // [rememberSaveable] allow for the values to survive configuration
    // changes (like when recompose is triggered by a screen rotation)
    var hasAccepted by rememberSaveable { mutableStateOf(false) }
    var timeLeft by rememberSaveable { mutableIntStateOf(if (BuildConfig.DEBUG) 4 else 30) }

    val scrollState = rememberScrollState()
    val hasScrolledToBottom by remember {
        // Only trigger update (recompose) when boolean operation result change
        derivedStateOf {
            !scrollState.canScrollForward || scrollState.maxValue == 0
        }
    }

    // Countdown timer: decrements timeLeft once per second until it reaches 0.
    LaunchedEffect(Unit) {
        while (timeLeft > 0) {
            delay(1000L)
            timeLeft--
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Title
            Text(
                text = stringResource(R.string.disclaimer_title),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground
            )

            // Introduction paragraph with a hyperlinked "Wiki" keyword
            val links = mapOf(
                stringResource(R.string.disclaimer_wiki_link_KEYWORD) to AppUrls.GITHUB_WIKI
            )

            HyperlinkText(stringResource(R.string.disclaimer_introduction), links)

            Spacer(modifier = Modifier.height(1.dp))

            // Elevated card gives visual depth to the scrollable disclaimer body.
            ElevatedCard(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                SelectionContainer {
                    Text(
                        text = stringResource(R.string.disclaimer_body),
                        modifier = Modifier
                            .verticalScroll(scrollState)
                            .padding(16.dp),
                        style = MaterialTheme.typography.bodyLarge.copy(
                            lineBreak = LineBreak.Paragraph
                        )
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // Acknowledgement checkbox, only becomes interactive after the user scrolls down.
            AnimatedContent(
                targetState = hasScrolledToBottom,
                transitionSpec = {
                    val enterFade = fadeIn(tween(600))
                    val exitFade = fadeOut(tween(500))

                    enterFade togetherWith exitFade                },
                label = "CheckboxUnlockTransition"
            ) { unlocked ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = hasAccepted,
                            onValueChange = { if (unlocked) hasAccepted = it },
                            role = Role.Checkbox,
                            enabled = unlocked
                        )
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Checkbox(
                        checked = hasAccepted,
                        onCheckedChange = null, // Handled by the Row's toggleable modifier above.
                        enabled = unlocked
                    )
                    Text(
                        text = stringResource(R.string.disclaimer_checkbox_label),
                        style = MaterialTheme.typography.bodyMedium,
                        // Dim the label to signal it's not yet interactive.
                        color = if (unlocked) MaterialTheme.colorScheme.onBackground
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
            }

            // Continue button, enabled only when all three gates are satisfied.
            val canContinue = hasAccepted && hasScrolledToBottom && timeLeft == 0

            Button(
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth(),
                enabled = canContinue,
            ) {
                if (timeLeft > 0) {
                    Text(text = stringResource(R.string.disclaimer_wait, timeLeft))
                }
                else if (!hasScrolledToBottom) {
                    Text(text = stringResource(R.string.disclaimer_must_read))
                }
                else {
                    Text(text = stringResource(R.string.general_continue))
                }
            }
        }
    }
}


/**
 * Renders [fullText] with inline clickable hyperlinks.
 *
 * Each entry in [links] maps a keyword substring to a URL. When the keyword is found in
 * [fullText], it is styled and tapping it opens the URL via [LocalUriHandler].
 *
 * @param fullText The complete plain-text string to display.
 * @param links    A map of keyword → URL pairs (e.g. `"Wiki" to "https://…"`).
 * @param modifier Optional layout modifier for the [Text] composable.
 */
@Composable
fun HyperlinkText(
    fullText: String,
    links: Map<String, String>,
    modifier: Modifier = Modifier
) {
    val uriHandler = LocalUriHandler.current

    // Build a LinkInteractionListener that opens the URL stored in the Clickable tag.
    val listener = LinkInteractionListener { link ->
        if (link is LinkAnnotation.Clickable) {
            uriHandler.openUri(link.tag)
        }
    }

    val annotatedText = buildAnnotatedString {
        append(fullText)

        links.forEach { (keyword, url) ->
            val startIndex = fullText.indexOf(keyword)
            if (startIndex != -1) {
                val endIndex = startIndex + keyword.length

                // addLink attaches a Clickable annotation over the keyword range.
                addLink(
                    clickable = LinkAnnotation.Clickable(
                        tag = url,
                        styles = TextLinkStyles(
                            style = SpanStyle(
                                textDecoration = TextDecoration.Underline,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                            )
                        ),
                        linkInteractionListener = listener
                    ),
                    start = startIndex,
                    end = endIndex
                )
            }
        }
    }

    Text(text = annotatedText, style = MaterialTheme.typography.bodyMedium, modifier = modifier)
}

@Preview(showBackground = true)
@Composable
private fun DisclaimerScreenPreview() {
    ShizuCallRecorderTheme {
        DisclaimerScreen(onContinue = {})
    }
}
