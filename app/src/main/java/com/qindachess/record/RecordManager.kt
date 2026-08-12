package com.qindachess.record

import android.content.Context
import android.util.Log
import java.util.UUID

object RecordManager {

    private const val TAG = "RecordManager"
    private const val DEFAULT_FOLDER_NAME = "默认收藏"

    private lateinit var storage: RecordStorage
    private var initialized = false

    fun init(context: Context) {
        if (initialized) {
            Log.i(TAG, "already initialized, skip")
            return
        }
        storage = RecordStorage.create(context)
        storage.ensureDirs()
        ensureDefaultFolder()
        initialized = true
        Log.i(TAG, "initialized")
    }

    private fun ensureDefaultFolder() {
        val existing = storage.listFolders().find { it.name == DEFAULT_FOLDER_NAME }
        if (existing != null) {
            Log.i(TAG, "default folder already exists: ${existing.id}")
            return
        }
        val folder = Folder(
            id = UUID.randomUUID().toString(),
            name = DEFAULT_FOLDER_NAME,
            parentId = null
        )
        storage.createFolder(folder)
        Log.i(TAG, "default folder created: ${folder.id}")
    }

    fun listFolders(): List<Folder> {
        return storage.listFolders().sortedBy { it.createdAt }
    }

    fun createFolder(name: String, parentId: String? = null): Folder {
        val folder = Folder(
            id = UUID.randomUUID().toString(),
            name = name,
            parentId = parentId
        )
        storage.createFolder(folder)
        Log.i(TAG, "folder created: ${folder.id} name=$name")
        return folder
    }

    fun renameFolder(id: String, name: String): Boolean {
        val ok = storage.updateFolder(id, name)
        Log.i(TAG, "rename folder $id -> $name: $ok")
        return ok
    }

    fun deleteFolder(id: String): Boolean {
        val ok = storage.deleteFolder(id)
        Log.i(TAG, "delete folder $id: $ok")
        return ok
    }

    fun listRecords(folderId: String): List<ChessRecord> {
        return storage.listRecords(folderId)
    }

    /**
     * 列出所有收藏目录下的棋谱，按 updatedAt 倒序。
     * 用于主界面棋谱 Tab 展示。
     */
    fun listAllRecords(): List<ChessRecord> {
        val folders = storage.listFolders()
        val all = folders.flatMap { storage.listRecords(it.id) }
        return all.sortedByDescending { it.updatedAt }
    }

    fun createRecord(
        folderId: String,
        title: String,
        fen: String,
        rootUciHistory: List<String>? = null
    ): ChessRecord {
        val nodes = HashMap<String, RecordNode>()

        val rootId = UUID.randomUUID().toString()
        val rootNode = RecordNode(
            id = rootId,
            parentId = null,
            uci = null
        )
        nodes[rootId] = rootNode

        var currentParentId = rootId
        rootUciHistory?.forEach { uci ->
            val nodeId = UUID.randomUUID().toString()
            val node = RecordNode(
                id = nodeId,
                parentId = currentParentId,
                uci = uci
            )
            nodes[nodeId] = node

            val parent = nodes[currentParentId]!!
            nodes[currentParentId] = parent.withChild(nodeId)
            currentParentId = nodeId
        }

        val now = System.currentTimeMillis()
        val recordId = UUID.randomUUID().toString()
        val record = ChessRecord(
            id = recordId,
            title = title,
            fen = fen,
            folderId = folderId,
            rootNodeId = rootId,
            activeNodeId = currentParentId,
            createdAt = now,
            updatedAt = now
        )

        val file = RecordFile(record = record, nodes = nodes)
        storage.saveRecord(file)
        Log.i(TAG, "record created: $recordId title=$title nodes=${nodes.size}")
        return record
    }

    fun saveRecord(recordFile: RecordFile) {
        storage.saveRecord(recordFile)
        Log.i(TAG, "record saved: ${recordFile.record.id}")
    }

    fun deleteRecord(recordId: String) {
        storage.deleteRecord(recordId)
        Log.i(TAG, "record deleted: $recordId")
    }

    fun moveRecord(recordId: String, targetFolderId: String) {
        storage.moveRecord(recordId, targetFolderId)
        Log.i(TAG, "record moved: $recordId -> folder $targetFolderId")
    }

    fun loadRecord(recordId: String): RecordFile? {
        return storage.loadRecord(recordId)
    }

