package com.qindachess.ui

import android.os.Bundle
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.qindachess.R
import com.qindachess.record.ChessRecord
import com.qindachess.record.Folder
import com.qindachess.record.RecordManager

class FavoritesFragment : Fragment() {

    private var currentFolderId: String? = null

    private lateinit var recyclerView: RecyclerView
    private lateinit var pathText: TextView
    private lateinit var emptyState: View
    private lateinit var backBtn: ImageButton

    private lateinit var adapter: FavoritesAdapter
    private var allItems: List<ListItem> = emptyList()

    private val pathSegments = ArrayList<Folder?>()

    sealed class ListItem {
        data class FolderItem(val folder: Folder) : ListItem()
        data class RecordItem(val record: ChessRecord) : ListItem()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_favorites, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recyclerView = view.findViewById(R.id.favRecyclerView)
        pathText = view.findViewById(R.id.favCurrentPath)
        emptyState = view.findViewById(R.id.favEmptyState)
        backBtn = view.findViewById(R.id.favBackFolder)

        val clickListener = { item: ListItem -> onItemClick(item) }
        val menuClickListener = { item: ListItem, anchor: View -> onItemMenuClick(item, anchor) }

        adapter = FavoritesAdapter(clickListener, menuClickListener)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        backBtn.setOnClickListener { goToParentFolder() }

        pathSegments.clear()
        pathSegments.add(null)
        refresh()
    }

    fun refresh() {
        val allFolders = RecordManager.listFolders()
        val folders = allFolders.filter { it.parentId == currentFolderId }
        val records = if (currentFolderId != null) {
            RecordManager.listRecords(currentFolderId!!)
        } else {
            emptyList()
        }

        val folderCountMap = HashMap<String, Int>()
        for (f in allFolders) {
            if (f.parentId != null) {
                folderCountMap[f.parentId] = (folderCountMap[f.parentId] ?: 0) + 1
            }
        }

        val newItems = ArrayList<ListItem>()
        folders.sortedBy { it.name }.forEach { newItems.add(ListItem.FolderItem(it)) }
        records.sortedByDescending { it.updatedAt }.forEach { newItems.add(ListItem.RecordItem(it)) }

        allItems = newItems
        adapter.submitList(newItems, folderCountMap)

        if (newItems.isEmpty()) {
            emptyState.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
        } else {
            emptyState.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
        }

        updatePathText()
    }

    private fun updatePathText() {
        val names = pathSegments.map { it?.name ?: "全部棋谱" }
        pathText.text = names.joinToString(" / ")
    }

    private fun goToFolder(folderId: String, name: String) {
        val folder = Folder(id = folderId, name = name, parentId = currentFolderId)
        pathSegments.add(folder)
        currentFolderId = folderId
        refresh()
    }

    private fun goToParentFolder() {
        if (currentFolderId == null) {
            Toast.makeText(requireContext(), "已在根目录", Toast.LENGTH_SHORT).show()
            return
        }
        pathSegments.removeLast()
        currentFolderId = pathSegments.lastOrNull()?.id
        refresh()
    }

    private fun onItemClick(item: ListItem) {
        when (item) {
            is ListItem.FolderItem -> goToFolder(item.folder.id, item.folder.name)
            is ListItem.RecordItem -> openRecord(item.record)
        }
    }

    private fun onItemMenuClick(item: ListItem, anchor: View) {
        when (item) {
            is ListItem.FolderItem -> showFolderMenu(item.folder, anchor)
            is ListItem.RecordItem -> showRecordMenu(item.record, anchor)
        }
    }

    private fun showFolderMenu(folder: Folder, anchor: View) {
        val menu = PopupMenu(requireContext(), anchor)
        menu.menu.add(0, 1, 0, "打开")
        menu.menu.add(0, 2, 1, "重命名")
        menu.menu.add(0, 3, 2, "删除")
        menu.setOnMenuItemClickListener { mi ->
            when (mi.itemId) {
                1 -> goToFolder(folder.id, folder.name)
                2 -> showRenameFolderDialog(folder)
                3 -> showDeleteFolderConfirm(folder)
            }
            true
        }
        menu.show()
    }

