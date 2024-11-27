package com.example.recordingvoice
import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Bundle
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

        outputFile = "${externalCacheDir?.absolutePath}/audiorecord_${System.currentTimeMillis()}.3gp"

        val startButton = findViewById<Button>(R.id.startButton)
        val stopButton = findViewById<Button>(R.id.stopButton)
        val playButton = findViewById<Button>(R.id.playButton)

        startButton.setOnClickListener { startRecording() }
        stopButton.setOnClickListener { stopRecording() }
        playButton.setOnClickListener { playRecording() }

        requestPermissions()
    }

    private fun checkPermissions(): Boolean {
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
        // 권한 확인
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "녹음 권한이 필요합니다.", Toast.LENGTH_SHORT).show()
            requestPermissions()
            return
        }
        if (isRecording) {
            Toast.makeText(this, "이미 녹음 중입니다.", Toast.LENGTH_SHORT).show()
            return
        }
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )

            // NoiseSuppressor 활성화
            noiseSuppressor = if (NoiseSuppressor.isAvailable()) {
                NoiseSuppressor.create(audioRecord!!.audioSessionId)
            } else {
                null
            }

            audioRecord?.startRecording()
            val audioData = ByteArray(bufferSize)
            val fileOutputStream = FileOutputStream(outputFile)

            isRecording = true
            Thread {
                try {
                    while (isRecording) {
                        val readBytes = audioRecord!!.read(audioData, 0, audioData.size)
                        if (readBytes > 0) {
                            fileOutputStream.write(audioData, 0, readBytes)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    fileOutputStream.close()
                    stopRecording() // 녹음 중단 후 자원 해제
                }
            }.start()
            Toast.makeText(this, "녹음 시작 (DSP)", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "녹음 실패: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopRecording() {
        recorder?.apply {
            stop()
            release()
        }
        recorder = null
        isRecording = false
        Toast.makeText(this, "녹음 저장 완료", Toast.LENGTH_SHORT).show()
    }

    private fun playRecording() {
        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(outputFile)
                prepare()
                start()
            }
            Toast.makeText(this, "재생 중...", Toast.LENGTH_SHORT).show()

            mediaPlayer?.setOnCompletionListener {
                Toast.makeText(this@MainActivity, "재생 완료", Toast.LENGTH_SHORT).show()
                mediaPlayer?.release()
                mediaPlayer = null
            }
        } catch (e: IOException) {
            e.printStackTrace()
            Toast.makeText(this, "파일 재생 실패", Toast.LENGTH_SHORT).show()
        }
    }
}