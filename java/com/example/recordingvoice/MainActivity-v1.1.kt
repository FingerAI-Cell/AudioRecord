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
import java.io.File
import java.io.IOException


class MainActivity : AppCompatActivity() {
    private var recorder: MediaRecorder? = null
    private var mediaPlayer: MediaPlayer? = null
    private var outputFile: String = ""
    private var isRecording = false

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

    private fun requestPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 0)
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