package com.guaiyiwu

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.guaiyiwu.databinding.ActivityMainBinding
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // 录音相关
    private var recorder: MediaRecorder? = null
    private var currentRecordingFile: File? = null
    private var isRecording = false

    // 播放相关
    private val players = mutableListOf<MediaPlayer>()
    private var announcerPlayer: MediaPlayer? = null
    private var backingPlayer: MediaPlayer? = null

    private val handler = Handler(Looper.getMainLooper())

    private val USER_RECORDING_DELAY_MS = 7000L  // 用户录音延迟播放毫秒数
    private val MAX_RECORDINGS = 15               // 最多保存录音数量

    // ── 状态枚举 ──────────────────────────────────
    enum class State { IDLE, RECORDING, PROCESSING, PLAYING }
    private var state = State.IDLE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnRecord.setOnClickListener { onButtonClick() }
        updateUI()
    }

    // ── 按钮点击逻辑 ──────────────────────────────
    private fun onButtonClick() {
        when (state) {
            State.IDLE -> startRecording()
            State.RECORDING -> stopRecording()
            State.PROCESSING, State.PLAYING -> { /* 忽略 */ }
        }
    }

    // ── 录音 ──────────────────────────────────────
    private fun startRecording() {
        if (!checkMicPermission()) return

        enforceRecordingLimit()
        val file = File(filesDir, "recording-${System.currentTimeMillis()}.m4a")
        currentRecordingFile = file

        recorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(44100)
            setAudioEncodingBitRate(128000)
            setOutputFile(file.absolutePath)
            prepare()
            start()
        }

        isRecording = true
        state = State.RECORDING
        updateUI()
    }

    private fun stopRecording() {
        state = State.PROCESSING
        updateUI()

        try {
            recorder?.apply { stop(); release() }
        } catch (e: Exception) {
            currentRecordingFile?.delete()
            currentRecordingFile = null
        }
        recorder = null
        isRecording = false

        // 稍作延迟，确保文件写入完成，再开始播放
        handler.postDelayed({ startPlayback() }, 600)
    }

    // ── 播放逻辑 ──────────────────────────────────
    private fun startPlayback() {
        stopAllPlayers()
        state = State.PLAYING
        updateUI()

        // 1. 先播报员旁白
        val announcerDuration = playAnnouncer()

        if (announcerDuration > 0) {
            // 2. 报员结束前2秒开始背景音乐
            val musicStartDelay = ((announcerDuration - 2000).coerceAtLeast(0)).toLong()
            handler.postDelayed({ startBackingMusic() }, musicStartDelay)

            // 3. 报员结束后2秒，播放所有录音
            handler.postDelayed({ playAllRecordings() }, (announcerDuration + 2000).toLong())
        } else {
            // 没有报员文件，直接播放
            startBackingMusic()
            handler.postDelayed({ playAllRecordings() }, 500)
        }
    }

    /** 播放 announcer.m4a，返回时长(毫秒)，找不到文件返回0 */
    private fun playAnnouncer(): Int {
        return try {
            val afd = assets.openFd("announcer.m4a")
            val mp = MediaPlayer().apply {
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                prepare()
            }
            announcerPlayer = mp
            val duration = mp.duration
            mp.start()
            duration
        } catch (e: Exception) {
            0
        }
    }

    /** 播放背景音乐，从第64秒开始，音量0.15 */
    private fun startBackingMusic() {
        try {
            val afd = assets.openFd("BackingMusic.m4a")
            backingPlayer = MediaPlayer().apply {
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                setVolume(0.15f, 0.15f)
                prepare()
                seekTo(64000)
                start()
            }
        } catch (e: Exception) { /* 没有背景音乐文件则跳过 */ }
    }

    /** 同时播放所有录音（最新100%音量，其余25%） */
    private fun playAllRecordings() {
        val allFiles = getAllRecordingFiles()
        if (allFiles.isEmpty()) {
            onPlaybackFinished()
            return
        }

        // 最新录音排第一（时间戳最大），音量100%；其余25%
        val sorted = allFiles.sortedByDescending { it.name }
        var otherMaxDuration = 0      // 其他录音的最大时长
        var userRecordingDuration = 0  // 最新用户录音的时长

        sorted.forEachIndexed { index, file ->
            try {
                val mp = MediaPlayer().apply {
                    setDataSource(file.absolutePath)
                    prepare()
                    val vol = if (index == 0) 1.0f else 0.25f
                    setVolume(vol, vol)
                }
                players.add(mp)
                if (index == 0) {
                    userRecordingDuration = mp.duration
                } else {
                    if (mp.duration > otherMaxDuration) otherMaxDuration = mp.duration
                }
            } catch (e: Exception) { /* 跳过损坏文件 */ }
        }

        // 还要加入 assets/stock/ 里的预置录音（全部25%）
        val stockFiles = listAssetFiles("stock")
        stockFiles.forEach { name ->
            try {
                val afd = assets.openFd("stock/$name")
                val mp = MediaPlayer().apply {
                    setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                    setVolume(0.25f, 0.25f)
                    prepare()
                }
                players.add(mp)
                if (mp.duration > otherMaxDuration) otherMaxDuration = mp.duration
            } catch (e: Exception) { }
        }

        // 其他录音立即播放，最新用户录音延迟7秒播放
        players.forEachIndexed { index, mp ->
            if (index == 0 && sorted.isNotEmpty()) {
                handler.postDelayed({ mp.start() }, USER_RECORDING_DELAY_MS)
            } else {
                mp.start()
            }
        }

        // 实际最大时长：取其他录音时长 vs 用户录音时长+延迟，取较大值
        val maxDuration = maxOf(otherMaxDuration, userRecordingDuration + USER_RECORDING_DELAY_MS.toInt())

        val statusNames = sorted.take(1).map { it.nameWithoutExtension }.joinToString()
        val bgCount = sorted.size - 1 + stockFiles.size
        binding.tvStatus.text = if (bgCount > 0)
            "正在播放...\n主录音：${statusNames}\n+ ${bgCount} 条背景录音"
        else
            "正在播放...\n${statusNames}"

        // 背景音乐在结束前3秒淡出
        if (maxDuration > 3000) {
            handler.postDelayed({ fadeOutBacking() }, (maxDuration - 3000).toLong())
        }

        // 播放结束后重置
        handler.postDelayed({ onPlaybackFinished() }, maxDuration.toLong())
    }

    private fun fadeOutBacking() {
        val steps = 30
        var step = 0
        var vol = 0.15f
        val decrement = vol / steps
        val timer = Runnable {  }
        // 简单线性淡出
        fun tick() {
            step++
            vol = (vol - decrement).coerceAtLeast(0f)
            backingPlayer?.setVolume(vol, vol)
            if (step < steps) handler.postDelayed(::tick, 100)
        }
        tick()
    }

    private fun onPlaybackFinished() {
        stopAllPlayers()
        state = State.IDLE
        updateUI()
    }

    // ── 工具方法 ──────────────────────────────────

    /** 超出最大录音数时，自动删除最老的录音 */
    private fun enforceRecordingLimit() {
        val files = getAllRecordingFiles().sortedBy { it.name }
        if (files.size >= MAX_RECORDINGS) {
            files.take(files.size - MAX_RECORDINGS + 1).forEach { it.delete() }
        }
    }

    /** 获取 filesDir 下所有用户录音文件（.m4a） */
    private fun getAllRecordingFiles(): List<File> {
        return filesDir.listFiles()
            ?.filter { it.extension == "m4a" }
            ?: emptyList()
    }

    /** 列出 assets 子目录下的所有文件名 */
    private fun listAssetFiles(dir: String): List<String> {
        return try {
            assets.list(dir)?.toList() ?: emptyList()
        } catch (e: Exception) { emptyList() }
    }

    private fun stopAllPlayers() {
        handler.removeCallbacksAndMessages(null)
        players.forEach { try { it.stop(); it.release() } catch (_: Exception) {} }
        players.clear()
        announcerPlayer?.apply { try { stop(); release() } catch (_: Exception) {} }
        announcerPlayer = null
        backingPlayer?.apply { try { stop(); release() } catch (_: Exception) {} }
        backingPlayer = null
    }

    // ── 权限 ──────────────────────────────────────
    private fun checkMicPermission(): Boolean {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) return true
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        return false
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startRecording()
        } else {
            binding.tvStatus.text = "需要麦克风权限才能录音\n请在手机设置中允许"
        }
    }

    // ── 界面更新 ──────────────────────────────────
    private fun updateUI() {
        when (state) {
            State.IDLE -> {
                binding.btnRecord.isEnabled = true
                binding.tvStatus.text = "准备就绪，点击屏幕开始录音"
            }
            State.RECORDING -> {
                binding.btnRecord.isEnabled = true
                binding.tvStatus.text = "🎙️ 正在录音...\n再次点击屏幕停止"
            }
            State.PROCESSING -> {
                binding.btnRecord.isEnabled = false
                binding.tvStatus.text = "⏳ 处理录音中..."
            }
            State.PLAYING -> {
                binding.btnRecord.isEnabled = false
                binding.tvStatus.text = "🔊 正在播放..."
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAllPlayers()
        recorder?.apply { try { stop(); release() } catch (_: Exception) {} }
    }
}
