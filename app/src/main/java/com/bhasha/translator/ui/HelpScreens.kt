// SPDX-License-Identifier: AGPL-3.0-or-later
package com.bhasha.translator.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bhasha.translator.BhashaApp
import com.bhasha.translator.core.ThemeMode
import com.bhasha.translator.core.formatBytes
import com.bhasha.translator.update.UpdateState
import com.bhasha.translator.update.Updater
import kotlinx.coroutines.launch

private class TourPage(val icon: ImageVector, val title: String, val text: String)

private val tourPages = listOf(
    TourPage(
        AppIcons.Translate, "Welcome to Bhasha",
        "Bhasha listens to Hindi, Bengali or Kannada and shows it in English as people speak. " +
            "It also teaches you the three languages.\n\nEverything happens on this phone. " +
            "Nothing you say or hear is sent anywhere.",
    ),
    TourPage(
        AppIcons.Download, "First, download the models",
        "The app's \"ears\" and \"interpreter\" are AI models. They are too large to fit inside the app, " +
            "so you download them once, over Wi-Fi, from the Models tab:\n\n" +
            "•  A speech pack for each language you need (198 MB each). It turns speech into text.\n" +
            "•  The IndicTrans2 translator (235 MB). It turns that text into English.\n\n" +
            "After that the app works with no internet connection.",
    ),
    TourPage(
        AppIcons.Mic, "Translate live",
        "Open Translate, choose the language being spoken and tap Start listening. " +
            "Put the phone near the speaker.\n\n" +
            "The words appear while they are being said, with the English underneath. " +
            "When the sentence ends, both are corrected and finalised.\n\n" +
            "Turn on \"Speak English aloud\" to hear the translation through your headphones or speaker.",
    ),
    TourPage(
        AppIcons.Notes, "Or just transcribe",
        "Switch from Translate to Transcribe to get the words in their own language, written in their own script, " +
            "with no translation. This only needs a speech pack.\n\n" +
            "Use Copy to take the text into another app.",
    ),
    TourPage(
        AppIcons.School, "Learn the language",
        "The Learn tab has a full course for each language: the script, grammar explained in English, " +
            "hundreds of phrases you can listen to, quizzes, and speaking practice that checks your pronunciation.\n\n" +
            "You can open this tour and the help page again from the menu at the top right.",
    ),
)

/** The first-run walkthrough; also reachable from the menu. */
@Composable
fun TourDialog(onClose: () -> Unit) {
    val pager = rememberPagerState { tourPages.size }
    val scope = rememberCoroutineScope()
    val last = pager.currentPage == tourPages.lastIndex
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().padding(24.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onClose) { Text(if (last) "Close" else "Skip") }
                }
                HorizontalPager(pager, Modifier.weight(1f)) { index ->
                    val page = tourPages[index]
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                            Icon(
                                page.icon, null, Modifier.padding(24.dp).size(56.dp),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                        Spacer(Modifier.height(28.dp))
                        Text(page.title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(16.dp))
                        Text(page.text, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        tourPages.indices.forEach { i ->
                            Surface(
                                shape = CircleShape,
                                color = if (i == pager.currentPage) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant,
                                modifier = Modifier.size(8.dp),
                            ) {}
                        }
                    }
                    if (pager.currentPage > 0) {
                        TextButton(onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } }) {
                            Text("Back")
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                    Button(onClick = {
                        if (last) onClose() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                    }) { Text(if (last) "Get started" else "Next") }
                }
            }
        }
    }
}

