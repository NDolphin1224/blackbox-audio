package com.example.blackboxrecorder

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import android.widget.Toast
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
    
    private lateinit var tvStartTime: TextView
    private lateinit var tvCurrentRealTime: TextView
    private lateinit var tvEndTime: TextView

    private var recordStartTimeMs: Long = 0
    
    private val updateHandler = Handler(Looper.getMainLooper())
    private val updateRunnable = object : Runnable {
        override fun run() {
            updateRealTimeText()
            updateHandler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_playback)

        playerView = findViewById(R.id.playerView)
        tvStartTime = findViewById(R.id.tvStartTime)
        tvCurrentRealTime = findViewById(R.id.tvCurrentRealTime)
        tvEndTime = findViewById(R.id.tvEndTime)

        initializePlayer()
    }

    private fun initializePlayer() {
        val dir = File(getExternalFilesDir(null), "records")
        val files = dir.listFiles()?.filter { it.name.startsWith("REC_") }?.sortedBy { it.lastModified() }

        if (files.isNullOrEmpty()) {
            Toast.makeText(this, "저장된 녹음 파일이 없어.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

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
                if (playbackState == Player.STATE_READY) {
                    val totalDurationMs = player?.duration ?: 0L
                    val endTimeMs = recordStartTimeMs + totalDurationMs
                    tvEndTime.text = "종료 " + timeFormat.format(Date(endTimeMs))
                }
            }
        })

        player?.prepare()
        updateHandler.post(updateRunnable)
    }

    private fun updateRealTimeText() {
        player?.let {
            val currentPosMs = it.currentPosition
            val currentRealTimeMs = recordStartTimeMs + currentPosMs
            val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            tvCurrentRealTime.text = timeFormat.format(Date(currentRealTimeMs))
        }
    }

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
        updateHandler.removeCallbacks(updateRunnable)
        player?.release()
        player = null
    }
}