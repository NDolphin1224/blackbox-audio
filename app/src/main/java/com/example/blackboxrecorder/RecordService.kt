package com.example.blackboxrecorder

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
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
    
    // 오디오 표준 규격 (44.1kHz, Mono, 16bit)
    private val sampleRate = 44100
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
    
    // 10분 단위 저장 (밀리초 변환)
    private val chunkDurationMs = 10 * 60 * 1000L
    // 최대 3시간 (10분 x 18개)
    private val maxFilesCount = 18

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "START") {
            startForegroundService()
            startRecording()
        } else if (intent?.action == "STOP") {
            stopRecording()
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
            
        // Android 14 이상 마이크 포그라운드 권한 명시
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

        // 메인 UI가 멈추지 않도록 별도의 백그라운드 스레드에서 파일 쓰기 진행
        Thread { writeAudioData() }.start()
    }

    private fun writeAudioData() {
        val data = ByteArray(bufferSize)
        var fos: FileOutputStream? = null
        var currentFile: File? = null
        var startTime = 0L
        var totalAudioLen = 0L

        try {
            while (isRecording) {
                val currentTime = System.currentTimeMillis()
                
                // 10분이 지났거나 첫 시작일 때 새 파일 생성 로직
                if (fos == null || currentTime - startTime >= chunkDurationMs) {
                    
                    // 이전 파일 닫고 WAV 헤더 덮어쓰기 (재생 가능하게 만듦)
                    fos?.close()
                    currentFile?.let { updateWavHeader(it, totalAudioLen) }

                    // 저장소 용량/개수 관리 (오래된 것 삭제)
                    manageStorage()

                    // 새 파일 생성
                    currentFile = createNewFile()
                    fos = FileOutputStream(currentFile)
                    startTime = currentTime
                    totalAudioLen = 0L
                    
                    // 44바이트 빈 공간(WAV 헤더 자리) 먼저 확보
                    fos.write(ByteArray(44), 0, 44)
                }

                // 마이크 데이터 읽어서 파일에 쓰기
                val read = audioRecord?.read(data, 0, bufferSize) ?: 0
                if (read > 0) {
                    fos.write(data, 0, read)
                    totalAudioLen += read
                }
            }
            
            // 정지 버튼 누르면 마지막 파일 정리
            fos?.close()
            currentFile?.let { updateWavHeader(it, totalAudioLen) }
            
        } catch (e: Exception) {
            e.printStackTrace()
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
        val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
        
        // 파일 개수가 18개(3시간) 이상이면 가장 오래된(first) 파일 삭제
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

    // 파일 생성 완료 후 파일 맨 앞으로 이동해 표준 WAV 헤더 규격을 작성하는 함수
    private fun updateWavHeader(file: File, totalAudioLen: Long) {
        val totalDataLen = totalAudioLen + 36
        val longSampleRate = sampleRate.toLong()
        val channels = 1
        val byteRate = 16 * sampleRate * channels / 8.toLong()

        val header = ByteArray(44)
        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = ((totalDataLen shr 8) and 0xff).toByte()
        header[6] = ((totalDataLen shr 16) and 0xff).toByte()
        header[7] = ((totalDataLen shr 24) and 0xff).toByte()
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0
        header[20] = 1; header[21] = 0
        header[22] = channels.toByte(); header[23] = 0
        header[24] = (longSampleRate and 0xff).toByte()
        header[25] = ((longSampleRate shr 8) and 0xff).toByte()
        header[26] = ((longSampleRate shr 16) and 0xff).toByte()
        header[27] = ((longSampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = (channels * 16 / 8).toByte(); header[33] = 0
        header[34] = 16; header[35] = 0
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
        header[40] = (totalAudioLen and 0xff).toByte()
        header[41] = ((totalAudioLen shr 8) and 0xff).toByte()
        header[42] = ((totalAudioLen shr 16) and 0xff).toByte()
        header[43] = ((totalAudioLen shr 24) and 0xff).toByte()

        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(0)
            raf.write(header)
        }
    }
}