    fun appendMoveToBranch(recordFile: RecordFile, parentNodeId: String, uci: String): RecordFile {
        val nodes = recordFile.nodes.toMutableMap()
        val parent = nodes[parentNodeId]
            ?: throw IllegalArgumentException("parent node not found: $parentNodeId")

        val existing = parent.childrenIds
            .mapNotNull { nodes[it] }
            .firstOrNull { it.uci == uci }
        if (existing != null) {
            val updated = recordFile.record.copy(
                activeNodeId = existing.id,
                updatedAt = System.currentTimeMillis()
            )
            Log.i(TAG, "append move $uci under $parentNodeId already exists at ${existing.id}")
            return RecordFile(record = updated, nodes = nodes)
        }

        val newId = UUID.randomUUID().toString()
        val newNode = RecordNode(
            id = newId,
            parentId = parentNodeId,
            uci = uci
        )
        nodes[newId] = newNode
        nodes[parentNodeId] = parent.withChild(newId)

        val updatedRecord = recordFile.record.copy(
            activeNodeId = newId,
            updatedAt = System.currentTimeMillis()
        )
        val updated = RecordFile(record = updatedRecord, nodes = nodes)
        Log.i(TAG, "append move $uci under $parentNodeId -> new node $newId")
        return updated
    }

    fun setAnnotation(recordFile: RecordFile, nodeId: String, text: String?): RecordFile {
        val nodes = recordFile.nodes.toMutableMap()
        val node = nodes[nodeId]
            ?: throw IllegalArgumentException("node not found: $nodeId")
        nodes[nodeId] = node.withAnnotation(text?.ifBlank { null })

        val updatedRecord = recordFile.record.copy(updatedAt = System.currentTimeMillis())
        return RecordFile(record = updatedRecord, nodes = nodes)
    }

    fun deleteNode(recordFile: RecordFile, nodeId: String): RecordFile {
        if (nodeId == recordFile.record.rootNodeId) {
            throw IllegalArgumentException("cannot delete root node")
        }

        val nodes = recordFile.nodes.toMutableMap()
        val target = nodes[nodeId]
            ?: throw IllegalArgumentException("node not found: $nodeId")

        val descendants = collectDescendantIds(nodes, nodeId)
        val idsToRemove = descendants + nodeId

        val parentId = target.parentId
        if (parentId != null) {
            val parent = nodes[parentId]!!
            nodes[parentId] = parent.copy(childrenIds = parent.childrenIds.filter { it != nodeId })
        }

        idsToRemove.forEach { nodes.remove(it) }

        val activeNodeId = recordFile.record.activeNodeId
        val newActive = if (idsToRemove.contains(activeNodeId)) parentId ?: recordFile.record.rootNodeId else activeNodeId

        val updatedRecord = recordFile.record.copy(
            activeNodeId = newActive,
            updatedAt = System.currentTimeMillis()
        )
        Log.i(TAG, "delete node $nodeId removed ${idsToRemove.size} nodes")
        return RecordFile(record = updatedRecord, nodes = nodes)
    }

    fun buildNodePath(recordFile: RecordFile, targetNodeId: String): List<RecordNode> {
        val nodes = recordFile.nodes
        val path = ArrayList<RecordNode>()
        var current: RecordNode? = nodes[targetNodeId] ?: return emptyList()
        while (current != null) {
            path.add(0, current)
            current = current.parentId?.let { nodes[it] }
        }
        return path
    }

    fun uciHistoryUpTo(recordFile: RecordFile, nodeId: String): List<String> {
        val path = buildNodePath(recordFile, nodeId)
        return path.mapNotNull { it.uci }
    }

    fun getDefaultFolder(): Folder {
        return storage.listFolders()
            .sortedBy { it.createdAt }
            .find { it.name == DEFAULT_FOLDER_NAME }
            ?: throw IllegalStateException("default folder missing — init() not called?")
    }

    private fun collectDescendantIds(nodes: Map<String, RecordNode>, nodeId: String): Set<String> {
        val result = HashSet<String>()
        val stack = ArrayDeque<String>()
        stack.add(nodeId)
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            val node = nodes[current] ?: continue
            node.childrenIds.forEach { childId ->
                if (result.add(childId)) {
                    stack.add(childId)
                }
            }
        }
        return result
    }
}
