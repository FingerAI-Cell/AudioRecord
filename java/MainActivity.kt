package com.example.recordingvoice
import android.Manifest
import android.content.pm.PackageManager
import android.widget.RelativeLayout
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
import android.os.Environment
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private var audioRecord: AudioRecord? = null
    private var outputFile: String = ""
    private var wavFilePath: String = ""
    private val recordedData = mutableListOf<ByteArray>()
    private var isRecording = false
    private var noiseSuppressor: NoiseSuppressor? = null
    private lateinit var recordingLayout: RelativeLayout
    private lateinit var muteLayout: RelativeLayout
    private var isMuted = false // 음소거 상태를 저장
    private var isMeetingActive = false // 회의 상태를 저장
    private var hasPermission = false // 녹음 권한
    private val REQUEST_RECORD_AUDIO_PERMISSION = 200
    private val sampleRate = 44100 // 샘플링 속도
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO // 모노 채널
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT // 16비트 PCM

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        outputFile =
            "${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)}/audiorecord_${System.currentTimeMillis()}.pcm"
        wavFilePath =
            "${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)}/audiorecord_${System.currentTimeMillis()}.wav"

        val startButton = findViewById<Button>(R.id.startButton)
        val muteButton = findViewById<Button>(R.id.muteButton)
        val stopButton = findViewById<Button>(R.id.stopButton)
        recordingLayout = findViewById(R.id.recordingLayout) // 참조를 캐싱
        muteLayout = findViewById(R.id.muteLayout)

        startButton.setOnClickListener { startRecordingWithMute() }
        muteButton.setOnClickListener { toggleMute() }
        stopButton.setOnClickListener { stopRecording() }
        requestPermissions()
    }

    private fun requestPermissions() {
        // 녹음 권한이 있는지 확인
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            hasPermission = true
        } else {
            // 권한 요청
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQUEST_RECORD_AUDIO_PERMISSION
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "녹음 권한이 승인되었습니다.", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "녹음 권한이 필요합니다.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun applyBandPassFilter(audioData: ShortArray, sampleRate: Int, lowFreq: Float, highFreq: Float): ShortArray {
        val filteredData = ShortArray(audioData.size)
        val lowPassCoeff = 2 * Math.PI * lowFreq / sampleRate
        val highPassCoeff = 2 * Math.PI * highFreq / sampleRate

        var prevSample = 0.0
        for (i in audioData.indices) {
            val sample = audioData[i] / 32768.0 // Normalize to [-1.0, 1.0]
            val lowPassed = sample - prevSample * lowPassCoeff
            val highPassed = lowPassed * highPassCoeff
            prevSample = sample

            // Convert back to 16-bit PCM
            filteredData[i] = (highPassed * 32768).toInt().toShort()
        }
        return filteredData
    }

    private fun showRecordingLayout() {
        recordingLayout.visibility = View.VISIBLE
    }

    private fun hideRecordingLayout() {
        recordingLayout.visibility = View.GONE
    }

    private fun startRecordingWithMute() {
        if (isMeetingActive) {
            Toast.makeText(this, "회의가 진행 중입니다.", Toast.LENGTH_SHORT).show()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "녹음 권한이 필요합니다.", Toast.LENGTH_SHORT).show()
            requestPermissions()
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
            noiseSuppressor = if (NoiseSuppressor.isAvailable()) {
                NoiseSuppressor.create(audioRecord!!.audioSessionId)
            } else null

            audioRecord?.startRecording()
            isMeetingActive = true
            isRecording = true
            isMuted = false // 초기 상태: 음소거 해제
            recordedData.clear() // 이전 데이터 초기화
            showRecordingLayout()

            // 녹음 데이터를 메모리에 저장하는 쓰레드 실행
            Thread {
                val audioData = ByteArray(bufferSize)
                val silenceData = ByteArray(bufferSize) { 0 } // 묵음 데이터
                val shortBuffer = ShortArray(bufferSize / 2) // 16-bit PCM용 ShortArray

                try {
                    while (isRecording) {
                        val bytesRead = audioRecord!!.read(audioData, 0, audioData.size)
                        if (bytesRead > 0) {
                            // ByteArray를 ShortArray로 변환
                            for (i in 0 until bytesRead step 2) {
                                shortBuffer[i / 2] =
                                    ((audioData[i + 1].toInt() shl 8) or (audioData[i].toInt() and 0xFF)).toShort()
                            }

                            // DSP 처리 적용
                            val processedShortArray = if (isMuted) {
                                ShortArray(shortBuffer.size) { 0 } // 음소거 상태 시 묵음 처리
                            } else {
                                applyBandPassFilter(shortBuffer, sampleRate, 300f, 3000f)
                            }

                            // DSP 결과를 ByteArray로 변환
                            val processedByteArray = ByteArray(processedShortArray.size * 2)
                            for (i in processedShortArray.indices) {
                                processedByteArray[i * 2] = (processedShortArray[i].toInt() and 0xFF).toByte()
                                processedByteArray[i * 2 + 1] =
                                    (processedShortArray[i].toInt() shr 8 and 0xFF).toByte()
                            }

                            // 저장
                            synchronized(recordedData) {
                                recordedData.add(processedByteArray)
                            }
                        }
                    }
                } catch (e: IOException) {
                    e.printStackTrace()
                } finally {
                    audioRecord?.stop()
                    audioRecord?.release()
                    noiseSuppressor?.release()
                    audioRecord = null
                }
            }.start()
        } catch (e: Exception) { // 바깥쪽 try의 예외 처리
            e.printStackTrace()
            runOnUiThread {
                Toast.makeText(this, "녹음 시작 실패: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
        }

    private fun showMuteLayout() {
        muteLayout.visibility = View.VISIBLE
    }

    private fun hideMuteLayout() {
        muteLayout.visibility = View.GONE
    }

    private fun toggleMute() {
        val message: String
        if (!isMeetingActive) {
            Toast.makeText(this, "진행중인 회의가 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }
        isMuted = !isMuted
        if (isMuted) {
            showMuteLayout()
            message = "음소거 설정"
        } else {
            hideMuteLayout()
            message = "음소거 해제"
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun stopRecording() {
        if (!isMeetingActive) {
            Toast.makeText(this, "진행중인 회의가 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }
        isRecording = false
        isMeetingActive = false
        hideRecordingLayout()
        hideMuteLayout()
        synchronized(recordedData) {
            try {   // WAV 파일로 저장
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                outputFile = "${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)}/audiorecord_$timestamp.pcm"
                wavFilePath = "${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)}/audiorecord_$timestamp.wav"

                val wavOutputStream = FileOutputStream(wavFilePath)
                val totalAudioLen = recordedData.sumOf { it.size }.toLong()
                val header = ByteArray(44)   // WAV 헤더 생성

                prepareWavHeader(header, totalAudioLen, sampleRate, 1, 16)
                wavOutputStream.write(header)

                for (data in recordedData) {   // PCM 데이터 기록
                    wavOutputStream.write(data)
                }
                wavOutputStream.close()
                Toast.makeText(this, "회의 종료", Toast.LENGTH_SHORT).show()
            } catch (e: IOException) {
                e.printStackTrace()
                Toast.makeText(this, "파일 저장 실패", Toast.LENGTH_SHORT).show()
            } finally {
                recordedData.clear()
            }
        }
    }

    private fun prepareWavHeader(
        header: ByteArray,
        totalAudioLen: Long,
        sampleRate: Int,
        channels: Int,
        bitRate: Int
    ) {
        val totalDataLen = totalAudioLen + 36
        val byteRate = (sampleRate * channels * bitRate / 8).toLong()

        // RIFF Header
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

        // fmt sub-chunk
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        header[16] = 16 // Sub-chunk size
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1 // PCM format
        header[21] = 0
        header[22] = channels.toByte()
        header[23] = 0
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte()
        header[27] = ((sampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = (channels * bitRate / 8).toByte()
        header[33] = 0
        header[34] = bitRate.toByte()
        header[35] = 0

        // data sub-chunk
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        header[40] = (totalAudioLen and 0xff).toByte()
        header[41] = ((totalAudioLen shr 8) and 0xff).toByte()
        header[42] = ((totalAudioLen shr 16) and 0xff).toByte()
        header[43] = ((totalAudioLen shr 24) and 0xff).toByte()
    }
}