package com.qindachess.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.qindachess.R
import com.qindachess.ui.theme.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.io.File

/**
 * 界面设置（多 Tab）：
 *   Tab 1 全局风格 —— 预置主题 + 自定义背景图 + 四个区域透明度
 *   Tab 2 棋盘     —— 棋盘皮肤（配色方案）
 *   Tab 3 棋子     —— 棋子样式（3D浮雕/传统/简化等）
 */
class AppearanceActivity : AppCompatActivity() {

    private lateinit var tm: ThemeManager

    // Tab 文字 + 页容器
    private lateinit var tabStyle: TextView
    private lateinit var tabBoard: TextView
    private lateinit var tabPiece: TextView
    private lateinit var pageStyle: View
    private lateinit var pageBoard: View
    private lateinit var pagePiece: View

    // Tab 1
    private lateinit var gridThemes: GridView
    private lateinit var textBgStatus: TextView
    private lateinit var slideTopBar: SeekBar
    private lateinit var slideBoard: SeekBar
    private lateinit var slidePanel: SeekBar
    private lateinit var slideBottom: SeekBar
    private lateinit var lblTopBar: TextView
    private lateinit var lblBoard: TextView
    private lateinit var lblPanel: TextView
    private lateinit var lblBottom: TextView

    // Tab 2
    private lateinit var gridSkins: GridView

    // Tab 3
    private lateinit var gridPieces: GridView

    private val pickBg = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        val dest = File(filesDir, "custom_bg_${System.currentTimeMillis()}.png")
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            tm.setCustomBackground(dest.absolutePath)
            Toast.makeText(this, "背景图已设置", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "背景图设置失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_appearance)
        } catch (e: Exception) {
            Toast.makeText(this, "布局加载失败: ${e.message}", Toast.LENGTH_LONG).show()
            finish(); return
        }
        try {
            tm = ThemeManager.getInstance(this)
        } catch (e: Exception) {
            Toast.makeText(this, "ThemeManager 初始化失败: ${e.message}", Toast.LENGTH_LONG).show()
            finish(); return
        }

        try {
            tabStyle = findViewById(R.id.tabStyle)
            tabBoard = findViewById(R.id.tabBoard)
            tabPiece = findViewById(R.id.tabPiece)
            pageStyle = findViewById(R.id.pageStyle)
            pageBoard = findViewById(R.id.pageBoard)
            pagePiece = findViewById(R.id.pagePiece)

            gridThemes = findViewById(R.id.gridThemes)
            gridSkins = findViewById(R.id.gridSkins)
            gridPieces = findViewById(R.id.gridPieces)

            textBgStatus = findViewById(R.id.textBgStatus)
            slideTopBar = findViewById(R.id.slideTopBar)
            slideBoard = findViewById(R.id.slideBoard)
            slidePanel = findViewById(R.id.slidePanel)
            slideBottom = findViewById(R.id.slideBottom)
            lblTopBar = findViewById(R.id.lblTopBar)
            lblBoard = findViewById(R.id.lblBoard)
            lblPanel = findViewById(R.id.lblPanel)
            lblBottom = findViewById(R.id.lblBottom)

            setupTabSwitcher()
            setupGrids()
            setupSliders()

            findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }
            findViewById<Button>(R.id.btnReset).setOnClickListener {
                tm.resetToDefaults()
                Toast.makeText(this, "已恢复默认主题", Toast.LENGTH_SHORT).show()
            }
            findViewById<Button>(R.id.btnPickBg).setOnClickListener { pickBg.launch("image/*") }
            findViewById<Button>(R.id.btnClearBg).setOnClickListener {
                tm.clearCustomBackground()
                Toast.makeText(this, "已清除背景图", Toast.LENGTH_SHORT).show()
            }

