package com.example.blackboxrecorder

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PlaybackActivity : Activity() {
    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var tvFileInfo: TextView
    
    private lateinit var tvStartTime: TextView
    private lateinit var tvCurrentRealTime: TextView
    private lateinit var tvEndTime: TextView

    private var recordStartTimeMs: Long = 0
    
    // UI 스레드에서 1초마다 현재 시각을 업데이트하기 위한 타이머(Handler)
    private val updateHandler = Handler(Looper.getMainLooper())
    private val updateRunnable = object : Runnable {
        override fun run() {
            updateRealTimeText()
            // 0.5초마다 갱신하여 텍스트 딜레이 방지
            updateHandler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_playback)

        playerView = findViewById(R.id.playerView)
        tvFileInfo = findViewById(R.id.tvFileInfo)
        tvStartTime = findViewById(R.id.tvStartTime)
        tvCurrentRealTime = findViewById(R.id.tvCurrentRealTime)
        tvEndTime = findViewById(R.id.tvEndTime)

        initializePlayer()
    }

    private fun initializePlayer() {
        val dir = File(getExternalFilesDir(null), "records")
        // 파일명이 REC_ 로 시작하는 것만 필터링 후 시간순 정렬
        val files = dir.listFiles()?.filter { it.name.startsWith("REC_") }?.sortedBy { it.lastModified() }

        if (files.isNullOrEmpty()) {
            tvFileInfo.text = "저장된 녹음 파일이 없어."
            return
        }

        tvFileInfo.text = "총 ${files.size}개의 녹음 블록 연결 완료"

        // 첫 번째 파일명에서 녹음 시작 시각 추출
        recordStartTimeMs = parseTimeToMillis(files.first().name)
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        tvStartTime.text = "시작 " + timeFormat.format(Date(recordStartTimeMs))

        player = ExoPlayer.Builder(this).build()
        playerView.player = player

        files.forEach { file ->
            val mediaItem = MediaItem.fromUri(file.toURI().toString())
            player?.addMediaItem(mediaItem)
        }

        player?.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                // 플레이어가 재생 준비를 마치면 전체 길이(duration)를 알 수 있음
                if (playbackState == Player.STATE_READY) {
                    val totalDurationMs = player?.duration ?: 0L
                    val endTimeMs = recordStartTimeMs + totalDurationMs
                    tvEndTime.text = "종료 " + timeFormat.format(Date(endTimeMs))
                }
            }
        })

        player?.prepare()
        
        // 실시간 시간 업데이트 시작
        updateHandler.post(updateRunnable)
    }

    // 재생 바 위치(Position)를 실제 시간으로 변환해서 화면에 출력
    private fun updateRealTimeText() {
        player?.let {
            val currentPosMs = it.currentPosition
            val currentRealTimeMs = recordStartTimeMs + currentPosMs
            val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            tvCurrentRealTime.text = timeFormat.format(Date(currentRealTimeMs))
        }
    }

    // REC_yyyyMMdd_HHmmss.wav 파일명을 읽어서 밀리초(Millis) 타임스탬프로 변환
    private fun parseTimeToMillis(fileName: String): Long {
        return try {
            val timeString = fileName.replace("REC_", "").replace(".wav", "")
            val format = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
            val date = format.parse(timeString)
            date?.time ?: System.currentTimeMillis()
        } catch (e: Exception) {
            System.currentTimeMillis()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 앱이 꺼지면 타이머도 메모리에서 해제
        updateHandler.removeCallbacks(updateRunnable)
        player?.release()
        player = null
    }
}