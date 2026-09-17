package com.example.httpserver2

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.httpserver2.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    private val folderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            getSharedPreferences("prefs", MODE_PRIVATE).edit()
                .putString(KEY_DIR_URI, uri.toString()).apply()
            binding.tvFolder.text = "Folder: ${uri.lastPathSegment}"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.title = "Settings"

        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)

        binding.etPort.setText(prefs.getInt(KEY_PORT, 8080).toString())
        binding.etHtml.setText(prefs.getString(KEY_HTML, MainActivity.DEFAULT_HTML))
        binding.cbAllowUpload.isChecked = prefs.getBoolean(KEY_UPLOAD, true)
        binding.cbAllowDelete.isChecked = prefs.getBoolean(KEY_MODIFY, true)

        val mode = prefs.getString(KEY_MODE, "DIRECTORY")
        if (mode == "HTML") binding.rbHtml.isChecked = true else binding.rbDir.isChecked = true

        val dirUri = prefs.getString(KEY_DIR_URI, null)
        binding.tvFolder.text = if (dirUri != null)
            "Folder: ${Uri.parse(dirUri).lastPathSegment}" else "(not selected)"

        binding.btnPickFolder.setOnClickListener { folderPicker.launch(null) }

        binding.btnSave.setOnClickListener {
            val port = binding.etPort.text.toString().toIntOrNull()
            if (port == null || port !in 1024..65535) {
                Toast.makeText(this, "Port 1024..65535", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val mode = if (binding.rbHtml.isChecked) "HTML" else "DIRECTORY"
            prefs.edit()
                .putInt(KEY_PORT, port)
                .putString(KEY_MODE, mode)
                .putString(KEY_HTML, binding.etHtml.text.toString())
                .putBoolean(KEY_UPLOAD, binding.cbAllowUpload.isChecked)
                .putBoolean(KEY_MODIFY, binding.cbAllowDelete.isChecked)
                .apply()
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    companion object {
        const val KEY_PORT = "port"
        const val KEY_MODE = "mode"
        const val KEY_HTML = "html"
        const val KEY_DIR_URI = "dir_uri"
        const val KEY_UPLOAD = "upload"
        const val KEY_MODIFY = "modify"
    }
}
