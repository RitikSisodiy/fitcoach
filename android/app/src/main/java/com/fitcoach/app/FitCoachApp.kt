package com.fitcoach.app

import android.app.Application
import android.content.Context
import com.fitcoach.app.data.Db
import com.fitcoach.app.data.Store
import com.fitcoach.app.engine.CoachService
import com.fitcoach.app.engine.FoodTable
import com.fitcoach.app.engine.Prompts
import com.fitcoach.app.llm.GeminiProvider
import com.fitcoach.app.llm.LlmException
import com.fitcoach.app.llm.LlmProvider
import com.fitcoach.app.llm.LlmRequest
import com.fitcoach.app.llm.LlmResponse
import com.fitcoach.app.notify.Notifier
import com.fitcoach.app.telegram.Telegram
import com.fitcoach.app.voice.CallManager
import com.fitcoach.app.work.Scheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.time.ZoneId

/** Process-wide singletons. Everything runs on the phone: no server, no forwarder apps. */
class FitCoachApp : Application() {

    lateinit var store: Store
        private set
    lateinit var foods: FoodTable
        private set
    lateinit var prompts: Prompts
        private set

    @Volatile private var cachedService: CoachService? = null
    @Volatile private var cachedKey: String? = null

    /** Bumped whenever the database changes in a way the UI should show (new messages, sync results). */
    private val _version = MutableStateFlow(0L)
    val dataVersion: StateFlow<Long> = _version

    override fun onCreate() {
        super.onCreate()
        instance = this
        store = Store(Db(this).writableDatabase, ZoneId.systemDefault())
        foods = assets.open("foods_seed.csv").use(FoodTable::load)
        prompts = Prompts.fromAssets(assets)
        Notifier.createChannels(this)
        CallManager.createChannel(this)
        Scheduler.ensureScheduled(this)
        Telegram.ensureRunning(this)
    }

    fun notifyDataChanged() { _version.value = _version.value + 1 }

    private val prefs get() = getSharedPreferences("settings", Context.MODE_PRIVATE)

    var apiKey: String?
        get() = prefs.getString(KEY_API, null)?.takeIf { it.isNotBlank() }
        set(value) { prefs.edit().putString(KEY_API, value?.trim()).apply() }

    /** The coach service, rebuilt when the API key changes. */
    @Synchronized
    fun service(): CoachService {
        val key = apiKey
        cachedService?.let { if (key == cachedKey) return it }
        val llm: LlmProvider = if (key == null) NoKeyProvider else GeminiProvider(key, usageFile = File(filesDir, "gemini_usage.json"))
        return CoachService(store, llm, foods, prompts).also {
            it.telegramLinked = { Telegram.isLinked(this) }
            it.callsSupported = { CallManager.canCall(this) }
            it.planChanged = { Scheduler.runNow(this) }
            cachedService = it; cachedKey = key
        }
    }

    private object NoKeyProvider : LlmProvider {
        override val name = "none"
        override suspend fun generate(request: LlmRequest): LlmResponse = throw LlmException("No Gemini API key set (Setup tab)")
    }

    companion object {
        private const val KEY_API = "gemini_api_key"
        lateinit var instance: FitCoachApp
            private set
    }
}
