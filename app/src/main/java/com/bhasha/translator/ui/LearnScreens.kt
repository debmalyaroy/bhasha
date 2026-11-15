// SPDX-License-Identifier: AGPL-3.0-or-later
package com.bhasha.translator.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bhasha.engine.llm.ChatMessage
import com.bhasha.translator.BhashaApp
import com.bhasha.translator.core.Lang
import com.bhasha.translator.learn.ClipRecorder
import com.bhasha.translator.learn.Course
import com.bhasha.translator.learn.GuideSection
import com.bhasha.translator.learn.LessonUnit
import com.bhasha.translator.learn.Letter
import com.bhasha.translator.learn.Phrase
import com.bhasha.translator.learn.Progress
import com.bhasha.translator.learn.speechMatchPercent
import com.bhasha.translator.learn.tutorSystemPrompt
import com.bhasha.translator.model.ModelKind
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface Route {
    data object Home : Route
    data class CourseHome(val lang: Lang) : Route
    data class Script(val lang: Lang) : Route
    data class Lesson(val lang: Lang, val unitId: String) : Route
    data class Quiz(val lang: Lang, val unitId: String) : Route
    data class Speak(val lang: Lang, val unitId: String) : Route
    data class Tutor(val lang: Lang) : Route
}

/** Where the learner is. Kept outside the composition so switching tabs does not lose the place. */
private val learnStack = mutableStateListOf<Route>(Route.Home)

@Composable
fun LearnTab(onOpenModels: () -> Unit) {
    val stack = learnStack
    fun push(route: Route) { stack.add(route) }
    fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    BackHandler(enabled = stack.size > 1) { pop() }

    when (val route = stack.last()) {
        Route.Home -> LearnHome(onPick = { push(Route.CourseHome(it)) })
        is Route.CourseHome -> WithCourse(route.lang, ::pop) { course ->
            CourseHome(route.lang, course, ::pop, ::push)
        }
        is Route.Script -> WithCourse(route.lang, ::pop) { course -> ScriptScreen(route.lang, course, ::pop) }
        is Route.Lesson -> WithUnit(route.lang, route.unitId, ::pop) { unit ->
            LessonScreen(route.lang, unit, ::pop, ::push)
        }
        is Route.Quiz -> WithUnit(route.lang, route.unitId, ::pop) { unit -> QuizScreen(route.lang, unit, ::pop) }
        is Route.Speak -> WithUnit(route.lang, route.unitId, ::pop) { unit ->
            SpeakScreen(route.lang, unit, ::pop, onOpenModels)
        }
        is Route.Tutor -> TutorScreen(route.lang, ::pop, onOpenModels)
    }
}

@Composable
private fun WithCourse(lang: Lang, onBack: () -> Unit, content: @Composable (Course) -> Unit) {
    val app = LocalContext.current.applicationContext as BhashaApp
    val loaded by produceState<Result<Course?>?>(null, lang) { value = Result.success(app.learn.course(lang)) }
    val course = loaded?.getOrNull()
    when {
        loaded == null -> Box(Modifier.fillMaxSize())
        course == null -> Column(Modifier.fillMaxSize().padding(16.dp)) {
            Header("${lang.english}", onBack)
            Text("Lessons for ${lang.english} could not be loaded.")
        }
        else -> content(course)
    }
}

@Composable
private fun WithUnit(lang: Lang, unitId: String, onBack: () -> Unit, content: @Composable (LessonUnit) -> Unit) {
    WithCourse(lang, onBack) { course ->
        val unit = course.units.firstOrNull { it.id == unitId }
        if (unit == null) LaunchedEffect(Unit) { onBack() } else content(unit)
    }
}

@Composable
private fun Header(title: String, onBack: (() -> Unit)?, subtitle: String? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        }
        Column(Modifier.padding(start = if (onBack == null) 0.dp else 4.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ------------------------------------------------------------------ home

@Composable
private fun LearnHome(onPick: (Lang) -> Unit) {
    val app = LocalContext.current.applicationContext as BhashaApp
    val sizes by produceState(emptyMap<Lang, String>()) {
        value = Lang.entries.mapNotNull { lang ->
            app.learn.course(lang)?.let { lang to "${it.units.size} units · ${it.units.sumOf { u -> u.phrases.size }} phrases" }
        }.toMap()
    }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Header("Learn a language", null, "Script, grammar, everyday phrases, speaking practice and an on-device tutor")
        Lang.entries.forEach { lang ->
            Card(
                onClick = { onPick(lang) },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(lang.english, style = MaterialTheme.typography.titleLarge)
                        Text(sizes[lang] ?: "", style = MaterialTheme.typography.bodySmall)
                    }
                    Text(lang.native, style = MaterialTheme.typography.headlineMedium)
                }
            }
        }
    }
}

