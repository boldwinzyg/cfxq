package com.qindachess.record

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class RecordStorage private constructor(context: Context) {

    private val rootDir = File(context.filesDir, "records")
    private val foldersFile = File(rootDir, "folders.json")
    private val recordsDir = File(rootDir, "records")

    private val lock = Any()

    fun ensureDirs() {
        synchronized(lock) {
            if (!rootDir.exists()) rootDir.mkdirs()
            if (!recordsDir.exists()) recordsDir.mkdirs()
            if (!foldersFile.exists()) {
                foldersFile.writeText("[]")
            }
        }
    }

    fun listFolders(): List<Folder> {
        synchronized(lock) {
            return try {
                val arr = JSONArray(foldersFile.readText())
                val list = ArrayList<Folder>(arr.length())
                for (i in 0 until arr.length()) {
                    list.add(jsonToFolder(arr.getJSONObject(i)))
                }
                list
            } catch (e: Exception) {
                Log.e(TAG, "listFolders failed", e)
                emptyList()
            }
        }
    }

    fun createFolder(folder: Folder): Boolean {
        synchronized(lock) {
            return try {
                val arr = JSONArray(foldersFile.readText())
                arr.put(folderToJson(folder))
                foldersFile.writeText(arr.toString())
                true
            } catch (e: Exception) {
                Log.e(TAG, "createFolder failed", e)
                false
            }
        }
    }

    fun updateFolder(id: String, name: String): Boolean {
        synchronized(lock) {
            return try {
                val arr = JSONArray(foldersFile.readText())
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    if (obj.getString("id") == id) {
                        obj.put("name", name)
                        foldersFile.writeText(arr.toString())
                        return true
                    }
                }
                false
            } catch (e: Exception) {
                Log.e(TAG, "updateFolder failed", e)
                false
            }
        }
    }

    fun deleteFolder(id: String): Boolean {
        synchronized(lock) {
            val records = listRecords(id)
            records.forEach { deleteRecordInternal(it.id) }
            return try {
                val arr = JSONArray(foldersFile.readText())
                val newArr = JSONArray()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    if (obj.getString("id") != id) newArr.put(obj)
                }
                foldersFile.writeText(newArr.toString())
                true
            } catch (e: Exception) {
                Log.e(TAG, "deleteFolder failed", e)
                false
            }
        }
    }

    fun listRecords(folderId: String): List<ChessRecord> {
        synchronized(lock) {
            return try {
                recordsDir.listFiles { f -> f.extension == "json" }
                    ?.mapNotNull { file ->
                        try {
                            val json = JSONObject(file.readText())
                            val record = jsonToChessRecord(json.getJSONObject("record"))
                            if (record.folderId == folderId) record else null
                        } catch (_: Exception) { null }
                    }
                    ?.sortedBy { it.createdAt }
                    ?: emptyList()
            } catch (e: Exception) {
                Log.e(TAG, "listRecords failed", e)
                emptyList()
            }
        }
    }

    fun saveRecord(file: RecordFile): Boolean {
        synchronized(lock) {
            return try {
                val json = recordFileToJson(file)
                val target = File(recordsDir, "${file.record.id}.json")
                target.writeText(json.toString())
                true
            } catch (e: Exception) {
                Log.e(TAG, "saveRecord failed", e)
                false
            }
        }
    }

    fun deleteRecord(recordId: String): Boolean {
        synchronized(lock) {
            val ok = deleteRecordInternal(recordId)
            if (ok) return true
            val all = listFolders()
            for (f in all) {
                val records = listRecords(f.id)
                if (records.any { it.id == recordId }) {
                    return true
                }
            }
            return false
        }
    }

    private fun deleteRecordInternal(recordId: String): Boolean {
        val target = File(recordsDir, "$recordId.json")
        return if (target.exists()) target.delete() else false
    }

    fun loadRecord(recordId: String): RecordFile? {
        synchronized(lock) {
            return try {
                val target = File(recordsDir, "$recordId.json")
                if (!target.exists()) return null
                val json = JSONObject(target.readText())
                jsonToRecordFile(json)
            } catch (e: Exception) {
                Log.e(TAG, "loadRecord failed", e)
                null
            }
        }
    }

    fun moveRecord(recordId: String, targetFolderId: String): Boolean {
        val file = loadRecord(recordId) ?: return false
        val updated = file.copy(
            record = file.record.copy(
                folderId = targetFolderId,
                updatedAt = System.currentTimeMillis()
            )
        )
        return saveRecord(updated)
    }

    // ============== JSON 序列化 ==============

    private fun folderToJson(f: Folder): JSONObject = JSONObject().apply {
        put("id", f.id)
        put("name", f.name)
        put("parentId", f.parentId ?: JSONObject.NULL)
        put("createdAt", f.createdAt)
    }

    private fun jsonToFolder(obj: JSONObject): Folder = Folder(
        id = obj.getString("id"),
        name = obj.getString("name"),
        parentId = nullableString(obj, "parentId"),
        createdAt = obj.getLong("createdAt")
    )

    private fun chessRecordToJson(r: ChessRecord): JSONObject = JSONObject().apply {
        put("id", r.id)
        put("title", r.title)
        put("fen", r.fen)
        put("folderId", r.folderId)
        put("rootNodeId", r.rootNodeId)
        put("activeNodeId", r.activeNodeId)
        put("createdAt", r.createdAt)
        put("updatedAt", r.updatedAt)
    }

    private fun jsonToChessRecord(obj: JSONObject): ChessRecord = ChessRecord(
        id = obj.getString("id"),
        title = obj.getString("title"),
        fen = obj.getString("fen"),
        folderId = obj.getString("folderId"),
        rootNodeId = obj.getString("rootNodeId"),
        activeNodeId = obj.getString("activeNodeId"),
        createdAt = obj.getLong("createdAt"),
        updatedAt = obj.getLong("updatedAt")
    )

    private fun nodeToJson(n: RecordNode): JSONObject = JSONObject().apply {
        put("id", n.id)
        put("parentId", n.parentId ?: JSONObject.NULL)
        put("childrenIds", JSONArray(n.childrenIds))
        put("uci", n.uci ?: JSONObject.NULL)
        put("annotation", n.annotation ?: JSONObject.NULL)
        put("isMainLine", n.isMainLine)
    }

    private fun jsonToNode(obj: JSONObject): RecordNode {
        val childrenArr = obj.getJSONArray("childrenIds")
        val children = ArrayList<String>(childrenArr.length())
        for (i in 0 until childrenArr.length()) children.add(childrenArr.getString(i))
        return RecordNode(
            id = obj.getString("id"),
            parentId = nullableString(obj, "parentId"),
            childrenIds = children,
            uci = nullableString(obj, "uci"),
            annotation = nullableString(obj, "annotation"),
            isMainLine = obj.getBoolean("isMainLine")
        )
    }

    private fun recordFileToJson(rf: RecordFile): JSONObject = JSONObject().apply {
        put("record", chessRecordToJson(rf.record))
        val nodesJson = JSONObject()
        for ((id, node) in rf.nodes) {
            nodesJson.put(id, nodeToJson(node))
        }
        put("nodes", nodesJson)
    }

    private fun jsonToRecordFile(obj: JSONObject): RecordFile {
        val record = jsonToChessRecord(obj.getJSONObject("record"))
        val nodesObj = obj.getJSONObject("nodes")
        val nodes = HashMap<String, RecordNode>()
        val keys = nodesObj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            nodes[k] = jsonToNode(nodesObj.getJSONObject(k))
        }
        return RecordFile(record = record, nodes = nodes)
    }

    private fun nullableString(obj: JSONObject, key: String): String? {
        return if (!obj.has(key) || obj.isNull(key)) null else obj.getString(key)
    }

    companion object {
        private const val TAG = "RecordStorage"
        fun create(context: Context): RecordStorage = RecordStorage(context.applicationContext)
    }
}
