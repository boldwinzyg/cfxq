package com.qindachess.record

data class Folder(
    val id: String,
    val name: String,
    val parentId: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

data class RecordNode(
    val id: String,
    val parentId: String?,
    val childrenIds: List<String> = emptyList(),
    val uci: String?,        // null 表示根节点
    val annotation: String? = null,
    val isMainLine: Boolean = true
) {
    fun withChild(childId: String): RecordNode =
        copy(childrenIds = childrenIds + childId)

    fun withAnnotation(text: String?): RecordNode =
        copy(annotation = text)
}

data class ChessRecord(
    val id: String,
    val title: String,
    val fen: String,
    val folderId: String,
    val rootNodeId: String,
    val activeNodeId: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

data class RecordFile(
    val record: ChessRecord,
    val nodes: Map<String, RecordNode>
)