    private fun showRenameFolderDialog(folder: Folder) {
        val edit = EditText(requireContext()).apply {
            hint = "目录名"
            setText(folder.name)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            layoutParams = lp
            setPadding(32, 16, 32, 16)
        }
        AlertDialog.Builder(requireContext())
            .setTitle("重命名目录")
            .setView(edit)
            .setPositiveButton("确定") { _, _ ->
                val name = edit.text.toString().trim()
                if (name.isBlank()) {
                    Toast.makeText(requireContext(), "名称不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val ok = RecordManager.renameFolder(folder.id, name)
                if (ok) refresh() else Toast.makeText(requireContext(), "重命名失败", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showDeleteFolderConfirm(folder: Folder) {
        AlertDialog.Builder(requireContext())
            .setTitle("删除目录")
            .setMessage("确定删除「${folder.name}」及其所有棋谱？此操作不可恢复。")
            .setPositiveButton("删除") { _, _ ->
                val ok = RecordManager.deleteFolder(folder.id)
                if (ok) refresh() else Toast.makeText(requireContext(), "删除失败", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showRecordMenu(record: ChessRecord, anchor: View) {
        val menu = PopupMenu(requireContext(), anchor)
        menu.menu.add(0, 1, 0, "打开")
        menu.menu.add(0, 2, 1, "重命名")
        menu.menu.add(0, 3, 2, "移动")
        menu.menu.add(0, 4, 3, "删除")
        menu.setOnMenuItemClickListener { mi ->
            when (mi.itemId) {
                1 -> openRecord(record)
                2 -> showRenameRecordDialog(record)
                3 -> showMoveRecordDialog(record)
                4 -> showDeleteRecordConfirm(record)
            }
            true
        }
        menu.show()
    }

    private fun openRecord(record: ChessRecord) {
        val act = activity as? RecordManagerActivity
        if (act != null) {
            act.loadRecord(record.id)
        } else {
            Toast.makeText(requireContext(), "打开棋谱: ${record.title}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showRenameRecordDialog(record: ChessRecord) {
        val edit = EditText(requireContext()).apply {
            hint = "棋谱标题"
            setText(record.title)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            layoutParams = lp
            setPadding(32, 16, 32, 16)
        }
        AlertDialog.Builder(requireContext())
            .setTitle("重命名棋谱")
            .setView(edit)
            .setPositiveButton("确定") { _, _ ->
                val name = edit.text.toString().trim()
                if (name.isBlank()) {
                    Toast.makeText(requireContext(), "名称不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val file = RecordManager.loadRecord(record.id)
                if (file == null) {
                    Toast.makeText(requireContext(), "棋谱不存在", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val updated = file.copy(
                    record = file.record.copy(
                        title = name,
                        updatedAt = System.currentTimeMillis()
                    )
                )
                RecordManager.saveRecord(updated)
                refresh()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showMoveRecordDialog(record: ChessRecord) {
        val allFolders = RecordManager.listFolders()
        val options = arrayOf("根目录（全部棋谱）") + allFolders.map { it.name }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle("移动到目录")
            .setItems(options) { _, which ->
                val targetFolderId = if (which == 0) null else allFolders[which - 1].id
                if (targetFolderId == record.folderId) {
                    Toast.makeText(requireContext(), "已在该目录", Toast.LENGTH_SHORT).show()
                    return@setItems
                }
                if (targetFolderId == null) {
                    Toast.makeText(requireContext(), "暂不支持移动到根目录", Toast.LENGTH_SHORT).show()
                    return@setItems
                }
                RecordManager.moveRecord(record.id, targetFolderId)
                refresh()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showDeleteRecordConfirm(record: ChessRecord) {
        AlertDialog.Builder(requireContext())
            .setTitle("删除棋谱")
            .setMessage("确定删除「${record.title}」？此操作不可恢复。")
            .setPositiveButton("删除") { _, _ ->
                RecordManager.deleteRecord(record.id)
                refresh()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    class FavoritesAdapter(
        private val clickListener: (ListItem) -> Unit,
        private val menuClickListener: (ListItem, View) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private var items: List<ListItem> = emptyList()
        private var folderChildCount: Map<String, Int> = emptyMap()

        fun submitList(newItems: List<ListItem>, childCount: Map<String, Int>) {
            items = newItems
            folderChildCount = childCount
            notifyDataSetChanged()
        }

        override fun getItemViewType(position: Int): Int {
            return when (items[position]) {
                is ListItem.FolderItem -> VIEW_TYPE_FOLDER
                is ListItem.RecordItem -> VIEW_TYPE_RECORD
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return when (viewType) {
                VIEW_TYPE_FOLDER -> {
                    val v = inflater.inflate(R.layout.item_folder, parent, false)
                    FolderViewHolder(v)
                }
                else -> {
                    val v = inflater.inflate(R.layout.item_record, parent, false)
                    RecordViewHolder(v)
                }
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is ListItem.FolderItem -> {
                    val vh = holder as FolderViewHolder
                    vh.name.text = item.folder.name
                    val count = folderChildCount[item.folder.id] ?: 0
                    val records = RecordManager.listRecords(item.folder.id)
                    val totalSubFolders = count
                    vh.count.text = "棋谱 ${records.size} · 子目录 ${totalSubFolders}"
                    vh.itemView.setOnClickListener { clickListener(item) }
                    vh.menu.setOnClickListener { menuClickListener(item, it) }
                }
                is ListItem.RecordItem -> {
                    val vh = holder as RecordViewHolder
                    vh.title.text = item.record.title
                    val dateStr = DateFormat.format("yyyy-MM-dd HH:mm", item.record.updatedAt)
                    vh.meta.text = dateStr.toString()
                    vh.itemView.setOnClickListener { clickListener(item) }
                    vh.menu.setOnClickListener { menuClickListener(item, it) }
                }
            }
        }

        override fun getItemCount(): Int = items.size

        class FolderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val name: TextView = itemView.findViewById(R.id.itemFolderName)
            val count: TextView = itemView.findViewById(R.id.itemFolderCount)
            val menu: ImageButton = itemView.findViewById(R.id.itemFolderMenu)
        }

        class RecordViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val title: TextView = itemView.findViewById(R.id.itemRecordTitle)
            val meta: TextView = itemView.findViewById(R.id.itemRecordMeta)
            val menu: ImageButton = itemView.findViewById(R.id.itemRecordMenu)
        }

        companion object {
            private const val VIEW_TYPE_FOLDER = 0
            private const val VIEW_TYPE_RECORD = 1
        }
    }

    companion object {
        private const val TAG = "FavoritesFragment"
    }
}
