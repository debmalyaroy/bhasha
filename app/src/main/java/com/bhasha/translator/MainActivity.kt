// SPDX-License-Identifier: AGPL-3.0-or-later
package com.bhasha.translator

import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bhasha.translator.core.ThemeMode
import com.bhasha.translator.ui.AboutDialog
import com.bhasha.translator.ui.AppIcons
import com.bhasha.translator.ui.BhashaTheme
import com.bhasha.translator.ui.HelpScreen
import com.bhasha.translator.ui.LearnTab
import com.bhasha.translator.ui.ModelsScreen
import com.bhasha.translator.ui.ThemeDialog
import com.bhasha.translator.ui.TourDialog
import com.bhasha.translator.ui.TranslateScreen
import com.bhasha.translator.ui.UpdateDialog
import com.bhasha.translator.update.UpdateState

class MainActivity : ComponentActivity() {
    /** In the background and not listening: let go of the models after a few minutes. */
    override fun onStop() {
        super.onStop()
        val app = application as BhashaApp
        if (!app.session.running.value) app.engines.releaseWhenIdle()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as BhashaApp
        setContent {
            val themeMode by app.settings.theme.collectAsStateWithLifecycle()
            val dark = when (themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            // Status and navigation bar icons must contrast with the chosen theme, not the phone's.
            DisposableEffect(dark) {
                val bars = if (dark) SystemBarStyle.dark(Color.TRANSPARENT)
                else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
                onDispose {}
            }
            BhashaTheme(themeMode) {
                var tab by rememberSaveable { mutableIntStateOf(0) }
                var help by rememberSaveable { mutableStateOf(false) }
                var about by rememberSaveable { mutableStateOf(false) }
                var menu by rememberSaveable { mutableStateOf(false) }
                var themePicker by rememberSaveable { mutableStateOf(false) }
                val tourSeen by app.settings.tourSeen.collectAsStateWithLifecycle()
                var tour by rememberSaveable { mutableStateOf(!tourSeen) }
                val update by app.updater.state.collectAsStateWithLifecycle()
                val updateWaiting = update is UpdateState.Available

                LaunchedEffect(Unit) { app.updater.checkIfDue() }

                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("Bhasha") },
                            actions = {
                                IconButton(onClick = { menu = true }) {
                                    BadgedBox(badge = { if (updateWaiting) Badge() }) {
                                        Icon(AppIcons.MoreVert, "Menu")
                                    }
                                }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    DropdownMenuItem(
                                        text = { Text("Help") },
                                        onClick = { menu = false; help = true },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Take the tour") },
                                        onClick = { menu = false; tour = true },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Theme") },
                                        onClick = { menu = false; themePicker = true },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Check for updates") },
                                        onClick = { menu = false; app.updater.check() },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("About") },
                                        onClick = { menu = false; about = true },
                                    )
                                }
                            },
                        )
                    },
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(
                                selected = !help && tab == 0, onClick = { help = false; tab = 0 },
                                icon = { Icon(AppIcons.Translate, null) }, label = { Text("Translate") },
                            )
                            NavigationBarItem(
                                selected = !help && tab == 1, onClick = { help = false; tab = 1 },
                                icon = { Icon(AppIcons.School, null) }, label = { Text("Learn") },
                            )
                            NavigationBarItem(
                                selected = !help && tab == 2, onClick = { help = false; tab = 2 },
                                icon = { Icon(AppIcons.Chip, null) }, label = { Text("Models") },
                            )
                        }
                    },
                ) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                        when {
                            help -> HelpScreen(onBack = { help = false }, onTour = { tour = true })
                            tab == 0 -> TranslateScreen(onOpenModels = { tab = 2 })
                            tab == 1 -> LearnTab(onOpenModels = { tab = 2 })
                            else -> ModelsScreen(onOpenHelp = { help = true })
                        }
                    }
                }

                if (tour) {
                    TourDialog(onClose = {
                        tour = false
                        app.settings.setTourSeen()
                    })
                } else {
                    // An update offer must not cover the first-run tour.
                    UpdateDialog()
                }
                if (about) AboutDialog(onClose = { about = false })
                if (themePicker) ThemeDialog(onClose = { themePicker = false })
            }
        }
    }
}
