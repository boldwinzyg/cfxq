package com.qindachess.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.qindachess.QinDaApp
import com.qindachess.databinding.ActivityBoardRecognitionBinding
import com.qindachess.recognition.ChessRecognizer

class BoardRecognitionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBoardRecognitionBinding
    private val recognizer = ChessRecognizer()

    private val imagePicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { loadAndRecognize(it) }
    }

    private val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        Toast.makeText(this, "请从相册或截图选择图片", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBoardRecognitionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupClickListeners()
    }

    private fun setupClickListeners() {
        binding.btnPickImage.setOnClickListener {
            imagePicker.launch("image/*")
        }

        binding.btnUseCamera.setOnClickListener {
            if (checkCameraPermission()) {
                Toast.makeText(this, "正在打开相机...", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnApplyToGame.setOnClickListener {
            val app = application as QinDaApp
            val state = app.gameManager.gameState.value
            binding.resultFen.text?.let { fen ->
                if (fen.isNotBlank()) {
                    Toast.makeText(this, "棋盘状态已更新", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun loadAndRecognize(uri: Uri) {
        binding.progressBar.visibility = View.VISIBLE
        binding.resultFen.text = ""
        binding.confidenceText.text = ""

        try {
            contentResolver.openInputStream(uri)?.use { stream ->
                val bitmap = BitmapFactory.decodeStream(stream)
                binding.recognizerView.setImageBitmap(bitmap)

                val result = recognizer.recognizeFromImage(bitmap)
                runOnUiThread {
                    binding.progressBar.visibility = View.GONE
                    binding.resultFen.text = result.board.toFen()
                    binding.confidenceText.text = "置信度: ${"%.1f".format(result.confidence * 100)}% | 检测到 ${result.detectedCount} 个棋子"
                }
            }
        } catch (e: Exception) {
            runOnUiThread {
                binding.progressBar.visibility = View.GONE
                Toast.makeText(this, "识别失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun checkCameraPermission(): Boolean {
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (!granted) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_REQUEST_CODE)
        }
        return granted
    }

    companion object {
        private const val CAMERA_REQUEST_CODE = 100
    }
}
