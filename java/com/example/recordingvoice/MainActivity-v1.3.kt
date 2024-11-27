package com.example.recordingvoice
import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Environment
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.IOException
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.audiofx.NoiseSuppressor
import java.io.FileOutputStream
import java.io.File
import android.util.Log

class MainActivity : AppCompatActivity() {
    private var recorder: MediaRecorder? = null
    private var mediaPlayer: MediaPlayer? = null
    private var audioRecord: AudioRecord? = null
    private var outputFile: String = ""
    private var isRecording = false
    private var noiseSuppressor: NoiseSuppressor? = null
    private val REQUEST_RECORD_AUDIO_PERMISSION = 200
    private val sampleRate = 44100 // 샘플링 속도
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO // 모노 채널
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT // 16비트 PCM

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        //outputFile = "${getExternalFilesDir(Environment.DIRECTORY_MUSIC)}/audiorecord_${System.currentTimeMillis()}.pcm"
        outputFile = "${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)}/audiorecord_${System.currentTimeMillis()}.pcm"

        val startButton = findViewById<Button>(R.id.startButton)
        val stopButton = findViewById<Button>(R.id.stopButton)
        val playButton = findViewById<Button>(R.id.playButton)

        startButton.setOnClickListener {
            if (checkPermission()) {
                startRecordingWithAudioRecord() // 버튼 클릭 시 녹음 시작
            } else {
                requestPermissions() // 권한 요청
            }
        }
        stopButton.setOnClickListener {
            stopRecording()
            val wavFile = outputFile.replace(".pcm", ".wav")
            convertPcmToWav(outputFile, wavFile)
        }
        playButton.setOnClickListener { playRecording() }
        requestPermissions()
    }

    private fun checkPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.RECORD_AUDIO),
            REQUEST_RECORD_AUDIO_PERMISSION
        )
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "녹음 권한이 승인되었습니다.", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "녹음 권한이 필요합니다.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startRecording() {
        if (isRecording) {
            Toast.makeText(this, "이미 녹음 중입니다.", Toast.LENGTH_SHORT).show()
            return
        }

        recorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION) // 변경된 AudioSource
            setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
            setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
            setOutputFile(outputFile)
            prepare()
            start()
        }
        isRecording = true
        Toast.makeText(this, "녹음 시작", Toast.LENGTH_SHORT).show()
    }

    private fun startRecordingWithAudioRecord() {
        if (!checkPermission()) {
            Toast.makeText(this, "녹음 권한이 필요합니다.", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )

            audioRecord?.startRecording()
            isRecording = true

            Thread {               // 녹음 데이터를 파일에 저장하는 쓰레드
                val audioData = ByteArray(bufferSize)
                val outputStream = FileOutputStream(outputFile)
                try {
                    while (isRecording) {
                        val read = audioRecord!!.read(audioData, 0, audioData.size)
                        if (read > 0) {
                            outputStream.write(audioData, 0, read)
                        }
                    }
                } catch (e: IOException) {
                    e.printStackTrace()
                } finally {
                    outputStream.close()
                }
            }.start()

            Toast.makeText(this, "녹음 시작", Toast.LENGTH_SHORT).show()
        } catch (e: SecurityException) {
            e.printStackTrace()
            Toast.makeText(this, "녹음 권한 부족", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "녹음 실패: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopRecording() {
        try {
            audioRecord?.apply {
                stop()
                release()
            }
            noiseSuppressor?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            audioRecord = null
            noiseSuppressor = null
            isRecording = false
            Toast.makeText(this, "녹음 중단 및 자원 해제 완료", Toast.LENGTH_SHORT).show()

            val file = File(outputFile)   // 파일 존재 여부 확인
            if (file.exists() && file.length() > 0) {
                Log.d("MainActivity", "파일 저장 성공: ${file.absolutePath}, 크기: ${file.length()} bytes")
            } else {
                Log.e("MainActivity", "파일 저장 실패")
            }
        }
    }

    private fun isFileExists(filePath: String): Boolean {
        val file = File(filePath)
        return file.exists() && file.length() > 0
    }

    private fun convertPcmToWav(pcmFile: String, wavFile: String) {
        val pcmData = File(pcmFile).readBytes()
        val wavOutputStream = FileOutputStream(wavFile)

        val header = ByteArray(44)
        val totalAudioLen = pcmData.size
        val totalDataLen = totalAudioLen + 36
        val longSampleRate = sampleRate.toLong()
        val channels = 1
        val byteRate = (sampleRate * channels * 16 / 8).toLong()

        // WAV 헤더 작성
        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = ((totalDataLen shr 8) and 0xff).toByte()
        header[6] = ((totalDataLen shr 16) and 0xff).toByte()
        header[7] = ((totalDataLen shr 24) and 0xff).toByte()
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        header[16] = 16
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1
        header[21] = 0
        header[22] = channels.toByte()
        header[23] = 0
        header[24] = (longSampleRate and 0xff).toByte()
        header[25] = ((longSampleRate shr 8) and 0xff).toByte()
        header[26] = ((longSampleRate shr 16) and 0xff).toByte()
        header[27] = ((longSampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = (channels * 16 / 8).toByte()
        header[33] = 0
        header[34] = 16
        header[35] = 0
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        header[40] = (totalAudioLen and 0xff).toByte()
        header[41] = ((totalAudioLen shr 8) and 0xff).toByte()
        header[42] = ((totalAudioLen shr 16) and 0xff).toByte()
        header[43] = ((totalAudioLen shr 24) and 0xff).toByte()

        wavOutputStream.write(header)
        wavOutputStream.write(pcmData)
        wavOutputStream.close()
    }

    private fun playRecording() {
        val wavFile = outputFile.replace(".pcm", ".wav")
        if (!isFileExists(wavFile)) {
            Toast.makeText(this, "녹음된 파일이 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(wavFile)
                prepare()
                start()
            }
            Toast.makeText(this, "재생 중...", Toast.LENGTH_SHORT).show()
        } catch (e: IOException) {
            e.printStackTrace()
            Toast.makeText(this, "파일 재생 실패: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}