package com.example.blackboxrecorder

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileInputStream
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
            saveBookmarkInstantExport()
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
        var lastFailsafeCheck = 0L

        try {
            while (isRecording) {
                val currentTime = System.currentTimeMillis()

                if (currentTime - lastFailsafeCheck > 60000) {
                    if (isFailsafeTriggered()) {
                        isRecording = false
                        break
                    }
                    lastFailsafeCheck = currentTime
                }
                
                if (fos == null || currentTime - startTime >= chunkDurationMs) {
                    fos?.close()
                    currentFile?.let { updateWavHeader(it, totalAudioLen) }
                    
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
            
            if (!isRecording) {
                stopSelf()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // 이름 변경(영구보관) 대신, 즉시 외부 폴더로 병합해서 추출하는 로직
    private fun saveBookmarkInstantExport() {
        Toast.makeText(applicationContext, "순간 저장을 시작합니다...", Toast.LENGTH_SHORT).show()
        
        val filesToExport = listOfNotNull(previousFile, currentFile).filter { it.exists() }
        
        if (filesToExport.isEmpty()) {
            Toast.makeText(applicationContext, "저장할 녹음 데이터가 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }

        Thread {
            try {
                val tempMergedFile = File(cacheDir, "Temp_Instant.wav")
                val fos = FileOutputStream(tempMergedFile)
                fos.write(ByteArray(44))

                var totalAudioLen = 0L
                val buffer = ByteArray(1024 * 64)

                for (file in filesToExport) {
                    val fis = FileInputStream(file)
                    fis.skip(44) // 기존 파일들의 헤더 건너뛰기
                    var read: Int
                    // 파일이 실시간으로 쓰이고 있어도, 디스크에 기록된 현재 시점까지만 긁어옴
                    while (fis.read(buffer).also { read = it } != -1) {
                        fos.write(buffer, 0, read)
                        totalAudioLen += read
                    }
                    fis.close()
                }
                fos.close()

                updateWavHeader(tempMergedFile, totalAudioLen)

                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val finalFileName = "BBA_Instant_$timestamp.wav"

                saveToPublicDownloads(tempMergedFile, finalFileName)
                tempMergedFile.delete()

                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(applicationContext, "순간 저장 완료! BlackBoxAudio 폴더를 확인해 주세요.", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(applicationContext, "순간 저장에 실패했습니다.", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun saveToPublicDownloads(sourceFile: File, finalFileName: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, finalFileName)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "audio/wav")
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/BlackBoxAudio")
            }
            val uri = contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            uri?.let {
                contentResolver.openOutputStream(it).use { outStream ->
                    java.io.FileInputStream(sourceFile).use { inStream ->
                        inStream.copyTo(outStream!!)
                    }
                }
            }
        } else {
            val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
            val appDir = File(downloadsDir, "BlackBoxAudio")
            if (!appDir.exists()) appDir.mkdirs()
            
            val destFile = File(appDir, finalFileName)
            
            java.io.FileInputStream(sourceFile).use { inStream ->
                java.io.FileOutputStream(destFile).use { outStream ->
                    inStream.copyTo(outStream)
                }
            }
        }
    }

    private fun isFailsafeTriggered(): Boolean {
        val freeSpace = getExternalFilesDir(null)?.freeSpace ?: 0L
        if (freeSpace < 500 * 1024 * 1024L) {
            notifyFailsafe("저장 공간 부족(500MB 이하)으로 안전 종료되었습니다.")
            return true
        }

        val batteryStatus: Intent? = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level != -1 && scale != -1) {
            val batteryPct = level * 100 / scale.toFloat()
            if (batteryPct <= 5.0f) {
                notifyFailsafe("배터리 부족(5% 이하)으로 녹음이 안전 종료되었습니다.")
                return true
            }
        }
        return false
    }

    private fun notifyFailsafe(message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
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