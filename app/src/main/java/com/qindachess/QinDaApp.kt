package com.qindachess

import android.app.Application
import android.util.Log
import com.qindachess.book.BookManager
import com.qindachess.book.CloudBookManager
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
        gameManager = GameManager(engineManager, bookManager)
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
                } else {
                    // assets 没有有效开局库（或被识别为占位符）→ 直接注册 BuiltInBook 兜底
                    Log.w(TAG, "No book asset deployed, registering BuiltInBook directly")
                    bookManager.registerBuiltInBook(
                        "内置兜底开局库",
                        "内置主流布局前 3-5 步",
                        ""   // 空路径，让 BookManager 走 registerFallbackBook
                    )
                }

                // 引擎路径优先级：
                // 1) AppPreferences 中用户已设置且文件存在
                // 2) assets 部署的内置引擎
                val savedEnginePath = prefs.enginePath.takeIf { it.isNotBlank() && java.io.File(it).exists() }
                val finalEnginePath = savedEnginePath ?: result.enginePath
                val finalNnuePath = if (savedEnginePath != null) {
                    prefs.nnuePath.takeIf { it.isNotBlank() && java.io.File(it).exists() }
                } else result.nnuePath

                if (finalEnginePath != null) {
                    val engineLoaded = engineManager.loadEngine(finalEnginePath, finalNnuePath)
                    Log.i(TAG, "Engine loaded: $engineLoaded (path=$finalEnginePath)")

                    if (engineLoaded) {
                        // 记住实际可用的路径，下次启动直接用
                        prefs.enginePath = finalEnginePath
                        if (finalNnuePath != null) prefs.nnuePath = finalNnuePath

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
                } else {
                    Log.w(TAG, "无任何引擎可用，请到菜单→引擎设置中导入 pikafish / yukfish / .so")
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
