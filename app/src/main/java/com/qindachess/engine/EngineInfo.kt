package com.qindachess.engine

data class EngineInfo(
    val name: String,
    val path: String,
    val author: String = "",
    val variant: String = "chess",
    val isLoaded: Boolean = false
)

data class SearchOptions(
    var depth: Int = 10,
    var timeMs: Long = 3000,
    var movestogo: Int = 30,
    var multiPv: Int = 1,
    var threads: Int = 2,
    var hashSize: Int = 256,
    var useNnue: Boolean = true
)

data class EngineMove(
    val uciMove: String,
    val scoreCp: Int,
    val mate: Int? = null,
    val pv: List<String> = emptyList(),
    val depth: Int = 0,
    val multipv: Int = 1
)

data class SearchResult(
    val bestMove: String,
    val ponder: String? = null,
    val moves: List<EngineMove> = emptyList(),
    val infoLines: List<String> = emptyList()
)
