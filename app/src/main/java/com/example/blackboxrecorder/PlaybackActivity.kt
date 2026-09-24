package com.example.blackboxrecorder

import android.app.Activity
import android.content.ContentValues
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
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
    private var isExporting = false

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

        val btnBack = findViewById<ImageButton>(R.id.btnBack)
        val btnExport = findViewById<Button>(R.id.btnExport)

        btnBack.setOnClickListener { finish() }
        btnExport.setOnClickListener { exportMergedAudio() }

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

    private fun exportMergedAudio() {
        if (isExporting) return
        isExporting = true
        Toast.makeText(this, "파일을 병합 중이야. 잠시만 기다려...", Toast.LENGTH_LONG).show()

        Thread {
            try {
                val dir = File(getExternalFilesDir(null), "records")
                val files = dir.listFiles()?.filter { it.name.startsWith("REC_") }?.sortedBy { it.lastModified() } ?: return@Thread

                // 임시 병합 파일 생성
                val tempMergedFile = File(cacheDir, "Temp_Merged.wav")
                val fos = FileOutputStream(tempMergedFile)
                
                // 44바이트 헤더 공간 비워두기
                fos.write(ByteArray(44))

                var totalAudioLen = 0L
                val buffer = ByteArray(1024 * 64) // 64KB 버퍼로 고속 복사

                for (file in files) {
                    val fis = FileInputStream(file)
                    fis.skip(44) // 원본 파일들의 44바이트 헤더를 건너뜀
                    var read: Int
                    while (fis.read(buffer).also { read = it } != -1) {
                        fos.write(buffer, 0, read)
                        totalAudioLen += read
                    }
                    fis.close()
                }
                fos.close()

                // 완성된 전체 길이를 바탕으로 맨 앞에 통합 WAV 헤더 덮어쓰기
                updateWavHeader(tempMergedFile, totalAudioLen)

                // 휴대폰의 '다운로드' 폴더로 복사
                saveToPublicDownloads(tempMergedFile)
                
                tempMergedFile.delete() // 임시 파일 삭제

                runOnUiThread {
                    Toast.makeText(this, "다운로드(Downloads) 폴더에 저장 완료!", Toast.LENGTH_LONG).show()
                    isExporting = false
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(this, "병합 실패: ${e.message}", Toast.LENGTH_SHORT).show()
                    isExporting = false
                }
            }
        }.start()
    }

    private fun saveToPublicDownloads(sourceFile: File) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())
        val finalFileName = "Blackbox_Merged_$timestamp.wav"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, finalFileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            uri?.let {
                contentResolver.openOutputStream(it).use { outStream ->
                    FileInputStream(sourceFile).use { inStream ->
                        inStream.copyTo(outStream!!)
                    }
                }
            }
        } else {
            // 안드로이드 9(API 28) 이하를 위한 직접 파일 복사 방식
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!downloadsDir.exists()) downloadsDir.mkdirs()
            val destFile = File(downloadsDir, finalFileName)
            
            FileInputStream(sourceFile).use { inStream ->
                FileOutputStream(destFile).use { outStream ->
                    inStream.copyTo(outStream)
                }
            }
        }
    }

    private fun updateWavHeader(file: File, totalAudioLen: Long) {
        val sampleRate = 44100
        val channels = 1
        val byteRate = 16 * sampleRate * channels / 8.toLong()
        val totalDataLen = totalAudioLen + 36

        val header = ByteArray(44)
        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte(); header[5] = ((totalDataLen shr 8) and 0xff).toByte()
        header[6] = ((totalDataLen shr 16) and 0xff).toByte(); header[7] = ((totalDataLen shr 24) and 0xff).toByte()
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0
        header[20] = 1; header[21] = 0; header[22] = channels.toByte(); header[23] = 0
        header[24] = (sampleRate.toLong() and 0xff).toByte(); header[25] = ((sampleRate.toLong() shr 8) and 0xff).toByte()
        header[26] = ((sampleRate.toLong() shr 16) and 0xff).toByte(); header[27] = ((sampleRate.toLong() shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte(); header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte(); header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = (channels * 16 / 8).toByte(); header[33] = 0; header[34] = 16; header[35] = 0
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
        header[40] = (totalAudioLen and 0xff).toByte(); header[41] = ((totalAudioLen shr 8) and 0xff).toByte()
        header[42] = ((totalAudioLen shr 16) and 0xff).toByte(); header[43] = ((totalAudioLen shr 24) and 0xff).toByte()

        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(0)
            raf.write(header)
        }
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