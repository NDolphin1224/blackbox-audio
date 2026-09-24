package com.example.blackboxrecorder

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.io.File

class PlaybackActivity : Activity() {
    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var tvFileInfo: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_playback)

        playerView = findViewById(R.id.playerView)
        tvFileInfo = findViewById(R.id.tvFileInfo)

        initializePlayer()
    }

    private fun initializePlayer() {
        val dir = File(getExternalFilesDir(null), "records")
        val files = dir.listFiles()?.sortedBy { it.lastModified() }

        if (files.isNullOrEmpty()) {
            tvFileInfo.text = "저장된 녹음 파일이 없어."
            return
        }

        tvFileInfo.text = "총 ${files.size}개의 녹음 블록(10분 단위) 연결 완료"

        player = ExoPlayer.Builder(this).build()
        // ExoPlayer와 PlayerView(UI)를 결합
        playerView.player = player

        files.forEach { file ->
            val mediaItem = MediaItem.fromUri(file.toURI().toString())
            player?.addMediaItem(mediaItem)
        }

        player?.prepare()
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }
}