private val helpSections = listOf(
    "What Bhasha does" to
        "Bhasha is a live translator and a language tutor for Hindi, Bengali and Kannada.\n\n" +
        "•  Translate: listens through the microphone and shows English captions as people speak.\n" +
        "•  Transcribe: writes down what is said in its own language, without translating.\n" +
        "•  Learn: courses with script lessons, grammar, phrases, quizzes and speaking practice.",
    "Why you have to download models" to
        "Speech recognition and translation are done by AI models that run on this phone instead of on a server. " +
        "That is what keeps your conversations private and lets the app work offline, but the models are large: " +
        "about 430 MB for one language, more than an app file should hold. So the app itself is small and you " +
        "download only what you need, once, from the Models tab. Use Wi-Fi.\n\n" +
        "•  Speech pack (198 MB per language): hears one language and writes it as text. Needed for Translate, " +
        "Transcribe and speaking practice.\n" +
        "•  Voice detector (1 MB, downloaded with the first pack): notices when someone starts and stops speaking.\n" +
        "•  IndicTrans2 translator (235 MB): translates the text into English. Not needed for Transcribe.\n" +
        "•  Gemma 4 E2B (optional, 2.8 GB): powers the tutor's explanations and conversation practice in Learn.\n" +
        "•  Whisper (optional, 0.2 to 1.1 GB): only for Auto-detect, when you do not know which language will be spoken.\n\n" +
        "You can delete any model in the Models tab to free the space and download it again later.",
    "Translating a conversation" to
        "1.  Open Translate and choose the language being spoken.\n" +
        "2.  Tap Start listening and place the phone near the speaker.\n" +
        "3.  Words appear in grey while the person is still speaking; this is a live preview and may change. " +
        "When they pause, the sentence and its English translation are finalised.\n\n" +
        "The first sentence after starting takes longer because the models are being loaded into memory. " +
        "The bar above the Start button moves when the app hears a voice.\n\n" +
        "Bhasha keeps listening with the screen off or while you use other apps. Stop it from the notification or the Stop button.",
    "Hearing the translation" to
        "Turn on \"Speak English aloud\" to have each translation read out. With headphones the microphone keeps " +
        "listening while the app talks. On the phone speaker, or with \"Pause mic while speaking\" switched on " +
        "(use this for a Bluetooth speaker), the microphone pauses so the app does not translate its own voice.\n\n" +
        "The voice comes from Android's text-to-speech. If nothing is spoken, install or update \"Speech Recognition & Synthesis\" " +
        "from Google and download the English voice in Android's settings.",
    "Getting accurate results" to
        "•  Choose the language yourself. Auto-detect is slower and less accurate.\n" +
        "•  Keep the phone within a metre or two of the speaker and away from loud background noise.\n" +
        "•  One person at a time is recognised best.\n" +
        "•  Names and rare words are the most likely to be wrong.\n\n" +
        "On test recordings the speech packs get roughly 1 word in 9 wrong for Hindi, 1 in 7 for Bengali and " +
        "1 in 5 for Kannada. The translation can only be as good as what was heard, so treat it as a strong aid, " +
        "not as a certified interpreter.",
    "Learning" to
        "Pick a language in Learn. Start with the script section if you cannot read it yet, then work through the units in order. " +
        "Each unit explains its grammar, lists phrases with a word-for-word breakdown, and lets you listen, " +
        "take a quiz, and speak the phrases to have them checked.\n\n" +
        "Listening uses Android's text-to-speech voices for each language. Speaking practice needs that language's speech pack.",
    "Privacy" to
        "Audio is processed in memory on this phone and is never saved or uploaded. The app uses the internet for " +
        "two things only: downloading models from Hugging Face and GitHub when you ask for them, and asking GitHub once a day " +
        "whether a newer version of the app exists. You can switch the update check off below.",
    "Updates" to
        "Bhasha is installed from a file rather than from an app store, so it updates itself: when a newer version " +
        "is published it offers to download it, checks the file and opens Android's installer. Android will ask you " +
        "to confirm, and the first time it will ask you to allow Bhasha to install apps. Your models and progress are kept.",
)

@Composable
fun HelpScreen(onBack: () -> Unit, onTour: () -> Unit) {
    val app = LocalContext.current.applicationContext as BhashaApp
    val autoUpdate by app.settings.autoUpdateCheck.collectAsStateWithLifecycle()
    val batterySaver by app.settings.batterySaver.collectAsStateWithLifecycle()
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("Help", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = onTour) { Text("Take the tour") }
        }
        helpSections.forEach { (title, text) ->
            Card(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(6.dp))
                    Text(text, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Battery saver", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Show each sentence when it is finished instead of word by word. Uses much less power and keeps the phone cooler.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = batterySaver, onCheckedChange = { app.settings.setBatterySaver(it) })
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Check for updates automatically", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Once a day, when the app is opened.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = autoUpdate, onCheckedChange = { app.settings.setAutoUpdateCheck(it) })
        }
    }
}

