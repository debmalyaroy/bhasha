// SPDX-License-Identifier: AGPL-3.0-or-later
package com.bhasha.translator.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bhasha.translator.BhashaApp
import com.bhasha.translator.core.TranslateMode
import com.bhasha.translator.core.formatBytes
import com.bhasha.translator.engine.EngineState
import com.bhasha.translator.model.ModelCatalog
import com.bhasha.translator.model.ModelKind
import com.bhasha.translator.model.ModelSpec
import com.bhasha.translator.model.ModelState

@Composable
fun ModelsScreen() {
    val app = LocalContext.current.applicationContext as BhashaApp
    val states by app.models.states.collectAsStateWithLifecycle()
    val asrChoice by app.settings.asrModelId.collectAsStateWithLifecycle()
    val llmChoice by app.settings.llmModelId.collectAsStateWithLifecycle()
    val mode by app.settings.mode.collectAsStateWithLifecycle()
    val asrEngine by app.engines.asrState.collectAsStateWithLifecycle()
    val llmEngine by app.engines.llmState.collectAsStateWithLifecycle()
    val running by app.session.running.collectAsStateWithLifecycle()

    // What will actually be used: the choice if installed, otherwise the first installed model.
    fun effective(choice: String, kind: ModelKind): String? {
        val installed = ModelCatalog.all.filter { it.kind == kind && states[it.id] == ModelState.Installed }
        return (installed.firstOrNull { it.id == choice } ?: installed.firstOrNull())?.id
    }
    val activeAsr = effective(asrChoice, ModelKind.ASR)
    val activeLlm = effective(llmChoice, ModelKind.LLM)
    val directPossible = ModelCatalog.byId(activeAsr ?: "")?.canTranslate == true

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            Text("Models", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Everything runs on this phone. Models are downloaded once from Hugging Face " +
                    "(Wi-Fi recommended) and stored in the app's folder." +
                    if (running) "\nChanges take effect the next time you start listening." else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item {
            SectionHeader("Speech recognition: language packs", engineLine(asrEngine))
            Text(
                "Download the pack for each language you want to translate or practise. " +
                    "A pack is used automatically whenever its language is chosen.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(ModelCatalog.packs, key = { it.id }) { spec ->
            ModelCard(
                spec, states[spec.id] ?: ModelState.Missing, selected = null, onSelect = {},
                onDownload = {
                    app.models.download(ModelCatalog.vad)   // tiny, and every speech model needs it
                    app.models.download(spec)
                },
                onDelete = { app.models.delete(spec) },
            )
        }

        item {
            SectionHeader("Speech recognition: Whisper (optional)", null)
            Text(
                "Used for a language without a pack, for Auto-detect and for direct mode.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(ModelCatalog.asr, key = { it.id }) { spec ->
            ModelCard(
                spec, states[spec.id] ?: ModelState.Missing, selected = activeAsr == spec.id,
                onSelect = { app.settings.setAsrModel(spec.id) },
                onDownload = {
                    app.models.download(ModelCatalog.vad)   // tiny, and every speech model needs it
                    app.models.download(spec)
                },
                onDelete = { app.models.delete(spec) },
            )
        }

        item { SectionHeader("Translation and tutor", engineLine(llmEngine)) }
        items(ModelCatalog.llm, key = { it.id }) { spec ->
            ModelCard(
                spec, states[spec.id] ?: ModelState.Missing, selected = activeLlm == spec.id,
                onSelect = { app.settings.setLlmModel(spec.id) },
                onDownload = { app.models.download(spec) },
                onDelete = { app.models.delete(spec) },
            )
        }

        item {
            SectionHeader("How to translate", null)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = mode == TranslateMode.LLM,
                    onClick = { app.settings.setMode(TranslateMode.LLM) },
                    label = { Text("Transcribe, then LLM") },
                )
                FilterChip(
                    selected = mode == TranslateMode.DIRECT,
                    onClick = { app.settings.setMode(TranslateMode.DIRECT) },
                    label = { Text("Direct (fastest)") },
                )
            }
            Text(
                when {
                    mode == TranslateMode.LLM ->
                        "Best quality. Shows the original sentence and its English translation."
                    directPossible ->
                        "The speech model outputs English itself: one model, lowest delay, rougher wording."
                    else ->
                        "Direct mode needs Whisper medium, small or large-v3 (turbo and the language packs cannot translate). " +
                            "Until one is selected, the LLM is used instead."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

private fun engineLine(state: EngineState): String? = when (state) {
    is EngineState.Loading -> "Loading ${state.model}…"
    is EngineState.Ready -> "${state.model.title} is loaded"
    is EngineState.Error -> state.message
    EngineState.Idle -> null
}

@Composable
private fun SectionHeader(title: String, note: String?) {
    Column(Modifier.padding(top = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        if (note != null) {
            Text(note, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ModelCard(
    spec: ModelSpec,
    state: ModelState,
    selected: Boolean?,   // null: nothing to choose, the model is simply used when installed
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 6.dp, end = 12.dp, top = 10.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selected != null) {
                    RadioButton(selected = selected, onClick = onSelect, enabled = state == ModelState.Installed)
                } else {
                    Spacer(Modifier.width(12.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(spec.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        formatBytes(spec.bytes) + if (spec.recommended) " · recommended" else "",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                when (state) {
                    ModelState.Installed -> IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete ${spec.title}") }
                    is ModelState.Downloading -> OutlinedButton(onClick = onDelete) { Text("Cancel") }
                    else -> FilledTonalButton(onClick = onDownload) {
                        Icon(AppIcons.Download, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (state is ModelState.Failed) "Retry" else "Download")
                    }
                }
            }
            Text(
                spec.description,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 12.dp, top = 2.dp),
            )
            when (state) {
                is ModelState.Downloading -> {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { state.fraction },
                        modifier = Modifier.fillMaxWidth().padding(start = 12.dp),
                    )
                    Text(
                        "${formatBytes(state.downloaded)} of ${formatBytes(state.total)}",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(start = 12.dp, top = 2.dp),
                    )
                }
                is ModelState.Failed -> Text(
                    state.reason,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 12.dp, top = 4.dp),
                )
                else -> if (spec.canTranslate) {
                    AssistChip(
                        onClick = {},
                        label = { Text("Supports direct mode") },
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
            }
        }
    }
}
