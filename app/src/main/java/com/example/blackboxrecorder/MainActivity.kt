package com.example.blackboxrecorder

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
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

        btnRecord.setOnClickListener {
            if (!isServiceRunning) {
                // 새 녹음 시작 전 파일 체크
                checkOldRecordsAndStart(btnRecord, statusText)
            } else {
                // 녹음 중지
                val intent = Intent(this, RecordService::class.java)
                intent.action = "STOP"
                startService(intent)
                btnRecord.text = "24시간 녹음 시작"
                statusText.text = "대기 중"
                isServiceRunning = false
            }
        }

        btnPlay.setOnClickListener {
            val playIntent = Intent(this, PlaybackActivity::class.java)
            startActivity(playIntent)
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
                    // 기존 파일 모두 삭제
                    files.forEach { it.delete() }
                    startRecordingService(btnRecord, statusText)
                }
                .setNegativeButton("취소", null)
                .show()
        } else {
            // 기존 파일이 없으면 바로 시작
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

        val missingPermissions = permissions.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            requestPermissions(missingPermissions.toTypedArray(), 100)
        }
    }
}