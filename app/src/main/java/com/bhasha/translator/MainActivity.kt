// SPDX-License-Identifier: AGPL-3.0-or-later
package com.bhasha.translator

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.bhasha.translator.ui.AppIcons
import com.bhasha.translator.ui.BhashaTheme
import com.bhasha.translator.ui.LearnTab
import com.bhasha.translator.ui.ModelsScreen
import com.bhasha.translator.ui.TranslateScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BhashaTheme {
                var tab by rememberSaveable { mutableIntStateOf(0) }
                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(
                                selected = tab == 0, onClick = { tab = 0 },
                                icon = { Icon(AppIcons.Translate, null) }, label = { Text("Translate") },
                            )
                            NavigationBarItem(
                                selected = tab == 1, onClick = { tab = 1 },
                                icon = { Icon(AppIcons.School, null) }, label = { Text("Learn") },
                            )
                            NavigationBarItem(
                                selected = tab == 2, onClick = { tab = 2 },
                                icon = { Icon(AppIcons.Chip, null) }, label = { Text("Models") },
                            )
                        }
                    },
                ) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                        when (tab) {
                            0 -> TranslateScreen(onOpenModels = { tab = 2 })
                            1 -> LearnTab(onOpenModels = { tab = 2 })
                            else -> ModelsScreen()
                        }
                    }
                }
            }
        }
    }
}
