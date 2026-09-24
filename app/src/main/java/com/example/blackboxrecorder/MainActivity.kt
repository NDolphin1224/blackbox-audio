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
        val statusText = findViewById<TextView>(R.id.statusText)
        val btnPlay = findViewById<Button>(R.id.btnPlay)
        val btnSettings = findViewById<Button>(R.id.btnSettings)

        // 현재 설정된 시간 표시
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

        btnPlay.setOnClickListener {
            val playIntent = Intent(this, PlaybackActivity::class.java)
            startActivity(playIntent)
        }

        btnSettings.setOnClickListener {
            if (isServiceRunning) {
                Toast.makeText(this, "녹음 중에는 설정을 변경할 수 없어.", Toast.LENGTH_SHORT).show()
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
        val files = dir.listFiles()?.filter { it.name.startsWith("REC_") }

        if (!files.isNullOrEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("녹음 초기화")
                .setMessage("새로 녹음을 시작하면 기존 녹음본이 모두 삭제됩니다. 계속할까?")
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
        // 안드로이드 9(API 28) 이하일 경우 파일 쓰기 권한 추가
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