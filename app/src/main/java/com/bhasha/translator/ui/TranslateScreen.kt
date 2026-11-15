// SPDX-License-Identifier: AGPL-3.0-or-later
package com.bhasha.translator.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bhasha.translator.BhashaApp
import com.bhasha.translator.core.Lang
import com.bhasha.translator.core.Settings
import com.bhasha.translator.model.ModelKind
import com.bhasha.translator.model.ModelState
import com.bhasha.translator.translate.Caption
import com.bhasha.translator.translate.TranslateService

@Composable
fun TranslateScreen(onOpenModels: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as BhashaApp
    val running by app.session.running.collectAsStateWithLifecycle()
    val captions by app.session.captions.collectAsStateWithLifecycle()
    val status by app.session.status.collectAsStateWithLifecycle()
    val level by app.session.level.collectAsStateWithLifecycle()
    val source by app.settings.sourceLang.collectAsStateWithLifecycle()
    val modelStates by app.models.states.collectAsStateWithLifecycle()
    val speakAloud by app.settings.speakTranslations.collectAsStateWithLifecycle()
    val pauseMic by app.settings.pauseMicWhileSpeaking.collectAsStateWithLifecycle()
    val transcribe by app.settings.transcribeOnly.collectAsStateWithLifecycle()

    val hasSpeechModel = remember(modelStates) { app.models.hasSpeechModel() }
    val hasLlm = remember(modelStates) { app.models.canTranslate() }
    val hasWhisper = remember(modelStates) { app.models.installed(ModelKind.ASR).isNotEmpty() }
    val packLanguages = remember(modelStates) { app.models.packLanguages() }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true) TranslateService.start(context)
    }

    fun toggle() {
        if (running) {
            TranslateService.stop(context)
            return
        }
        val needed = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) TranslateService.start(context) else permissions.launch(needed.toTypedArray())
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (transcribe) "Live transcription" else "Live translation",
                style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f),
            )
            if (captions.isNotEmpty()) {
                TextButton(onClick = { copyCaptions(context, captions) }) { Text("Copy") }
                TextButton(onClick = { app.session.clear() }) { Text("Clear") }
            }
        }

        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            SegmentedButton(
                selected = !transcribe, enabled = !running,
                onClick = { app.settings.setTranscribeOnly(false) },
                shape = SegmentedButtonDefaults.itemShape(0, 2),
            ) { Text("Translate to English") }
            SegmentedButton(
                selected = transcribe, enabled = !running,
                onClick = { app.settings.setTranscribeOnly(true) },
                shape = SegmentedButtonDefaults.itemShape(1, 2),
            ) { Text("Transcribe only") }
        }

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Lang.entries.forEach { lang ->
                FilterChip(
                    selected = source == lang.code,
                    onClick = { app.settings.setSourceLang(lang.code) },
                    label = { Text("${lang.english} · ${lang.native}") },
                )
            }
            FilterChip(
                selected = source == Settings.AUTO,
                onClick = { app.settings.setSourceLang(Settings.AUTO) },
                label = { Text("Auto-detect") },
            )
        }
        if (!transcribe) Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = speakAloud,
                onClick = { app.settings.setSpeakTranslations(!speakAloud) },
                label = { Text("Speak English aloud") },
                leadingIcon = { Icon(AppIcons.Volume, null, Modifier.size(18.dp)) },
            )
            if (speakAloud) {
                FilterChip(
                    selected = pauseMic,
                    onClick = { app.settings.setPauseMicWhileSpeaking(!pauseMic) },
                    label = { Text("Pause mic while speaking") },
                )
            }
        }
        if (speakAloud && !transcribe) {
            Text(
                if (pauseMic) "The mic pauses whenever a translation is spoken. Use this with a Bluetooth speaker."
                else "Plays through connected headphones or speaker. On the phone speaker the mic pauses while it talks.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val missing = when {
            !hasSpeechModel -> null   // the setup card below covers it
            source == Settings.AUTO && !hasWhisper ->
                "Auto-detect needs a Whisper model to tell the languages apart. Download one in Models, or pick the language."
            source != Settings.AUTO && source !in packLanguages && !hasWhisper ->
                "No speech model for ${Lang.fromCode(source)?.english ?: source} yet. Download its language pack in Models."
            !transcribe && !hasLlm ->
                "No translation model yet, so only the original words will be shown. Download the IndicTrans2 translator in Models to get English."
            else -> null
        }
        val langName = Lang.fromCode(source)?.english ?: ""
        Text(
            missing ?: when {
                source == Settings.AUTO -> "Auto-detect listens for all three languages. It is slower and has no live preview."
                transcribe -> "Writing down $langName as it is spoken, entirely on this phone."
                else -> "Translating $langName → English as it is spoken, entirely on this phone."
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (missing != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Box(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp)) {
            when {
                !hasSpeechModel -> SetupCard(
                    title = "One-time setup",
                    body = "Bhasha does its listening and translating on this phone, so nothing you say leaves it " +
                        "and it works offline. The AI models that do the work are too large to ship inside the app, " +
                        "so they are downloaded once.\n\nYou need the speech pack for your language (198 MB)" +
                        (if (hasLlm) "." else " and, to translate into English, the IndicTrans2 translator (235 MB)."),
                    downloading = modelStates.values.any { it is ModelState.Downloading },
                    onOpenModels = onOpenModels,
                )
                captions.isEmpty() -> Text(
                    if (running && !status.startsWith("Listening")) "Getting ready… the models take up to a minute to load."
                    else if (running) "Listening… the words appear here as people speak."
                    else "Tap Start and put the phone near the conversation.\nIt keeps listening with the screen off.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
                else -> CaptionList(captions)
            }
        }

        if (running) {
            val shown by animateFloatAsState(level, label = "level")
            LinearProgressIndicator(progress = { shown }, modifier = Modifier.fillMaxWidth().height(4.dp))
        }
        if (status.isNotEmpty()) {
            Text(
                status,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
            ExtendedFloatingActionButton(
                onClick = { if (hasSpeechModel || running) toggle() else onOpenModels() },
                icon = { Icon(if (running) AppIcons.Stop else AppIcons.Mic, null) },
                text = { Text(if (running) "Stop" else "Start listening") },
                containerColor = if (running) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
            )
        }
    }
}

@Composable
private fun CaptionList(captions: List<Caption>) {
    val listState = rememberLazyListState()
    val last = captions.lastOrNull()
    // Follow the newest caption, including while its words and translation stream in.
    LaunchedEffect(captions.size, last?.english?.length, last?.original?.length) {
        if (captions.isNotEmpty()) listState.animateScrollToItem(captions.size - 1)
    }
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(captions, key = { it.id }) { CaptionCard(it) }
    }
}

@Composable
private fun CaptionCard(c: Caption) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            // A sentence still being spoken is a preview: shown lighter until it is final.
            val settled = MaterialTheme.colorScheme.onSurface
            val preview = MaterialTheme.colorScheme.outline
            if (c.english.isNotEmpty() || c.pending) {
                Text(
                    c.english.ifEmpty { "…" },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Medium,
                    color = if (c.live || c.pending) preview else settled,
                )
            }
            if (c.original.isNotEmpty()) {
                val alone = c.english.isEmpty() && !c.pending
                Text(
                    c.original,
                    style = if (alone) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyMedium,
                    color = when {
                        c.live -> preview
                        alone -> settled
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            val timing = buildString {
                append(Lang.fromCode(c.lang)?.english ?: c.lang)
                if (c.live) append(" · still speaking…") else append(" · heard in ${c.asrMillis} ms")
                if (c.translateMillis > 0) append(" · translated in ${c.translateMillis} ms")
            }
            Text(
                timing,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** Puts everything on screen on the clipboard: the original words, and the English when there is any. */
private fun copyCaptions(context: Context, captions: List<Caption>) {
    val text = captions.joinToString("\n\n") { c ->
        listOf(c.original, c.english).filter { it.isNotBlank() }.joinToString("\n")
    }
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText("Bhasha", text))
}

@Composable
private fun SetupCard(title: String, body: String, downloading: Boolean, onOpenModels: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Column(Modifier.padding(20.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onOpenModels) {
                Icon(AppIcons.Download, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (downloading) "View download progress" else "Choose models")
            }
        }
    }
}
