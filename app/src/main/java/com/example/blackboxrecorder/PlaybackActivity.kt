package com.example.blackboxrecorder

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import java.io.File

class PlaybackActivity : Activity() {
    private var player: ExoPlayer? = null
    private lateinit var btnPlayPause: Button
    private lateinit var tvFileInfo: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_playback)

        btnPlayPause = findViewById(R.id.btnPlayPause)
        tvFileInfo = findViewById(R.id.tvFileInfo)

        initializePlayer()
    }

    private fun initializePlayer() {
        // 프라이빗 폴더에 저장된 녹음 파일들 불러오기
        val dir = File(getExternalFilesDir(null), "records")
        val files = dir.listFiles()?.sortedBy { it.lastModified() }

        if (files.isNullOrEmpty()) {
            tvFileInfo.text = "저장된 녹음 파일이 없어."
            btnPlayPause.isEnabled = false
            return
        }

        tvFileInfo.text = "총 ${files.size}개의 녹음 블록(10분 단위)이 연결됨"

        // ExoPlayer 생성
        player = ExoPlayer.Builder(this).build()

        // 10분 단위 파일들을 하나의 플레이리스트로 묶기 (Gapless 재생)
        files.forEach { file ->
            val mediaItem = MediaItem.fromUri(file.toURI().toString())
            player?.addMediaItem(mediaItem)
        }

        player?.prepare()

        btnPlayPause.setOnClickListener {
            if (player?.isPlaying == true) {
                player?.pause()
                btnPlayPause.text = "재생"
            } else {
                player?.play()
                btnPlayPause.text = "일시정지"
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }
}