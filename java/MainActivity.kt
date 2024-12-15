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
import java.io.File

class MainActivity : AppCompatActivity() {
    private var audioRecord: AudioRecord? = null
    private var wavFilePath: String = ""
    private var isRecording = false
    private var noiseSuppressor: NoiseSuppressor? = null
    private lateinit var recordingLayout: RelativeLayout
    private lateinit var muteLayout: RelativeLayout
    private var isMuted = false // 음소거 상태를 저장
    private var isMeetingActive = false // 회의 상태를 저장
    private var hasPermission = false // 녹음 권한
    private var outputFile: String = ""
    private val REQUEST_RECORD_AUDIO_PERMISSION = 200
    private val sampleRate = 44100 // 샘플링 속도
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO // 모노 채널
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT // 16비트 PCM
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val startButton = findViewById<Button>(R.id.startButton)
        val muteButton = findViewById<Button>(R.id.muteButton)
        val stopButton = findViewById<Button>(R.id.stopButton)
        recordingLayout = findViewById(R.id.recordingLayout) // 참조를 캐싱
        muteLayout = findViewById(R.id.muteLayout)

        startButton.setOnClickListener { startRecording() }
        muteButton.setOnClickListener { toggleMute() }
        stopButton.setOnClickListener { stopRecording() }
        requestPermissions()
    }

    private fun requestPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            hasPermission = true
        } else {   // 권한 요청
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
            filteredData[i] = (highPassed * 32768).toInt().toShort()   // Convert back to 16-bit PCM
        }
        return filteredData
    }

    private fun showRecordingLayout() {
        recordingLayout.visibility = View.VISIBLE
    }

    private fun hideRecordingLayout() {
        recordingLayout.visibility = View.GONE
    }

    private fun startRecording() {
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
            outputFile =
                "${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)}/audiorecord_${System.currentTimeMillis()}.pcm"
            wavFilePath =
                "${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)}/audiorecord_${System.currentTimeMillis()}.wav"

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
            isRecording = true
            isMeetingActive = true

            showRecordingLayout()
            Thread {
                saveAudioToFileHybrid()
            }.start()
            Toast.makeText(this, "녹음을 시작합니다.", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "녹음을 시작할 수 없습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showMuteLayout() { muteLayout.visibility = View.VISIBLE }
    private fun hideMuteLayout() { muteLayout.visibility = View.GONE }

    private fun saveAudioToFileHybrid() {
        val audioData = ByteArray(bufferSize)
        val file = File(outputFile)
        val tempBuffer = mutableListOf<ByteArray>()

        FileOutputStream(file).use { outputStream ->
            try {
                while (isRecording) {
                    val bytesRead = audioRecord!!.read(audioData, 0, audioData.size)
                    if (bytesRead > 0) {
                        if (isMuted) {
                            // 음소거 상태일 때 데이터를 무음으로 설정
                            for (i in audioData.indices) {
                                audioData[i] = 0
                            }
                        }
                        // 무음이든 실제 데이터든 저장
                        tempBuffer.add(audioData.copyOf(bytesRead))

                        // 버퍼 크기가 일정량 이상이면 파일로 저장
                        if (tempBuffer.size >= 10) { // 예: 10개의 블록 저장 후 파일에 씀
                            synchronized(tempBuffer) {
                                tempBuffer.forEach { chunk ->
                                    outputStream.write(chunk)
                                }
                                tempBuffer.clear()
                            }
                        }
                    }
                }
                // 남은 데이터 저장
                synchronized(tempBuffer) {
                    tempBuffer.forEach { chunk ->
                        outputStream.write(chunk)
                    }
                    tempBuffer.clear()
                }
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
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
            Toast.makeText(this, "진행 중인 녹음이 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }
        isRecording = false
        isMeetingActive = false
        audioRecord?.stop()
        audioRecord?.release()
        noiseSuppressor?.release()

        audioRecord = null
        noiseSuppressor = null
        hideRecordingLayout()
        hideMuteLayout()

        Thread {   // 백그라운드 스레드에서 파일 변환
            try {
                val pcmFile = File(outputFile) // 기존 PCM 파일 경로
                val wavFile = File(wavFilePath) // 변환할 WAV 파일 경로
                if (pcmFile.exists()) {
                    val pcmData = pcmFile.readBytes()
                    val wavOutputStream = FileOutputStream(wavFile)
                    val header = ByteArray(44)

                    prepareWavHeader(header, pcmData.size.toLong(), sampleRate, 1, 16)   // WAV 헤더 생성
                    wavOutputStream.write(header)
                    wavOutputStream.write(pcmData)   // PCM 데이터 기록
                    wavOutputStream.close()
                }
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }.start()
        Toast.makeText(this, "회의 종료", Toast.LENGTH_SHORT).show()
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