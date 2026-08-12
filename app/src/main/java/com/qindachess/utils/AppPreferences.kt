package com.qindachess.utils

import android.content.Context
import android.content.SharedPreferences

class AppPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "qindachess_prefs"

        // Engine settings
        private const val KEY_USE_ENGINE = "use_engine"
        private const val KEY_ENGINE_PATH = "engine_path"
        private const val KEY_NNUE_PATH = "nnue_path"
        private const val KEY_SEARCH_DEPTH = "search_depth"
        private const val KEY_SEARCH_TIME_MS = "search_time_ms"
        private const val KEY_THREAD_COUNT = "thread_count"
        private const val KEY_HASH_SIZE_MB = "hash_size_mb"
        private const val KEY_USE_NNUE = "use_nnue"
        private const val KEY_MULTI_PV = "multi_pv"
        private const val KEY_PONDER = "ponder_enabled"
        private const val KEY_CONTEMPT = "contempt"
        private const val KEY_UCI_LIMIT_STRENGTH = "uci_limit_strength"
        private const val KEY_UCI_ELO = "uci_elo"

        // Book settings
        private const val KEY_USE_BOOK = "use_opening_book"
        private const val KEY_BOOK_RANDOMIZE = "book_randomize"
        private const val KEY_BOOK_PATH = "book_path"
        private const val KEY_MAX_BOOK_MOVES = "max_book_moves"
        private const val KEY_MIN_BOOK_WEIGHT = "min_book_weight"
        private const val KEY_CLOUD_ENABLED = "cloud_book_enabled"
        private const val KEY_CLOUD_PRIORITIZE = "cloud_prioritize"
        private const val KEY_MAX_CLOUD_MOVES = "max_cloud_moves"

        // Theme settings
        private const val KEY_THEME = "theme"
        private const val KEY_BOARD_SKIN_ID = "board_skin_id"
        private const val KEY_PIECE_STYLE_ID = "piece_style_id"

        // Display settings
        private const val KEY_SHOW_COORDINATES = "show_coordinates"
        private const val KEY_FLIP_BY_DEFAULT = "flip_board_by_default"

        // Auto-play settings
        private const val KEY_AUTO_MOVE_DELAY = "auto_move_delay"
        private const val KEY_TARGET_APP = "target_app"

        // Recognition settings
        private const val KEY_AUTO_DETECT_BOARD = "auto_detect_board"
        private const val KEY_RECOGNITION_CONFIDENCE = "recognition_confidence"
    }

    var useEngine: Boolean
        get() = prefs.getBoolean(KEY_USE_ENGINE, true)
        set(value) = prefs.edit().putBoolean(KEY_USE_ENGINE, value).apply()

    var enginePath: String
        get() = prefs.getString(KEY_ENGINE_PATH, "") ?: ""
        set(value) = prefs.edit().putString(KEY_ENGINE_PATH, value).apply()

    var nnuePath: String
        get() = prefs.getString(KEY_NNUE_PATH, "") ?: ""
        set(value) = prefs.edit().putString(KEY_NNUE_PATH, value).apply()

    var searchDepth: Int
        get() = prefs.getInt(KEY_SEARCH_DEPTH, 12)
        set(value) = prefs.edit().putInt(KEY_SEARCH_DEPTH, value).apply()

    var searchTimeMs: Long
        get() = prefs.getLong(KEY_SEARCH_TIME_MS, 3000L)
        set(value) = prefs.edit().putLong(KEY_SEARCH_TIME_MS, value).apply()

    var threadCount: Int
        get() = prefs.getInt(KEY_THREAD_COUNT, 2)
        set(value) = prefs.edit().putInt(KEY_THREAD_COUNT, value).apply()

    var hashSizeMb: Int
        get() = prefs.getInt(KEY_HASH_SIZE_MB, 256)
        set(value) = prefs.edit().putInt(KEY_HASH_SIZE_MB, value).apply()

    var useNnue: Boolean
        get() = prefs.getBoolean(KEY_USE_NNUE, true)
        set(value) = prefs.edit().putBoolean(KEY_USE_NNUE, value).apply()

    var multiPv: Int
        get() = prefs.getInt(KEY_MULTI_PV, 3)
        set(value) = prefs.edit().putInt(KEY_MULTI_PV, value).apply()

    var ponderEnabled: Boolean
        get() = prefs.getBoolean(KEY_PONDER, false)
        set(value) = prefs.edit().putBoolean(KEY_PONDER, value).apply()

    var contempt: Int
        get() = prefs.getInt(KEY_CONTEMPT, 0)
        set(value) = prefs.edit().putInt(KEY_CONTEMPT, value).apply()

    var uciLimitStrength: Boolean
        get() = prefs.getBoolean(KEY_UCI_LIMIT_STRENGTH, false)
        set(value) = prefs.edit().putBoolean(KEY_UCI_LIMIT_STRENGTH, value).apply()

    var uciElo: Int
        get() = prefs.getInt(KEY_UCI_ELO, 1800)
        set(value) = prefs.edit().putInt(KEY_UCI_ELO, value).apply()

    var useBook: Boolean
        get() = prefs.getBoolean(KEY_USE_BOOK, true)
        set(value) = prefs.edit().putBoolean(KEY_USE_BOOK, value).apply()

    var bookRandomize: Boolean
        get() = prefs.getBoolean(KEY_BOOK_RANDOMIZE, true)
        set(value) = prefs.edit().putBoolean(KEY_BOOK_RANDOMIZE, value).apply()

    var bookPath: String
        get() = prefs.getString(KEY_BOOK_PATH, "") ?: ""
        set(value) = prefs.edit().putString(KEY_BOOK_PATH, value).apply()

    var maxBookMoves: Int
        get() = prefs.getInt(KEY_MAX_BOOK_MOVES, 20)
        set(value) = prefs.edit().putInt(KEY_MAX_BOOK_MOVES, value).apply()

    var minBookWeight: Int
        get() = prefs.getInt(KEY_MIN_BOOK_WEIGHT, 10)
        set(value) = prefs.edit().putInt(KEY_MIN_BOOK_WEIGHT, value).apply()

    var cloudEnabled: Boolean
        get() = prefs.getBoolean(KEY_CLOUD_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_CLOUD_ENABLED, value).apply()

    var cloudPrioritize: Boolean
        get() = prefs.getBoolean(KEY_CLOUD_PRIORITIZE, false)
        set(value) = prefs.edit().putBoolean(KEY_CLOUD_PRIORITIZE, value).apply()

    var maxCloudMoves: Int
        get() = prefs.getInt(KEY_MAX_CLOUD_MOVES, 30)
        set(value) = prefs.edit().putInt(KEY_MAX_CLOUD_MOVES, value).apply()

    var theme: String
        get() = prefs.getString(KEY_THEME, "light") ?: "light"
        set(value) = prefs.edit().putString(KEY_THEME, value).apply()

    var boardSkinId: String
        get() = prefs.getString(KEY_BOARD_SKIN_ID, "wood_classic") ?: "wood_classic"
        set(value) = prefs.edit().putString(KEY_BOARD_SKIN_ID, value).apply()

    var pieceStyleId: String
        get() = prefs.getString(KEY_PIECE_STYLE_ID, "traditional") ?: "traditional"
        set(value) = prefs.edit().putString(KEY_PIECE_STYLE_ID, value).apply()

    var showCoordinates: Boolean
        get() = prefs.getBoolean(KEY_SHOW_COORDINATES, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_COORDINATES, value).apply()

    var flipBoardByDefault: Boolean
        get() = prefs.getBoolean(KEY_FLIP_BY_DEFAULT, false)
        set(value) = prefs.edit().putBoolean(KEY_FLIP_BY_DEFAULT, value).apply()

    var autoMoveDelay: Long
        get() = prefs.getLong(KEY_AUTO_MOVE_DELAY, 500L)
        set(value) = prefs.edit().putLong(KEY_AUTO_MOVE_DELAY, value).apply()

    var targetApp: String
        get() = prefs.getString(KEY_TARGET_APP, "general") ?: "general"
        set(value) = prefs.edit().putString(KEY_TARGET_APP, value).apply()

    var autoDetectBoard: Boolean
        get() = prefs.getBoolean(KEY_AUTO_DETECT_BOARD, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_DETECT_BOARD, value).apply()

    var recognitionConfidence: Int
        get() = prefs.getInt(KEY_RECOGNITION_CONFIDENCE, 50)
        set(value) = prefs.edit().putInt(KEY_RECOGNITION_CONFIDENCE, value).apply()

    fun reset() {
        prefs.edit().clear().apply()
    }
}
