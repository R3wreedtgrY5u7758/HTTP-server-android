package com.example.httpserver2

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.httpserver2.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private var server: HttpServer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        loadSettings()

        binding.btnStart.setOnClickListener { toggle() }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.btnClear.setOnClickListener { binding.tvLog.text = "" }
        binding.btnOpen.setOnClickListener {
            val port = prefs.getInt(SettingsActivity.KEY_PORT, 8080)
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://127.0.0.1:$port")))
        }
    }

    override fun onResume() { super.onResume(); loadSettings(); if (server?.isRunning() != true) updateUi() }
    override fun onDestroy() { super.onDestroy(); server?.stop() }

    private fun loadSettings() {
        val port = prefs.getInt(SettingsActivity.KEY_PORT, 8080)
        val mode = prefs.getString(SettingsActivity.KEY_MODE, "DIRECTORY") ?: "DIRECTORY"
        binding.tvInfo.text = "Port: $port   Mode: $mode"
    }

    private fun toggle() {
        if (server?.isRunning() == true) { server?.stop(); server = null; updateUi(); return }
        startServer()
    }

    private fun startServer() {
        val port = prefs.getInt(SettingsActivity.KEY_PORT, 8080)
        val modeStr = prefs.getString(SettingsActivity.KEY_MODE, "DIRECTORY") ?: "DIRECTORY"
        val mode = HttpServer.Mode.valueOf(modeStr)
        val html = prefs.getString(SettingsActivity.KEY_HTML, DEFAULT_HTML) ?: DEFAULT_HTML
        val allowUpload = prefs.getBoolean(SettingsActivity.KEY_UPLOAD, true)
        val allowModify = prefs.getBoolean(SettingsActivity.KEY_MODIFY, true)
        val dirUriStr = prefs.getString(SettingsActivity.KEY_DIR_URI, null)

        if (mode == HttpServer.Mode.DIRECTORY && dirUriStr == null) {
            Toast.makeText(this, "Choose a folder first", Toast.LENGTH_SHORT).show()
            return
        }

        val rootUri = dirUriStr?.let { Uri.parse(it) }

        binding.tvLog.text = ""
        server = HttpServer(
            context = applicationContext,
            port = port,
            mode = mode,
            htmlContent = html,
            rootUri = rootUri,
            allowUpload = allowUpload,
            allowModify = allowModify,
            onLog = { msg ->
                runOnUiThread {
                    binding.tvLog.append("$msg\n")
                    binding.scrollLog.post {
                        binding.scrollLog.fullScroll(android.view.View.FOCUS_DOWN)
                    }
                }
            }
        )
        server?.start()
        updateUi()
    }

    private fun updateUi() {
        val running = server?.isRunning() == true
        binding.btnStart.text = if (running) "STOP" else "START"
        binding.btnOpen.isEnabled = running
        binding.tvStatus.text = if (running) "🟢 Running" else "🔴 Stopped"
        binding.tvUrl.text = if (running) {
            val port = prefs.getInt(SettingsActivity.KEY_PORT, 8080)
            "http://${HttpServer.localIp()}:$port"
        } else ""
    }

    companion object {
        const val DEFAULT_HTML = """<!DOCTYPE html>
<html><head><meta charset="utf-8"><title>Hello</title>
<style>body{font-family:sans-serif;text-align:center;padding:60px;background:#0f1115;color:#e6e6e6}
h1{color:#3ea6ff}</style></head><body>
<h1>Server is running</h1>
<p>Edit this HTML in the app settings.</p>
</body></html>"""
    }
}
