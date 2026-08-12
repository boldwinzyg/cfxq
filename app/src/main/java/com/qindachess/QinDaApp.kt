package com.qindachess

import android.app.Application
import android.util.Log
import com.qindachess.book.BookManager
import com.qindachess.book.CloudBookManager
import com.qindachess.book.EmptyBook
import com.qindachess.engine.GameManager
import com.qindachess.engine.GameManagerV2
import com.qindachess.engine.ResourceManager
import com.qindachess.engine.UciEngineManager
import com.qindachess.record.RecordManager
import com.qindachess.ui.theme.ThemeManager
import com.qindachess.utils.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class QinDaApp : Application() {

    companion object {
        private const val TAG = "QinDaApp"
        lateinit var instance: QinDaApp
            private set
    }

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val prefs by lazy { AppPreferences(this) }
    val themeManager by lazy { ThemeManager.getInstance(this) }
    val engineManager = UciEngineManager()
    val bookManager = BookManager.getInstance()
    val cloudBookManager = CloudBookManager()
    lateinit var gameManager: GameManager
        private set
    lateinit var gameManagerV2: GameManagerV2
        private set
    lateinit var resourceManager: ResourceManager
        private set

    var resourcesLoaded = false
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        RecordManager.init(this)

        resourceManager = ResourceManager(this)
        gameManager = GameManager(engineManager, BookManager.getInstance().activeBook ?: EmptyBook)
        gameManagerV2 = GameManagerV2(engineManager, bookManager, cloudBookManager)

        appScope.launch {
            try {
                loadOpenCV()
            } catch (e: Exception) {
                Log.e(TAG, "OpenCV load failed", e)
            }

            try {
                val result = resourceManager.deployAssets()
                Log.i(TAG, "Resources deployed: engine=${result.enginePath != null}, " +
                    "nnue=${result.nnuePath != null}, book=${result.bookPath != null}")

                if (result.bookPath != null) {
                    val bookInfo = bookManager.registerBuiltInBook(
                        "华山狂刀内置库",
                        "随应用打包的默认开局库",
                        result.bookPath
                    )
                    Log.i(TAG, "Built-in book registered: ${bookInfo?.name ?: "failed"}")
                }

                if (result.enginePath != null) {
                    val engineLoaded = engineManager.loadEngine(result.enginePath, result.nnuePath)
                    Log.i(TAG, "Engine loaded: $engineLoaded")

                    if (engineLoaded) {
                        engineManager.applySearchOptions(
                            com.qindachess.engine.SearchOptions(
                                depth = prefs.searchDepth,
                                timeMs = prefs.searchTimeMs,
                                threads = prefs.threadCount,
                                hashSize = prefs.hashSizeMb,
                                multiPv = prefs.multiPv,
                                useNnue = prefs.useNnue
                            )
                        )
                    }
                }

                resourcesLoaded = true
            } catch (e: Exception) {
                Log.e(TAG, "Resource loading failed", e)
            }
        }
    }

    private fun loadOpenCV() {
        Log.i(TAG, "Image recognition works without OpenCV (Android API)")
    }

    override fun onTerminate() {
        super.onTerminate()
        engineManager.stopEngine()
    }
}
