package com.qindachess.ui

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.qindachess.R
import com.qindachess.board.ChessBoard
import com.qindachess.record.ChessRecord
import com.qindachess.record.Folder
import com.qindachess.record.RecordFile
import com.qindachess.record.RecordManager

class RecordManagerActivity : AppCompatActivity() {

    companion object {
        const val TAB_NOTATION = 0
        const val TAB_FAVORITES = 1
    }

    private lateinit var tabNotation: TextView
    private lateinit var tabFavorites: TextView
    private lateinit var recordBack: ImageButton
    private lateinit var recordNewAction: ImageButton

    private var currentTab = TAB_NOTATION
    private var notationFragment: NotationFragment? = null
    private var favoritesFragment: FavoritesFragment? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_record_manager)

        RecordManager.init(this)

        bindViews()
        setupTabListeners()
        setupToolbarListeners()

        if (savedInstanceState != null) {
            currentTab = savedInstanceState.getInt("currentTab", TAB_NOTATION)
            restoreFragments()
        } else {
            showNotationFragment()
        }
        updateTabUI(currentTab)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("currentTab", currentTab)
    }

    private fun bindViews() {
        tabNotation = findViewById(R.id.tabNotation)
        tabFavorites = findViewById(R.id.tabFavorites)
        recordBack = findViewById(R.id.recordBack)
        recordNewAction = findViewById(R.id.recordNewAction)
    }

    private fun setupTabListeners() {
        tabNotation.setOnClickListener { showNotationFragment() }
        tabFavorites.setOnClickListener { showFavoritesFragment() }
    }

    private fun setupToolbarListeners() {
        recordBack.setOnClickListener { finish() }
        recordNewAction.setOnClickListener { showNewActionDialog() }
    }

    fun loadRecord(recordId: String) {
        val file = RecordManager.loadRecord(recordId)
        if (file == null) {
            Toast.makeText(this, "棋谱不存在", Toast.LENGTH_SHORT).show()
            return
        }
        val frag = notationFragment ?: NotationFragment().also { notationFragment = it }
        notationFragment = frag
        showNotationFragment()
        notationFragment?.loadRecord(file)
    }

    private fun showNotationFragment() {
        currentTab = TAB_NOTATION
        updateTabUI(TAB_NOTATION)
        val frag = notationFragment ?: NotationFragment().also { notationFragment = it }
        switchFragment(frag)
    }

    private fun showFavoritesFragment() {
        currentTab = TAB_FAVORITES
        updateTabUI(TAB_FAVORITES)
        val frag = favoritesFragment ?: FavoritesFragment().also { favoritesFragment = it }
        switchFragment(frag)
    }

    private fun restoreFragments() {
        supportFragmentManager.fragments.forEach { frag ->
            when (frag) {
                is NotationFragment -> notationFragment = frag
                is FavoritesFragment -> favoritesFragment = frag
            }
        }
        val target = if (currentTab == TAB_NOTATION) {
            notationFragment ?: NotationFragment().also { notationFragment = it }
        } else {
            favoritesFragment ?: FavoritesFragment().also { favoritesFragment = it }
        }
        switchFragment(target)
    }

    private fun switchFragment(fragment: Fragment) {
        val tx = supportFragmentManager.beginTransaction()
            .replace(R.id.recordContentContainer, fragment)
        if (supportFragmentManager.fragments.isNotEmpty()) {
            tx.addToBackStack(null)
        }
        tx.commit()
    }

    private fun updateTabUI(selectedTab: Int) {
        applyTabStyle(
            tabNotation,
            selectedTab == TAB_NOTATION,
            R.drawable.bg_tab_selected_v2
        )
        applyTabStyle(
            tabFavorites,
            selectedTab == TAB_FAVORITES,
            R.drawable.bg_tab_selected_v2
        )
    }

    private fun applyTabStyle(tab: TextView, selected: Boolean, selectedBg: Int) {
        if (selected) {
            tab.setTextColor(0xFF37474F.toInt())
            tab.textSize = 16f
            tab.setTypeface(tab.typeface, android.graphics.Typeface.BOLD)
            tab.setBackgroundResource(selectedBg)
        } else {
            tab.setTextColor(0xFF888888.toInt())
            tab.textSize = 16f
            tab.setTypeface(tab.typeface, android.graphics.Typeface.NORMAL)
            tab.setBackgroundResource(0)
        }
    }

    private fun showNewActionDialog() {
        val options = arrayOf("新建棋谱（空白）", "新建目录")
        AlertDialog.Builder(this)
            .setTitle("新建")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> createBlankRecord()
                    1 -> promptCreateFolder()
                }
            }
            .show()
    }

    private fun createBlankRecord() {
        val defaultFolder = RecordManager.getDefaultFolder()
        val record = RecordManager.createRecord(
            folderId = defaultFolder.id,
            title = "未命名棋谱",
            fen = ChessBoard.INITIAL_FEN
        )
        val recordFile = RecordManager.loadRecord(record.id) ?: run {
            Toast.makeText(this, "创建失败", Toast.LENGTH_SHORT).show()
            return
        }
        showNotationFragment()
        notationFragment?.loadRecord(recordFile)
    }

    private fun promptCreateFolder() {
        val input = EditText(this).apply {
            hint = "请输入目录名称"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                (24 * resources.displayMetrics.density).toInt(),
                (12 * resources.displayMetrics.density).toInt(),
                (24 * resources.displayMetrics.density).toInt(),
                0
            )
            addView(input)
        }

        AlertDialog.Builder(this)
            .setTitle("新建目录")
            .setView(container)
            .setPositiveButton("确定") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isBlank()) {
                    Toast.makeText(this, "名称不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                RecordManager.createFolder(name)
                Toast.makeText(this, "目录已创建", Toast.LENGTH_SHORT).show()
                if (currentTab == TAB_FAVORITES) {
                    favoritesFragment?.refresh()
                } else {
                    showFavoritesFragment()
                    favoritesFragment?.refresh()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