@Composable
private fun CourseHome(lang: Lang, course: Course, onBack: () -> Unit, open: (Route) -> Unit) {
    val app = LocalContext.current.applicationContext as BhashaApp
    val scores by app.progress.scores.collectAsStateWithLifecycle()
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item { Header("${course.name} · ${course.nativeName}", onBack, "${course.script} script") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(onClick = { open(Route.Script(lang)) }, modifier = Modifier.weight(1f)) {
                    Text("The script")
                }
                FilledTonalButton(onClick = { open(Route.Tutor(lang)) }, modifier = Modifier.weight(1f)) {
                    Text("Ask the tutor")
                }
            }
        }
        course.units.forEachIndexed { index, unit ->
            if (unit.level.isNotEmpty() && unit.level != course.units.getOrNull(index - 1)?.level) {
                item(key = "level-${unit.level}") {
                    Text(
                        unit.level,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }
            item(key = unit.id) {
            val quiz = scores[Progress.key(Progress.QUIZ, lang, unit.id)]
            val speak = scores[Progress.key(Progress.SPEAK, lang, unit.id)]
            Card(onClick = { open(Route.Lesson(lang, unit.id)) }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("${index + 1}. ${unit.title}", style = MaterialTheme.typography.titleMedium)
                    if (unit.description.isNotEmpty()) {
                        Text(unit.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (quiz != null || speak != null) {
                        Text(
                            listOfNotNull(quiz?.let { "Quiz $it%" }, speak?.let { "Speaking $it%" }).joinToString(" · "),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
            }
        }
    }
}

/** An explanation with examples that can be heard. */
@Composable
private fun GuideCard(section: GuideSection, onSpeak: (String) -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(section.title, style = MaterialTheme.typography.titleMedium)
            Text(section.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
            section.examples.forEach { example ->
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(example.native, style = MaterialTheme.typography.titleMedium)
                        Text(example.roman, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic)
                        Text(example.english, style = MaterialTheme.typography.bodyMedium)
                        if (example.literal.isNotEmpty()) {
                            Text("Word for word: ${example.literal}", style = MaterialTheme.typography.bodySmall)
                        }
                        if (example.note.isNotEmpty()) Text(example.note, style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { onSpeak(example.native) }) { Icon(AppIcons.Volume, "Hear it") }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ script

@Composable
private fun ScriptScreen(lang: Lang, course: Course, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as BhashaApp
    var selected by remember { mutableStateOf<Letter?>(null) }
    var voiceMissing by remember { mutableStateOf(false) }
    var tracing by remember { mutableStateOf<Letter?>(null) }

    tracing?.let { letter ->
        BackHandler { tracing = null }
        TracePad(letter, onHear = { app.speaker.speak(letter.char, lang) }, onClose = { tracing = null })
        return
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Header("${course.script} script", onBack, "Tap a letter to hear it, then practise writing it")
        LazyVerticalGrid(
            columns = GridCells.Adaptive(68.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 12.dp),
            modifier = Modifier.weight(1f),
        ) {
            course.about.forEach { section ->
                item(span = { GridItemSpan(maxLineSpan) }) {
                    GuideCard(section) { voiceMissing = !app.speaker.speak(it, lang) }
                }
            }
            course.letters.forEach { group ->
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.padding(top = 8.dp)) {
                        Text(group.group, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        if (group.note.isNotEmpty()) {
                            Text(
                                group.note,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                items(group.items) { letter ->
                    val isSelected = selected == letter
                    Card(
                        onClick = {
                            selected = letter
                            voiceMissing = !app.speaker.speak(letter.char, lang)
                        },
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceContainerHigh,
                        ),
                        modifier = Modifier.aspectRatio(0.9f),
                    ) {
                        Column(
                            Modifier.fillMaxSize().padding(4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(letter.char, style = MaterialTheme.typography.headlineMedium)
                            Text(
                                letter.roman,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
        selected?.let { letter ->
            Card(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(letter.char, style = MaterialTheme.typography.displaySmall)
                    Column(Modifier.weight(1f).padding(start = 16.dp)) {
                        Text("“${letter.roman}”", style = MaterialTheme.typography.titleMedium)
                        if (letter.example.isNotEmpty()) {
                            Text("${letter.example} (${letter.exampleRoman}) — ${letter.exampleMeaning}")
                        }
                        if (letter.note.isNotEmpty()) {
                            Text(
                                letter.note,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (voiceMissing) VoiceHint(lang)
                        TextButton(onClick = { tracing = letter }) { Text("Practise writing") }
                    }
                    if (letter.example.isNotEmpty()) {
                        IconButton(onClick = { voiceMissing = !app.speaker.speak(letter.example, lang) }) {
                            Icon(AppIcons.Volume, "Hear the example word")
                        }
                    }
                }
            }
        }
    }
}

/** A pad for writing a letter with a finger over a faint copy of it. */
@Composable
private fun TracePad(letter: Letter, onHear: () -> Unit, onClose: () -> Unit) {
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    var current by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var showGuide by remember { mutableStateOf(true) }
    val ink = MaterialTheme.colorScheme.primary

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Header("Write ${letter.char}", onClose, "Trace over the letter, then try without the guide")
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            modifier = Modifier.fillMaxWidth().weight(1f).padding(vertical = 8.dp),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (showGuide) {
                    Text(
                        letter.char,
                        fontSize = 240.sp,
                        lineHeight = 300.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                    )
                }
                Canvas(
                    // Raw pointer tracking: a drag detector would drop the start of each stroke (touch slop).
                    Modifier.fillMaxSize().pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            current = listOf(down.position)
                            while (true) {
                                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                // Points the system batched between frames keep fast strokes smooth,
                                // and the lift-off point is part of the stroke too.
                                current = current + change.historical.map { it.position } + change.position
                                if (!change.pressed) break
                                change.consume()
                            }
                            strokes.add(current)
                            current = emptyList()
                        }
                    }
                ) {
                    (strokes + listOf(current)).forEach { points ->
                        if (points.size > 1) {
                            val path = Path().apply {
                                moveTo(points[0].x, points[0].y)
                                for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
                            }
                            drawPath(path, ink, style = Stroke(width = 22f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                        }
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = { strokes.clear(); current = emptyList() }) { Text("Clear") }
            FilterChip(selected = showGuide, onClick = { showGuide = !showGuide }, label = { Text("Guide") })
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onHear) { Icon(AppIcons.Volume, "Hear the letter") }
        }
    }
}

@Composable
private fun VoiceHint(lang: Lang) {
    Text(
        "No ${lang.english} voice on this phone. Install one in Settings → Text-to-speech output.",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.error,
    )
}

// ------------------------------------------------------------------ lesson

@Composable
private fun LessonScreen(lang: Lang, unit: LessonUnit, onBack: () -> Unit, open: (Route) -> Unit) {
    val app = LocalContext.current.applicationContext as BhashaApp
    var voiceMissing by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { app.speaker.stop() } }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item { Header(unit.title, onBack, unit.description.ifEmpty { null }) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { open(Route.Quiz(lang, unit.id)) }, modifier = Modifier.weight(1f)) { Text("Quiz") }
                Button(onClick = { open(Route.Speak(lang, unit.id)) }, modifier = Modifier.weight(1f)) {
                    Icon(AppIcons.Mic, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Speak")
                }
            }
            if (voiceMissing) VoiceHint(lang)
        }
        items(unit.guide) { section ->
            GuideCard(section) { voiceMissing = !app.speaker.speak(it, lang) }
        }
        if (unit.guide.isNotEmpty()) {
            item {
                Text(
                    "Phrases to practise",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        items(unit.phrases) { phrase ->
            PhraseCard(phrase) { slow -> voiceMissing = !app.speaker.speak(phrase.native, lang, slow) }
        }
    }
}

@Composable
private fun PhraseCard(phrase: Phrase, onSpeak: (slow: Boolean) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(phrase.native, style = MaterialTheme.typography.titleLarge)
                Text(phrase.roman, style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic)
                Text(phrase.english, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 4.dp))
                if (phrase.literal.isNotEmpty()) {
                    Text(
                        "Word for word: ${phrase.literal}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                if (phrase.note.isNotEmpty()) {
                    Text(
                        phrase.note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                IconButton(onClick = { onSpeak(false) }) { Icon(AppIcons.Volume, "Hear it") }
                TextButton(onClick = { onSpeak(true) }) { Text("Slow") }
            }
        }
    }
}

// ------------------------------------------------------------------ quiz

private data class Question(val prompt: String, val promptIsNative: Boolean, val options: List<String>, val answer: Int)

private fun buildQuiz(unit: LessonUnit): List<Question> =
    unit.phrases.shuffled().mapIndexed { i, phrase ->
        val nativeToEnglish = i % 2 == 0
        val text: (Phrase) -> String = { if (nativeToEnglish) it.english else it.native }
        val wrong = unit.phrases.filter { text(it) != text(phrase) }.shuffled().take(3)
        val options = (wrong + phrase).shuffled().map(text)
        Question(
            prompt = if (nativeToEnglish) phrase.native else phrase.english,
            promptIsNative = nativeToEnglish,
            options = options,
            answer = options.indexOf(text(phrase)),
        )
    }

@Composable
private fun QuizScreen(lang: Lang, unit: LessonUnit, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as BhashaApp
    var round by remember { mutableIntStateOf(0) }
    val questions = remember(unit.id, round) { buildQuiz(unit) }
    var index by remember(round) { mutableIntStateOf(0) }
    var chosen by remember(round) { mutableStateOf<Int?>(null) }
    var correct by remember(round) { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Header("Quiz · ${unit.title}", onBack)
        if (index >= questions.size) {
            val percent = if (questions.isEmpty()) 0 else correct * 100 / questions.size
            LaunchedEffect(round) { app.progress.record(Progress.QUIZ, lang, unit.id, percent) }
            Spacer(Modifier.height(32.dp))
            Text("$correct of ${questions.size} correct", style = MaterialTheme.typography.headlineMedium)
            Text(
                when {
                    percent == 100 -> "Perfect!"
                    percent >= 70 -> "Nicely done."
                    else -> "Go through the phrases once more and try again."
                },
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { round++ }) { Text("Try again") }
                OutlinedButton(onClick = onBack) { Text("Back to lesson") }
            }
            return@Column
        }

        val q = questions[index]
        LinearProgressIndicator(progress = { index.toFloat() / questions.size }, modifier = Modifier.fillMaxWidth())
        Text(
            if (q.promptIsNative) "What does this mean?" else "How do you say this in ${lang.english}?",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 20.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 12.dp)) {
            Text(q.prompt, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            if (q.promptIsNative) {
                IconButton(onClick = { app.speaker.speak(q.prompt, lang) }) { Icon(AppIcons.Volume, "Hear it") }
            }
        }
        q.options.forEachIndexed { i, option ->
            val picked = chosen
            val container = when {
                picked == null -> MaterialTheme.colorScheme.surfaceContainerHigh
                i == q.answer -> Color(0xFF2E7D32).copy(alpha = 0.25f)
                i == picked -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.surfaceContainerHigh
            }
            Card(
                colors = CardDefaults.cardColors(containerColor = container),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable(enabled = picked == null) {
                    chosen = i
                    if (i == q.answer) correct++
                },
            ) {
                Text(option, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
            }
        }
        if (chosen != null) {
            Button(
                onClick = { chosen = null; index++ },
                modifier = Modifier.padding(top = 16.dp).align(Alignment.End),
            ) { Text(if (index == questions.lastIndex) "Finish" else "Next") }
        }
    }
}

// ------------------------------------------------------------------ speaking practice

@Composable
private fun SpeakScreen(lang: Lang, unit: LessonUnit, onBack: () -> Unit, onOpenModels: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as BhashaApp
    val scope = rememberCoroutineScope()
    val listening by app.session.running.collectAsStateWithLifecycle()
    val modelStates by app.models.states.collectAsStateWithLifecycle()
    val hasSpeechModel = remember(modelStates) { app.models.hasSpeechModel() }

    val recorder = remember { ClipRecorder() }
    var index by remember { mutableIntStateOf(0) }
    var recording by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var heard by remember { mutableStateOf<String?>(null) }
    var score by remember { mutableStateOf<Int?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val best = remember { mutableStateListOf<Int>().apply { repeat(unit.phrases.size) { add(0) } } }
    DisposableEffect(Unit) { onDispose { recorder.stop(); app.speaker.stop() } }

    fun record() {
        scope.launch {
            app.speaker.stop()
            heard = null; score = null; message = null
            recording = true
            val pcm = recorder.record(maxSeconds = 8)
            recording = false
            if (pcm == null) {
                message = "Nothing was recorded. Try again."
                return@launch
            }
            busy = true
            val asr = app.engines.ensureAsr()
            val result = asr?.let { withContext(app.engines.asrDispatcher) { it.transcribe(pcm, pcm.size, lang.code) } }
            busy = false
            if (result == null) {
                message = "The speech model could not process that."
                return@launch
            }
            val percent = speechMatchPercent(unit.phrases[index], result.text)
            heard = result.text.ifBlank { "(nothing recognised)" }
            score = percent
            if (percent > best[index]) best[index] = percent
            app.progress.record(Progress.SPEAK, lang, unit.id, best.sum() / best.size)
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) record()
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Header("Speak · ${unit.title}", onBack, "Phrase ${index + 1} of ${unit.phrases.size}")
        when {
            !hasSpeechModel -> {
                Text("Speaking practice uses the on-device speech model to check what you said.", Modifier.padding(vertical = 16.dp))
                Button(onClick = onOpenModels) { Text("Download a speech model") }
                return@Column
            }
            listening -> {
                Text("Stop live translation first - it is using the microphone.", Modifier.padding(vertical = 16.dp))
                return@Column
            }
        }

        val phrase = unit.phrases[index]
        Card(Modifier.fillMaxWidth().padding(top = 12.dp)) {
            Column(Modifier.padding(20.dp)) {
                Text(phrase.english, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(phrase.native, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 8.dp))
                Text(phrase.roman, style = MaterialTheme.typography.titleMedium, fontStyle = FontStyle.Italic)
                TextButton(onClick = { app.speaker.speak(phrase.native, lang, slow = true) }) {
                    Icon(AppIcons.Volume, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Listen first")
                }
            }
        }

        Box(Modifier.fillMaxWidth().padding(vertical = 20.dp), contentAlignment = Alignment.Center) {
            when {
                busy -> Text("Checking…", style = MaterialTheme.typography.titleMedium)
                recording -> Button(onClick = { recorder.stop() }) {
                    Icon(AppIcons.Stop, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Done speaking")
                }
                else -> Button(onClick = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) record()
                    else permission.launch(Manifest.permission.RECORD_AUDIO)
                }) {
                    Icon(AppIcons.Mic, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (score == null) "Say it" else "Try again")
                }
            }
        }

        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        score?.let { percent ->
            Text("$percent% match", style = MaterialTheme.typography.headlineSmall)
            Text(
                when {
                    percent >= 85 -> "Excellent - that was understood clearly."
                    percent >= 60 -> "Close. Listen again and copy the rhythm."
                    else -> "Not quite. Try saying it more slowly."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Text("Heard: ${heard.orEmpty()}", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
        }

        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            OutlinedButton(enabled = index > 0 && !recording && !busy, onClick = {
                index--; heard = null; score = null; message = null
            }) { Text("Previous") }
            if (index < unit.phrases.lastIndex) {
                Button(enabled = !recording && !busy, onClick = {
                    index++; heard = null; score = null; message = null
                }) { Text("Next phrase") }
            } else {
                Button(enabled = !recording && !busy, onClick = onBack) { Text("Finish") }
            }
        }
    }
}

// ------------------------------------------------------------------ tutor

@Composable
private fun TutorScreen(lang: Lang, onBack: () -> Unit, onOpenModels: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as BhashaApp
    val scope = rememberCoroutineScope()
    val modelStates by app.models.states.collectAsStateWithLifecycle()
    val hasLlm = remember(modelStates) { app.models.installed(ModelKind.LLM).isNotEmpty() }
    val hasSpeechModel = remember(modelStates) { app.models.hasSpeechModel() }
    val listening by app.session.running.collectAsStateWithLifecycle()
    val recorder = remember { ClipRecorder() }
    var recording by remember { mutableStateOf(false) }
    var transcribing by remember { mutableStateOf(false) }
    var speakReplies by remember { mutableStateOf(true) }

    val messages = remember { mutableStateListOf<ChatMessage>() }
    var draft by remember { mutableStateOf("") }
    var streaming by remember { mutableStateOf<String?>(null) }   // reply being generated
    var note by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    DisposableEffect(Unit) {
        onDispose {
            recorder.stop()
            app.speaker.stop()
            if (streaming != null) app.engines.cancelLlm()
        }
    }

    fun send(text: String) {
        val question = text.trim()
        if (question.isEmpty() || streaming != null) return
        draft = ""
        note = null
        messages.add(ChatMessage.user(question))
        streaming = ""
        scope.launch {
            val llm = app.engines.ensureLlm()
            if (llm == null) {
                note = "The language model could not be loaded."
                streaming = null
                return@launch
            }
            // Keep the prompt well inside the context window.
            val recent = messages.takeLast(MAX_TURNS)
            val prompt = listOf(ChatMessage.system(tutorSystemPrompt(lang))) + recent
            val partial = StringBuilder()
            val reply = withContext(app.engines.llmDispatcher) {
                llm.chat(prompt, maxTokens = 400, temperature = 0.6f) { piece ->
                    partial.append(piece)
                    streaming = partial.toString()
                    true
                }
            }
            streaming = null
            if (reply.isNullOrBlank()) {
                note = "No answer was produced. Try a shorter question."
            } else {
                messages.add(ChatMessage.assistant(reply))
                // Markdown markers would be read out literally.
                if (speakReplies) app.speaker.speak(reply.replace(Regex("[*_#`]"), ""), lang)
            }
        }
    }

    // Oral practice: say something in the language (or in English); the speech model writes it down.
    fun talk() {
        scope.launch {
            app.speaker.stop()
            note = null
            recording = true
            val pcm = recorder.record(maxSeconds = 12)
            recording = false
            if (pcm == null) {
                note = "Nothing was recorded. Try again."
                return@launch
            }
            transcribing = true
            val asr = app.engines.ensureAsr()
            val heard = asr?.let {
                withContext(app.engines.asrDispatcher) { it.transcribe(pcm, pcm.size, "${lang.code},en") }
            }?.text?.trim()
            transcribing = false
            if (heard.isNullOrEmpty()) note = "Could not make out what you said." else send(heard)
        }
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) talk()
    }

    LaunchedEffect(messages.size, streaming?.length) {
        val count = messages.size + if (streaming != null) 1 else 0
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).imePadding()) {
        Header("${lang.english} tutor", onBack, "Type or talk. Runs on this phone - nothing leaves it")
        if (!hasLlm) {
            Text("The tutor needs a language model.", Modifier.padding(vertical = 16.dp))
            Button(onClick = onOpenModels) { Text("Download a language model") }
            return@Column
        }

        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            if (messages.isEmpty()) {
                item {
                    Text(
                        "Ask anything about ${lang.english}: words, grammar, pronunciation, or just practise chatting.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                    )
                }
            }
            items(messages) { Bubble(it.content, fromUser = it.role == "user") }
            streaming?.let { text -> item { Bubble(text.ifEmpty { "Thinking…" }, fromUser = false) } }
        }

        note?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium) }
        when {
            recording -> Text("Listening… tap stop when you have finished.", style = MaterialTheme.typography.labelMedium)
            transcribing -> Text("Working out what you said…", style = MaterialTheme.typography.labelMedium)
        }
        FilterChip(
            selected = speakReplies,
            onClick = { speakReplies = !speakReplies; if (!speakReplies) app.speaker.stop() },
            label = { Text("Read answers aloud") },
            leadingIcon = { Icon(AppIcons.Volume, null, Modifier.size(18.dp)) },
        )
        if (messages.isEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    "Teach me five everyday greetings",
                    "How do I ask a question?",
                    "Quiz me on numbers",
                    "Let's have a simple conversation",
                ).forEach { suggestion -> AssistChip(onClick = { send(suggestion) }, label = { Text(suggestion) }) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = { Text("Ask the tutor") },
                maxLines = 4,
                modifier = Modifier.weight(1f),
            )
            if (streaming != null) {
                IconButton(onClick = { app.engines.cancelLlm() }) { Icon(AppIcons.Stop, "Stop") }
            } else if (recording) {
                IconButton(onClick = { recorder.stop() }) { Icon(AppIcons.Stop, "Done speaking") }
            } else if (draft.isBlank() && hasSpeechModel && !listening && !transcribing) {
                IconButton(onClick = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) talk()
                    else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                }) { Icon(AppIcons.Mic, "Speak to the tutor") }
            } else {
                IconButton(onClick = { send(draft) }, enabled = draft.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Send")
                }
            }
        }
    }
}

@Composable
private fun Bubble(text: String, fromUser: Boolean) {
    Box(Modifier.fillMaxWidth(), contentAlignment = if (fromUser) Alignment.CenterEnd else Alignment.CenterStart) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (fromUser) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
            modifier = Modifier.fillMaxWidth(0.88f),
        ) {
            // The model writes Markdown emphasis; show plain text rather than stray asterisks.
            Text(text.replace("**", "").replace("__", ""), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(12.dp))
        }
    }
}

private const val MAX_TURNS = 7   // odd, so the window always starts with a user turn
