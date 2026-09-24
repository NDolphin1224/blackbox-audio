package com.example.blackboxrecorder

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import java.io.File

class MainActivity : Activity() {

    private var isServiceRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        checkAndRequestPermissions()

        val btnRecord = findViewById<Button>(R.id.btnRecord)
        val btnBookmark = findViewById<Button>(R.id.btnBookmark)
        val statusText = findViewById<TextView>(R.id.statusText)
        val btnPlay = findViewById<Button>(R.id.btnPlay)
        val btnSettings = findViewById<Button>(R.id.btnSettings)

        updateStatusText(statusText)

        btnRecord.setOnClickListener {
            if (!isServiceRunning) {
                checkOldRecordsAndStart(btnRecord, statusText)
            } else {
                val intent = Intent(this, RecordService::class.java)
                intent.action = "STOP"
                startService(intent)
                btnRecord.text = "24시간 녹음 시작"
                updateStatusText(statusText)
                isServiceRunning = false
            }
        }

        // 보관 버튼 로직
        btnBookmark.setOnClickListener {
            if (isServiceRunning) {
                val intent = Intent(this, RecordService::class.java)
                intent.action = "BOOKMARK"
                startService(intent)
            } else {
                Toast.makeText(this, "녹음이 켜져 있을 때만 사용할 수 있습니다.", Toast.LENGTH_SHORT).show()
            }
        }

        btnPlay.setOnClickListener {
            val playIntent = Intent(this, PlaybackActivity::class.java)
            startActivity(playIntent)
        }

        btnSettings.setOnClickListener {
            if (isServiceRunning) {
                Toast.makeText(this, "녹음 중에는 설정을 변경할 수 없습니다.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            showSettingsDialog(statusText)
        }
    }

    private fun showSettingsDialog(statusText: TextView) {
        val options = arrayOf("1시간", "3시간", "6시간", "12시간", "24시간")
        val hours = intArrayOf(1, 3, 6, 12, 24)
        
        val prefs = getSharedPreferences("BlackboxPrefs", Context.MODE_PRIVATE)
        val currentHour = prefs.getInt("max_hours", 3)
        val checkedItem = hours.indexOf(currentHour).takeIf { it >= 0 } ?: 1

        AlertDialog.Builder(this)
            .setTitle("최대 녹음 시간 설정")
            .setSingleChoiceItems(options, checkedItem) { dialog, which ->
                prefs.edit().putInt("max_hours", hours[which]).apply()
                updateStatusText(statusText)
                dialog.dismiss()
            }
            .show()
    }

    private fun updateStatusText(statusText: TextView) {
        if (!isServiceRunning) {
            val prefs = getSharedPreferences("BlackboxPrefs", Context.MODE_PRIVATE)
            val currentHour = prefs.getInt("max_hours", 3)
            statusText.text = "대기 중 (설정: 최대 ${currentHour}시간 보존)"
        }
    }

    private fun checkOldRecordsAndStart(btnRecord: Button, statusText: TextView) {
        val dir = File(getExternalFilesDir(null), "records")
        // REC_와 EVENT_ 파일을 모두 스캔
        val files = dir.listFiles()?.filter { it.name.startsWith("REC_") || it.name.startsWith("EVENT_") }

        if (!files.isNullOrEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("녹음 초기화")
                .setMessage("새로 녹음을 시작하면 기존 녹음본(영구 보관 포함)이 모두 삭제됩니다. 계속하시겠습니까?")
                .setPositiveButton("확인") { _, _ ->
                    files.forEach { it.delete() }
                    startRecordingService(btnRecord, statusText)
                }
                .setNegativeButton("취소", null)
                .show()
        } else {
            startRecordingService(btnRecord, statusText)
        }
    }

    private fun startRecordingService(btnRecord: Button, statusText: TextView) {
        val intent = Intent(this, RecordService::class.java)
        intent.action = "START"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        btnRecord.text = "녹음 중지"
        statusText.text = "24시간 녹음 가동 중..."
        isServiceRunning = true
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        val missingPermissions = permissions.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missingPermissions.isNotEmpty()) {
            requestPermissions(missingPermissions.toTypedArray(), 100)
        }
    }
}