@Composable
fun ThemeDialog(onClose: () -> Unit) {
    val app = LocalContext.current.applicationContext as BhashaApp
    val current by app.settings.theme.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Theme") },
        text = {
            Column {
                ThemeMode.entries.forEach { mode ->
                    Row(
                        Modifier.fillMaxWidth().clickable { app.settings.setTheme(mode) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = current == mode, onClick = { app.settings.setTheme(mode) })
                        Text(mode.label)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Done") } },
    )
}

@Composable
fun AboutDialog(onClose: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as BhashaApp
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Bhasha ${app.updater.installedVersion}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("On-device live translation and language tutor for Hindi, Bengali and Kannada.")
                Text(
                    "Free software under the GNU Affero General Public License, version 3 or later. " +
                        "It comes with no warranty. The source code is on GitHub.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Built with llama.cpp and whisper.cpp (MIT), sherpa-onnx (Apache-2.0) and ONNX Runtime (MIT). " +
                        "Speech models: AI4Bharat IndicConformer, Silero VAD, OpenAI Whisper. Translation: AI4Bharat IndicTrans2 with SentencePiece (Apache-2.0). Tutor: Google Gemma 4.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
        dismissButton = {
            TextButton(onClick = {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(Updater.REPOSITORY)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }) { Text("Source code") }
        },
    )
}

/** Shows whatever the updater is doing; nothing while it is idle. */
@Composable
fun UpdateDialog() {
    val app = LocalContext.current.applicationContext as BhashaApp
    val state by app.updater.state.collectAsStateWithLifecycle()
    val updater = app.updater
    when (val s = state) {
        UpdateState.Idle -> Unit
        UpdateState.Checking -> AlertDialog(
            onDismissRequest = updater::dismiss,
            title = { Text("Checking for updates…") },
            text = { LinearProgressIndicator(Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = updater::dismiss) { Text("Cancel") } },
        )
        is UpdateState.UpToDate -> AlertDialog(
            onDismissRequest = updater::dismiss,
            title = { Text("You have the latest version") },
            text = { Text("Bhasha ${s.version} is up to date.") },
            confirmButton = { TextButton(onClick = updater::dismiss) { Text("OK") } },
        )
        is UpdateState.Available -> AlertDialog(
            onDismissRequest = updater::dismiss,
            title = { Text("Bhasha ${s.release.version} is available") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "You have ${updater.installedVersion}. The download is ${formatBytes(s.release.apkBytes)}; " +
                            "your models and progress are kept.",
                    )
                    if (s.release.notes.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Text("What is new", style = MaterialTheme.typography.titleSmall)
                        Text(plainNotes(s.release.notes), style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = { Button(onClick = { updater.download(s.release) }) { Text("Download") } },
            dismissButton = { TextButton(onClick = updater::dismiss) { Text("Later") } },
        )
        is UpdateState.Downloading -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Downloading Bhasha ${s.release.version}") },
            text = {
                Column {
                    val fraction = if (s.total > 0) (s.downloaded.toFloat() / s.total).coerceIn(0f, 1f) else 0f
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "${formatBytes(s.downloaded)} of ${formatBytes(s.total)}",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            },
            confirmButton = { TextButton(onClick = updater::dismiss) { Text("Cancel") } },
        )
        is UpdateState.Ready -> AlertDialog(
            onDismissRequest = updater::dismiss,
            title = { Text("Ready to install") },
            text = {
                Text(
                    "Bhasha ${s.release.version} was downloaded and verified. Android will now ask you to confirm the update. " +
                        "If it asks, allow Bhasha to install apps, then come back and tap Install again.",
                )
            },
            confirmButton = { Button(onClick = { runCatching { updater.install(s.apk) } }) { Text("Install") } },
            dismissButton = { TextButton(onClick = updater::dismiss) { Text("Later") } },
        )
        is UpdateState.Failed -> AlertDialog(
            onDismissRequest = updater::dismiss,
            title = { Text("Update failed") },
            text = { Text(s.message) },
            confirmButton = {
                TextButton(onClick = { s.release?.let(updater::download) ?: updater.check() }) { Text("Try again") }
            },
            dismissButton = { TextButton(onClick = updater::dismiss) { Text("Close") } },
        )
    }
}

/** Release notes are Markdown; show them as plain text. */
private fun plainNotes(notes: String): String =
    notes.lineSequence()
        .map { it.replace("**", "").replace("`", "").replace(Regex("^\\s*[-*]\\s+"), "•  ") }
        .joinToString("\n")
        .trim()