            lifecycleScope.launch {
                tm.config.collect { cfg ->
                    runOnUiThread {
                        try { refreshAll(cfg) } catch (e: Exception) {
                            android.util.Log.e("Appearance", "refreshAll 崩了: ${e.message}", e)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("Appearance", "onCreate 崩了: ${e.message}", e)
            Toast.makeText(this, "界面设置加载失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ─────────────────── Tab 切换 ───────────────────

    private fun setupTabSwitcher() {
        val highlight = { active: TextView, other1: TextView, other2: TextView ->
            active.setTextColor(Color.parseColor("#1976D2"))
            active.textSize = 15f
            active.setTypeface(active.typeface, android.graphics.Typeface.BOLD)
            other1.setTextColor(Color.parseColor("#757575"))
            other1.textSize = 15f
            other1.setTypeface(other1.typeface, android.graphics.Typeface.NORMAL)
            other2.setTextColor(Color.parseColor("#757575"))
            other2.textSize = 15f
            other2.setTypeface(other2.typeface, android.graphics.Typeface.NORMAL)
        }
        tabStyle.setOnClickListener {
            highlight(tabStyle, tabBoard, tabPiece)
            pageStyle.visibility = View.VISIBLE; pageBoard.visibility = View.GONE; pagePiece.visibility = View.GONE
        }
        tabBoard.setOnClickListener {
            highlight(tabBoard, tabStyle, tabPiece)
            pageBoard.visibility = View.VISIBLE; pageStyle.visibility = View.GONE; pagePiece.visibility = View.GONE
        }
        tabPiece.setOnClickListener {
            highlight(tabPiece, tabStyle, tabBoard)
            pagePiece.visibility = View.VISIBLE; pageStyle.visibility = View.GONE; pageBoard.visibility = View.GONE
        }
    }

    // ─────────────────── Grid adapters ───────────────────

    private fun setupGrids() {
        gridThemes.adapter = ThemeCardAdapter(AppThemes.ALL) { tm.setTheme(it) }
        gridSkins.adapter = SkinCardAdapter(BoardSkins.ALL) { tm.setSkin(it) }
        gridPieces.adapter = PieceCardAdapter(PieceStyles.ALL) { tm.setPieceStyle(it) }
    }

    private inner class ThemeCardAdapter(
        private val themes: List<AppTheme>,
        private val onPick: (String) -> Unit
    ) : BaseAdapter() {
        override fun getCount() = themes.size
        override fun getItem(i: Int) = themes[i]
        override fun getItemId(i: Int) = i.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.item_theme_card, parent, false)
            val t = themes[position]
            v.findViewById<TextView>(R.id.cardName).text = t.name
            v.findViewById<View>(R.id.cardBgPreview).setBackgroundColor(Color.parseColor(t.backgroundColor))
            v.findViewById<View>(R.id.cardColor1).setBackgroundColor(Color.parseColor(t.primaryColor))
            v.findViewById<View>(R.id.cardColor2).setBackgroundColor(Color.parseColor(t.accentColor))
            v.setOnClickListener { onPick(t.id) }
            return v
        }
    }

    private inner class SkinCardAdapter(
        private val skins: List<BoardSkin>,
        private val onPick: (String) -> Unit
    ) : BaseAdapter() {
        override fun getCount() = skins.size
        override fun getItem(i: Int) = skins[i]
        override fun getItemId(i: Int) = i.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.item_theme_card, parent, false)
            val s = skins[position]
            v.findViewById<TextView>(R.id.cardName).text = s.name
            v.findViewById<View>(R.id.cardBgPreview).setBackgroundColor(Color.parseColor(s.boardBg))
            v.findViewById<View>(R.id.cardColor1).setBackgroundColor(Color.parseColor(s.redPiece))
            v.findViewById<View>(R.id.cardColor2).setBackgroundColor(Color.parseColor(s.blackPiece))
            v.setOnClickListener { onPick(s.id) }
            return v
        }
    }

    private inner class PieceCardAdapter(
        private val pieces: List<PieceStyle>,
        private val onPick: (String) -> Unit
    ) : BaseAdapter() {
        override fun getCount() = pieces.size
        override fun getItem(i: Int) = pieces[i]
        override fun getItemId(i: Int) = i.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.item_piece_card, parent, false)
            val p = pieces[position]
            v.findViewById<TextView>(R.id.pieceName).text = p.name
            val preview = v.findViewById<TextView>(R.id.piecePreview)
            val drawable = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (p.embossed) 0xFFFFF9E6.toInt() else Color.WHITE)
                if (p.showBorder) setStroke(p.borderWidth.toInt(), Color.parseColor("#8B0000"))
            }
            preview.background = drawable
            if (p.showCharacter) {
                val redChar = if (p.useTraditional) "帥" else "帅"
                preview.text = redChar
                preview.setTextColor(Color.parseColor("#C62828"))
            } else {
                preview.text = "⚫"
                preview.setTextColor(Color.parseColor("#2C1810"))
            }
            v.setOnClickListener { onPick(p.id) }
            return v
        }
    }

    // ─────────────────── Sliders ───────────────────

    private fun setupSliders() {
        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val a = progress / 100f
                when (sb?.id) {
                    R.id.slideTopBar -> tm.setTopBarAlpha(a)
                    R.id.slideBoard  -> tm.setBoardAlpha(a)
                    R.id.slidePanel  -> tm.setPanelAlpha(a)
                    R.id.slideBottom -> tm.setBottomBarAlpha(a)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        }
        slideTopBar.setOnSeekBarChangeListener(listener)
        slideBoard.setOnSeekBarChangeListener(listener)
        slidePanel.setOnSeekBarChangeListener(listener)
        slideBottom.setOnSeekBarChangeListener(listener)
    }

    // ─────────────────── 刷新 ───────────────────

    private fun refreshAll(cfg: ThemeConfig) {
        // 滑杆（只读，不触发 listener 的 fromUser 回调，因为我们调用 setProgress）
        slideTopBar.progress = (cfg.topBarAlpha * 100).toInt()
        slideBoard.progress  = (cfg.boardAlpha  * 100).toInt()
        slidePanel.progress  = (cfg.panelAlpha  * 100).toInt()
        slideBottom.progress = (cfg.bottomBarAlpha * 100).toInt()
        lblTopBar.text = "${(cfg.topBarAlpha * 100).toInt()}%"
        lblBoard.text  = "${(cfg.boardAlpha  * 100).toInt()}%"
        lblPanel.text  = "${(cfg.panelAlpha  * 100).toInt()}%"
        lblBottom.text = "${(cfg.bottomBarAlpha * 100).toInt()}%"

        // 背景图状态
        textBgStatus.text = if (cfg.customBackgroundPath.isNotBlank()) {
            val f = File(cfg.customBackgroundPath)
            if (f.exists()) "已设置 · ${f.name} · ${f.length() / 1024} KB" else "文件已丢失"
        } else "未设置，使用主题默认"

        // 卡片高亮边框
        highlightSelected(gridThemes, AppThemes.ALL.map { it.id }, cfg.themeId)
        highlightSelected(gridSkins, BoardSkins.ALL.map { it.id }, cfg.boardSkinId)
        highlightSelected(gridPieces, PieceStyles.ALL.map { it.id }, cfg.pieceStyleId)
    }

    private fun highlightSelected(grid: GridView, ids: List<String>, selectedId: String) {
        for (i in 0 until grid.childCount) {
            val child = grid.getChildAt(i)
            val id = ids.getOrNull(i) ?: continue
            val isSel = id == selectedId
            val drawable = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.WHITE)
                setStroke(
                    if (isSel) 3 else 1,
                    if (isSel) 0xFF1976D2.toInt() else 0xFFCCCCCC.toInt()
                )
                cornerRadius = 6f * resources.displayMetrics.density
            }
            child.background = drawable
            child.setPadding(6, 6, 6, 6)
        }
    }
}
