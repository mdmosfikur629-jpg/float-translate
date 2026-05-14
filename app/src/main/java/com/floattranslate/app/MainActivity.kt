package com.floattranslate.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var btnToggle: Button
    private lateinit var tvStatus: TextView
    private var serviceRunning = false

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) checkOverlayPermission()
        else Toast.makeText(this, "Microphone permission is required!", Toast.LENGTH_LONG).show()
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(this)) startFloatingService()
        else Toast.makeText(this, "Overlay permission is required!", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        btnToggle = findViewById(R.id.btn_toggle)
        tvStatus = findViewById(R.id.tv_status)
        btnToggle.setOnClickListener {
            if (serviceRunning) stopFloatingService()
            else checkPermissionsAndStart()
        }
        updateUI()
    }

    private fun checkPermissionsAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            AlertDialog.Builder(this)
                .setTitle("Microphone Permission")
                .setMessage("This app needs microphone access to listen and translate speech.")
                .setPositiveButton("Grant") { _, _ ->
                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
                .setNegativeButton("Cancel", null).show()
        } else {
            checkOverlayPermission()
        }
    }

    private fun checkOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            AlertDialog.Builder(this)
                .setTitle("Display Over Other Apps")
                .setMessage("Float Translate needs permission to show a floating widget over other apps.")
                .setPositiveButton("Open Settings") { _, _ ->
                    overlayPermissionLauncher.launch(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName"))
                    )
                }
                .setNegativeButton("Cancel", null).show()
        } else {
            startFloatingService()
        }
    }

    private fun startFloatingService() {
        startForegroundService(Intent(this, FloatingService::class.java))
        serviceRunning = true
        updateUI()
        moveTaskToBack(true)
    }

    private fun stopFloatingService() {
        stopService(Intent(this, FloatingService::class.java))
        serviceRunning = false
        updateUI()
    }

    private fun updateUI() {
        if (serviceRunning) {
            btnToggle.text = "⏹ Stop Float Translate"
            tvStatus.text = "Status: ACTIVE — Floating widget is on screen\n(First launch: model downloads ~50MB)"
        } else {
            btnToggle.text = "▶ Start Float Translate"
            tvStatus.text = "Status: Inactive"
        }
    }
}
