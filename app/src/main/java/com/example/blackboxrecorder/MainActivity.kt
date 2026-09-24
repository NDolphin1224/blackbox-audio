package com.example.blackboxrecorder

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView

class MainActivity : Activity() {

    private var isServiceRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        checkAndRequestPermissions()

        val btnRecord = findViewById<Button>(R.id.btnRecord)
        val statusText = findViewById<TextView>(R.id.statusText)

        btnRecord.setOnClickListener {
            val intent = Intent(this, RecordService::class.java)
            
            if (!isServiceRunning) {
                // 백그라운드 서비스 시작 명령
                intent.action = "START"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
                btnRecord.text = "녹음 중지"
                statusText.text = "24시간 녹음 가동 중..."
                isServiceRunning = true
            } else {
                // 백그라운드 서비스 종료 명령
                intent.action = "STOP"
                startService(intent)
                btnRecord.text = "24시간 녹음 시작"
                statusText.text = "대기 중"
                isServiceRunning = false
            }
        }
        val btnPlay = findViewById<Button>(R.id.btnPlay)
        btnPlay.setOnClickListener {
            val playIntent = Intent(this, PlaybackActivity::class.java)
            startActivity(playIntent)
        }
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