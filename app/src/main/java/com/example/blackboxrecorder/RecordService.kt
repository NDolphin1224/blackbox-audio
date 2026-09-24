package com.example.blackboxrecorder

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecordService : Service() {
    private var isRecording = false
    private var audioRecord: AudioRecord? = null
    
    private val sampleRate = 44100
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
    private val chunkDurationMs = 10 * 60 * 1000L
    private var maxFilesCount = 18

    // 직전 파일과 현재 파일을 추적
    private var currentFile: File? = null
    private var previousFile: File? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "START") {
            val prefs = getSharedPreferences("BlackboxPrefs", Context.MODE_PRIVATE)
            val maxHours = prefs.getInt("max_hours", 3)
            maxFilesCount = maxHours * 6

            startForegroundService()
            startRecording()
        } else if (intent?.action == "STOP") {
            stopRecording()
        } else if (intent?.action == "BOOKMARK") {
            saveBookmark()
        }
        return START_STICKY
    }

    private fun startForegroundService() {
        val channelId = "record_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "녹음 서비스", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Blackbox Recorder")
            .setContentText("24시간 분할 녹음이 진행 중입니다.")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
            
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, notification)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        if (isRecording) return
        isRecording = true
        audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, channelConfig, audioFormat, bufferSize)
        audioRecord?.startRecording()
        Thread { writeAudioData() }.start()
    }

    private fun writeAudioData() {
        val data = ByteArray(bufferSize)
        var fos: FileOutputStream? = null
        var startTime = 0L
        var totalAudioLen = 0L

        try {
            while (isRecording) {
                val currentTime = System.currentTimeMillis()
                
                if (fos == null || currentTime - startTime >= chunkDurationMs) {
                    fos?.close()
                    currentFile?.let { updateWavHeader(it, totalAudioLen) }
                    
                    // 파일이 넘어가면 현재 파일을 이전 파일로 밀어냄
                    previousFile = currentFile

                    manageStorage()

                    currentFile = createNewFile()
                    fos = FileOutputStream(currentFile)
                    startTime = currentTime
                    totalAudioLen = 0L
                    
                    fos.write(ByteArray(44), 0, 44)
                }

                val read = audioRecord?.read(data, 0, bufferSize) ?: 0
                if (read > 0) {
                    fos.write(data, 0, read)
                    totalAudioLen += read
                }
            }
            fos?.close()
            currentFile?.let { updateWavHeader(it, totalAudioLen) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // 파일 이름만 변경하여 영구 보관 (스트림 중단 없음)
    private fun saveBookmark() {
        var count = 0
        
        previousFile?.let {
            if (it.exists() && it.name.startsWith("REC_")) {
                val newFile = File(it.parent, it.name.replace("REC_", "EVENT_"))
                if (it.renameTo(newFile)) {
                    previousFile = newFile
                    count++
                }
            }
        }
        
        currentFile?.let {
            if (it.exists() && it.name.startsWith("REC_")) {
                val newFile = File(it.parent, it.name.replace("REC_", "EVENT_"))
                if (it.renameTo(newFile)) {
                    currentFile = newFile
                    count++
                }
            }
        }
        
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, "중요 순간 ${count}개 파일이 영구 보관 처리되었습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun createNewFile(): File {
        val dir = File(getExternalFilesDir(null), "records")
        if (!dir.exists()) dir.mkdirs()
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        return File(dir, "REC_$timestamp.wav")
    }

    private fun manageStorage() {
        val dir = File(getExternalFilesDir(null), "records")
        // EVENT_ 파일은 절대 지워지지 않도록 삭제 리스트에서 제외함 (핵심 로직)
        val files = dir.listFiles()?.filter { it.name.startsWith("REC_") }?.sortedBy { it.lastModified() } ?: return
        
        if (files.size >= maxFilesCount) {
            files.first().delete()
        }
    }

    private fun stopRecording() {
        isRecording = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun updateWavHeader(file: File, totalAudioLen: Long) {
        val totalDataLen = totalAudioLen + 36
        val longSampleRate = sampleRate.toLong()
        val channels = 1
        val byteRate = 16 * sampleRate * channels / 8.toLong()
        val header = ByteArray(44)
        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte(); header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte(); header[5] = ((totalDataLen shr 8) and 0xff).toByte(); header[6] = ((totalDataLen shr 16) and 0xff).toByte(); header[7] = ((totalDataLen shr 24) and 0xff).toByte()
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte(); header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte(); header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0
        header[20] = 1; header[21] = 0; header[22] = channels.toByte(); header[23] = 0
        header[24] = (longSampleRate and 0xff).toByte(); header[25] = ((longSampleRate shr 8) and 0xff).toByte(); header[26] = ((longSampleRate shr 16) and 0xff).toByte(); header[27] = ((longSampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte(); header[29] = ((byteRate shr 8) and 0xff).toByte(); header[30] = ((byteRate shr 16) and 0xff).toByte(); header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = (channels * 16 / 8).toByte(); header[33] = 0; header[34] = 16; header[35] = 0
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte(); header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
        header[40] = (totalAudioLen and 0xff).toByte(); header[41] = ((totalAudioLen shr 8) and 0xff).toByte(); header[42] = ((totalAudioLen shr 16) and 0xff).toByte(); header[43] = ((totalAudioLen shr 24) and 0xff).toByte()
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(0)
            raf.write(header)
        }
    }
}