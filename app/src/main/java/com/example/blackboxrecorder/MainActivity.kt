package com.example.blackboxrecorder

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 방금 만든 XML UI를 화면에 연결
        setContentView(R.layout.activity_main)

        checkAndRequestPermissions()

        val btnRecord = findViewById<Button>(R.id.btnRecord)
        val statusText = findViewById<TextView>(R.id.statusText)

        btnRecord.setOnClickListener {
            // TODO: 나중에 여기에 24시간 백그라운드 서비스(AudioRecord) 실행 코드를 넣을 거야
            Toast.makeText(this, "녹음 서비스 준비 중", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        
        // Android 13(API 33) 이상에서는 알림 권한 필수
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