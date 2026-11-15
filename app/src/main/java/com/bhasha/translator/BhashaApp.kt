// SPDX-License-Identifier: AGPL-3.0-or-later
package com.bhasha.translator

import android.app.Application
import com.bhasha.translator.core.Settings
import com.bhasha.translator.engine.Engines
import com.bhasha.translator.learn.LearnRepository
import com.bhasha.translator.learn.Progress
import com.bhasha.translator.learn.Speaker
import com.bhasha.translator.model.ModelStore
import com.bhasha.translator.translate.TranslationSession
import com.bhasha.translator.translate.Translator
import com.bhasha.translator.update.Updater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Process-wide singletons, shared by the activity and the listening service. */
class BhashaApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    lateinit var settings: Settings private set
    lateinit var models: ModelStore private set
    lateinit var engines: Engines private set
    lateinit var session: TranslationSession private set
    lateinit var translator: Translator private set
    lateinit var learn: LearnRepository private set
    lateinit var progress: Progress private set
    lateinit var updater: Updater private set
    val speaker: Speaker by lazy { Speaker(this) }

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        models = ModelStore(this, scope)
        engines = Engines(this, models, settings, scope)
        session = TranslationSession()
        translator = Translator(engines)
        learn = LearnRepository(this)
        progress = Progress(this)
        updater = Updater(this, settings, scope)
    }
}
