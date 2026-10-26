package com.screenmemory.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputEditText
import com.screenmemory.app.chat.ChatStore
import com.screenmemory.app.chat.Conversation
import com.screenmemory.app.service.CaptureService
import com.screenmemory.app.util.Preview
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs

    private lateinit var tvStatus: TextView
    private lateinit var tvPermission: TextView
    private lateinit var btnStart: MaterialButton
    private lateinit var btnStop: MaterialButton

    // 顶部两个平级入口
    private lateinit var tabs: TabLayout
    private lateinit var mainPane: View
    private lateinit var historyPane: View
    private lateinit var historyList: LinearLayout
    private lateinit var tvHistoryCount: TextView
    private lateinit var tvClearHistory: TextView

    private lateinit var etApiKey: TextInputEditText
    private lateinit var etChatBase: TextInputEditText
    private lateinit var etChatModel: TextInputEditText
    private lateinit var etAsrBase: TextInputEditText
    private lateinit var etAsrModel: TextInputEditText

    private lateinit var sliderSeconds: Slider
    private lateinit var sliderFrames: Slider
    private lateinit var sliderFps: Slider
    private lateinit var tvFpsHint: TextView
    private lateinit var tvThinkingHint: TextView
    private lateinit var rbThinkingAuto: RadioButton
    private lateinit var rbThinkingOff: RadioButton
    private lateinit var rbThinkingOn: RadioButton
    private lateinit var etSeconds: TextInputEditText
    private lateinit var etFrames: TextInputEditText
    private lateinit var swSaveClip: SwitchCompat

    private lateinit var rbFrames: RadioButton
    private lateinit var rbVideo: RadioButton
    private lateinit var groupFrames: View
    private lateinit var groupVideo: View
    private lateinit var etVideoChat: TextInputEditText
    private lateinit var etVideoUpload: TextInputEditText
    private lateinit var etVideoKey: TextInputEditText
    private lateinit var etVideoModel: TextInputEditText
    private lateinit var etAsrKey: TextInputEditText
    private lateinit var swLocalAsr: SwitchCompat
    private lateinit var rbAudioAuto: RadioButton
    private lateinit var rbAudioOn: RadioButton
    private lateinit var rbAudioOff: RadioButton
    private lateinit var tvAudioModeHint: TextView
    private lateinit var rbQualityUltraLow: RadioButton
    private lateinit var rbQualitySaver: RadioButton
    private lateinit var rbQualityStandard: RadioButton
    private lateinit var rbQualitySharp: RadioButton
    private lateinit var tvQualityHint: TextView

    /** 防止输入框与滑块互相回调造成死循环 */
    private var syncing = false

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                launchService(result.resultCode, data)
            } else {
                toast("没有获得录屏授权，助手未启动")
            }
        }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants[Manifest.permission.RECORD_AUDIO] == true) {
                ensureOverlayThenCapture()
            } else {
                toast("需要麦克风权限才能录声音")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        bindViews()
        loadSettings()
        bindActions()
    }

    private fun bindViews() {
        tvStatus = findViewById(R.id.tvStatus)
        tvPermission = findViewById(R.id.tvPermission)
        tabs = findViewById(R.id.tabs)
        mainPane = findViewById(R.id.mainPane)
        historyPane = findViewById(R.id.historyPane)
        historyList = findViewById(R.id.historyList)
        tvHistoryCount = findViewById(R.id.tvHistoryCount)
        tvClearHistory = findViewById(R.id.tvClearHistory)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        etApiKey = findViewById(R.id.etApiKey)
        etChatBase = findViewById(R.id.etChatBase)
        etChatModel = findViewById(R.id.etChatModel)
        etAsrBase = findViewById(R.id.etAsrBase)
        etAsrModel = findViewById(R.id.etAsrModel)
        sliderSeconds = findViewById(R.id.sliderSeconds)
        sliderFrames = findViewById(R.id.sliderFrames)
        sliderFps = findViewById(R.id.sliderFps)
        tvFpsHint = findViewById(R.id.tvFpsHint)
        tvThinkingHint = findViewById(R.id.tvThinkingHint)
        rbThinkingAuto = findViewById(R.id.rbThinkingAuto)
        rbThinkingOff = findViewById(R.id.rbThinkingOff)
        rbThinkingOn = findViewById(R.id.rbThinkingOn)
        etSeconds = findViewById(R.id.etSeconds)
        etFrames = findViewById(R.id.etFrames)
        swSaveClip = findViewById(R.id.swSaveClip)

        rbFrames = findViewById(R.id.rbFrames)
        rbVideo = findViewById(R.id.rbVideo)
        groupFrames = findViewById(R.id.groupFrames)
        groupVideo = findViewById(R.id.groupVideo)
        etVideoChat = findViewById(R.id.etVideoChat)
        etVideoUpload = findViewById(R.id.etVideoUpload)
        etVideoKey = findViewById(R.id.etVideoKey)
        etVideoModel = findViewById(R.id.etVideoModel)
        etAsrKey = findViewById(R.id.etAsrKey)
        swLocalAsr = findViewById(R.id.swLocalAsr)
        rbAudioAuto = findViewById(R.id.rbAudioAuto)
        rbAudioOn = findViewById(R.id.rbAudioOn)
        rbAudioOff = findViewById(R.id.rbAudioOff)
        tvAudioModeHint = findViewById(R.id.tvAudioModeHint)
        rbQualityUltraLow = findViewById(R.id.rbQualityUltraLow)
        rbQualitySaver = findViewById(R.id.rbQualitySaver)
        rbQualityStandard = findViewById(R.id.rbQualityStandard)
        rbQualitySharp = findViewById(R.id.rbQualitySharp)
        tvQualityHint = findViewById(R.id.tvQualityHint)
    }

    private fun loadSettings() {
        etApiKey.setText(prefs.apiKey)
        etChatBase.setText(prefs.chatUrl)
        etChatModel.setText(prefs.chatModel)
        etAsrBase.setText(prefs.asrUrl)
        etAsrModel.setText(prefs.asrModel)

        syncing = true
        sliderSeconds.value = prefs.clipSeconds.toFloat()
        sliderFrames.value = prefs.frameCount.toFloat()
        etSeconds.setText(prefs.clipSeconds.toString())
        etFrames.setText(prefs.frameCount.toString())
        syncing = false

        when (prefs.qualityProfile) {
            0 -> rbQualitySaver.isChecked = true
            2 -> rbQualitySharp.isChecked = true
            QualityProfile.ULTRA_SAVER_INDEX -> rbQualityUltraLow.isChecked = true
            else -> rbQualityStandard.isChecked = true
        }
        updateQualityHint()

        swSaveClip.isChecked = prefs.saveClip

        etVideoChat.setText(prefs.qwenChatUrl)
        etVideoUpload.setText(prefs.qwenUploadUrl)
        etVideoKey.setText(prefs.qwenApiKey)
        etVideoModel.setText(prefs.qwenVideoModel)
        etAsrKey.setText(prefs.asrApiKey)
        swLocalAsr.isChecked = prefs.preferLocalAsr
        when (prefs.qwenAudioMode) {
            1 -> rbAudioOn.isChecked = true
            2 -> rbAudioOff.isChecked = true
            else -> rbAudioAuto.isChecked = true
        }
        updateAudioHint()

        syncing = true
        sliderFps.value = prefs.videoFps
        syncing = false
        when (prefs.thinkingMode) {
            0 -> rbThinkingOff.isChecked = true
            1 -> rbThinkingOn.isChecked = true
            else -> rbThinkingAuto.isChecked = true
        }
        updateFpsHint()
        updateThinkingHint()

        if (prefs.videoMode) rbVideo.isChecked = true else rbFrames.isChecked = true
        updateModeVisibility()
    }

    private fun updateModeVisibility() {
        val video = rbVideo.isChecked
        groupFrames.visibility = if (video) View.GONE else View.VISIBLE
        groupVideo.visibility = if (video) View.VISIBLE else View.GONE
    }

    /**
     * 视频抽帧频率提示：直接把「这段片段会变成多少帧」算给用户看，
     * 因为首字延迟跟帧数成正比，光一个 fps 数字没有体感。
     */
    private fun updateFpsHint() {
        val fps = sliderFps.value
        val seconds = (etSeconds.text?.toString()?.toIntOrNull() ?: prefs.clipSeconds)
        val frames = (seconds * fps).toInt().coerceAtLeast(1)
        tvFpsHint.text = "%.1f 帧/秒 · %d 秒的片段约 %d 帧".format(fps, seconds, frames)
    }

    private fun updateThinkingHint() {
        tvThinkingHint.text = when {
            rbThinkingOff.isChecked -> "已关闭：请求带 enable_thinking=false，出字最快"
            rbThinkingOn.isChecked -> "已开启：模型会先推理再回答，更准但明显更慢"
            else -> "不传该参数（默认）：由模型自己决定，兼容性最好"
        }
    }

    private fun updateQualityHint() {
        val index = when {
            rbQualityUltraLow.isChecked -> QualityProfile.ULTRA_SAVER_INDEX
            rbQualitySaver.isChecked -> 0
            rbQualitySharp.isChecked -> 2
            else -> 1
        }
        val seconds = sliderSeconds.value.toInt()
        tvQualityHint.text = "当前：" + QualityProfile.of(index).summary(seconds)
    }

    /** 让用户看到「能听声音」当前到底是自动判定的结果，还是手动指定 */
    private fun updateAudioHint() {
        val model = etVideoModel.text?.toString()?.trim().orEmpty().ifBlank { "qwen-vl-max" }
        val autoHears = model.contains("omni", ignoreCase = true)
        tvAudioModeHint.text = when {
            rbAudioOn.isChecked -> "当前：能听声音（手动指定）"
            rbAudioOff.isChecked -> "当前：听不到声音（手动指定）"
            autoHears -> "自动判定：能听声音（型号含 omni）"
            else -> "自动判定：听不到声音（型号不含 omni）"
        }
    }

    private fun bindActions() {
        sliderSeconds.addOnChangeListener { _, value, _ ->
            if (syncing) return@addOnChangeListener
            syncing = true
            val text = value.toInt().toString()
            etSeconds.setText(text)
            etSeconds.setSelection(text.length)
            syncing = false
            updateQualityHint()
            updateFpsHint()
        }
        sliderFrames.addOnChangeListener { _, value, _ ->
            if (syncing) return@addOnChangeListener
            syncing = true
            val text = value.toInt().toString()
            etFrames.setText(text)
            etFrames.setSelection(text.length)
            syncing = false
        }
        sliderFps.addOnChangeListener { _, _, _ ->
            if (syncing) return@addOnChangeListener
            updateFpsHint()
        }
        findViewById<android.widget.RadioGroup>(R.id.rgThinking)
            .setOnCheckedChangeListener { _, _ -> updateThinkingHint() }
        etSeconds.doAfterTextChanged { editable ->
            if (syncing) return@doAfterTextChanged
            val value = editable?.toString()?.toIntOrNull() ?: return@doAfterTextChanged
            if (value in CLIP_MIN..CLIP_MAX) {
                syncing = true
                sliderSeconds.value = value.toFloat()
                syncing = false
            }
        }
        etFrames.doAfterTextChanged { editable ->
            if (syncing) return@doAfterTextChanged
            val value = editable?.toString()?.toIntOrNull() ?: return@doAfterTextChanged
            if (value in FRAME_MIN..FRAME_MAX) {
                syncing = true
                sliderFrames.value = value.toFloat()
                syncing = false
            }
        }
        btnStart.setOnClickListener {
            saveSettings()
            startAssistant()
        }
        findViewById<android.widget.RadioGroup>(R.id.rgMode).setOnCheckedChangeListener { _, _ ->
            updateModeVisibility()
        }
        findViewById<TextView>(R.id.tvModeInfo).setOnClickListener {
            showModeInfoDialog()
        }
        findViewById<android.widget.RadioGroup>(R.id.rgAudioMode).setOnCheckedChangeListener { _, _ ->
            updateAudioHint()
        }
        findViewById<android.widget.RadioGroup>(R.id.rgQuality).setOnCheckedChangeListener { _, _ ->
            updateQualityHint()
        }
        etVideoModel.doAfterTextChanged { updateAudioHint() }
        setupTabs()
        btnStop.setOnClickListener {
            startService(CaptureService.stopIntent(this))
            Handler(Looper.getMainLooper()).postDelayed({ refreshStatus() }, 600)
        }
    }

    private fun showModeInfoDialog() {
        AlertDialog.Builder(this)
            .setTitle("CHARCHAR")
            .setMessage("CHARCHAR")
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun saveSettings() {
        prefs.apiKey = etApiKey.text?.toString()?.trim().orEmpty()
        prefs.chatUrl = etChatBase.text?.toString()?.trim().orEmpty()
        prefs.chatModel = etChatModel.text?.toString()?.trim().orEmpty()
        prefs.asrUrl = etAsrBase.text?.toString()?.trim().orEmpty()
        prefs.asrModel = etAsrModel.text?.toString()?.trim().orEmpty()

        val seconds = etSeconds.text?.toString()?.toIntOrNull() ?: prefs.clipSeconds
        val frames = etFrames.text?.toString()?.toIntOrNull() ?: prefs.frameCount
        prefs.clipSeconds = seconds
        prefs.frameCount = frames
        prefs.qualityProfile = when {
            rbQualityUltraLow.isChecked -> QualityProfile.ULTRA_SAVER_INDEX
            rbQualitySaver.isChecked -> 0
            rbQualitySharp.isChecked -> 2
            else -> 1
        }
        prefs.saveClip = swSaveClip.isChecked
        prefs.videoMode = rbVideo.isChecked
        prefs.qwenChatUrl = etVideoChat.text?.toString()?.trim().orEmpty()
        prefs.qwenUploadUrl = etVideoUpload.text?.toString()?.trim().orEmpty()
        prefs.qwenApiKey = etVideoKey.text?.toString()?.trim().orEmpty()
        prefs.qwenVideoModel = etVideoModel.text?.toString()?.trim().orEmpty()
        prefs.asrApiKey = etAsrKey.text?.toString()?.trim().orEmpty()
        prefs.preferLocalAsr = swLocalAsr.isChecked
        prefs.qwenAudioMode = when {
            rbAudioOn.isChecked -> 1
            rbAudioOff.isChecked -> 2
            else -> 0
        }
        prefs.videoFps = sliderFps.value
        prefs.thinkingMode = when {
            rbThinkingOff.isChecked -> 0
            rbThinkingOn.isChecked -> 1
            else -> -1
        }

        // 超出范围时把输入框拉回合法值，让用户看到实际生效的数字
        syncing = true
        if (etSeconds.text?.toString() != prefs.clipSeconds.toString()) {
            etSeconds.setText(prefs.clipSeconds.toString())
        }
        if (etFrames.text?.toString() != prefs.frameCount.toString()) {
            etFrames.setText(prefs.frameCount.toString())
        }
        sliderSeconds.value = prefs.clipSeconds.toFloat()
        sliderFrames.value = prefs.frameCount.toFloat()
        syncing = false

        notifyServiceSettingsChanged()
    }

    /** 服务在运行时，把新的时长/帧数推给正在录制的实例 */
    private fun notifyServiceSettingsChanged() {
        if (!CaptureService.isRunning) return
        try {
            startService(
                Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_UPDATE)
            )
        } catch (_: Throwable) {
        }
    }

    private fun startAssistant() {
        if (CaptureService.isRunning) {
            toast("助手已经在运行了")
            return
        }
        val missing = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            missing.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
            return
        }
        ensureOverlayThenCapture()
    }

    private fun ensureOverlayThenCapture() {
        if (!Settings.canDrawOverlays(this)) {
            toast("请先打开“显示在其他应用上层”权限")
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (t: Throwable) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            }
            return
        }
        val manager = getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun launchService(resultCode: Int, data: Intent) {
        saveSettings()
        try {
            ContextCompat.startForegroundService(
                this,
                CaptureService.startIntent(this, resultCode, data)
            )
            toast("助手启动中…")
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    moveTaskToBack(true)
                } catch (_: Throwable) {
                }
            }, 900)
        } catch (t: Throwable) {
            toast("启动失败：${t.message}")
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onPause() {
        saveSettings()
        super.onPause()
    }

    private fun refreshStatus() {
        val running = CaptureService.isRunning
        tvStatus.text = if (running) "运行中 · 悬浮球已就绪" else "未运行"
        tvStatus.setTextColor(if (running) 0xFF12B76A.toInt() else 0xFF8A8F9C.toInt())
        btnStart.isEnabled = !running
        btnStop.isEnabled = running

        val mic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val overlay = Settings.canDrawOverlays(this)
        val notify = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        tvPermission.text = buildString {
            append(if (mic) "麦克风 ✓" else "麦克风 ✗")
            append("    ")
            append(if (overlay) "悬浮窗 ✓" else "悬浮窗 ✗")
            append("    ")
            append(if (notify) "通知 ✓" else "通知 ✗")
        }
        refreshHistory()
    }

    // ------------------------------------------------------------------ 顶部两个平级入口

    private fun setupTabs() {
        tabs.removeAllTabs()
        tabs.addTab(tabs.newTab().setText("主功能"))
        tabs.addTab(tabs.newTab().setText("对话历史"))
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                val history = tab.position == 1
                mainPane.visibility = if (history) View.GONE else View.VISIBLE
                historyPane.visibility = if (history) View.VISIBLE else View.GONE
                if (history) refreshHistory()
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) {
                if (tab.position == 1) refreshHistory()
            }
        })
    }

    /** 历史列表：每段一张卡片，点进去看全文并可以继续追问 */
    private fun refreshHistory() {
        val store = ChatStore(this)
        val all = store.loadAll()
        val totalTurns = all.sumOf { it.turns.size }
        tvHistoryCount.text = if (all.isEmpty()) {
            "还没有对话记录 · 点悬浮球提问后会自动保存在本机"
        } else {
            "$totalTurns 轮 · ${all.size} 段对话"
        }
        tvClearHistory.visibility = if (all.isEmpty()) View.GONE else View.VISIBLE
        tvClearHistory.setOnClickListener { confirmClearHistory(store) }

        historyList.removeAllViews()
        val inflater = LayoutInflater.from(this)
        val format = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        for (conv in all) {
            val item = inflater.inflate(R.layout.item_history, historyList, false)
            item.findViewById<TextView>(R.id.tvItemTitle).text =
                historyTitle(conv.turns.firstOrNull()?.answer.orEmpty())
            item.findViewById<TextView>(R.id.tvItemMeta).text =
                format.format(Date(conv.id)) + " · " + conv.turns.size + " 轮"
            item.findViewById<TextView>(R.id.tvItemQuestion).text =
                Preview.plain(
                    conv.turns.firstOrNull()?.question?.trim().orEmpty()
                ).ifBlank { "（无提问）" }
            item.findViewById<TextView>(R.id.tvItemAnswer).text =
                Preview.plain(
                    conv.turns.lastOrNull()?.answer?.trim()?.replace('\n', ' ').orEmpty()
                ).ifBlank { "（没有回答）" }
            item.setOnClickListener { openHistoryDetail(conv) }
            // 使用 XML 中定义的卡片外边距，让相邻记录保持清晰间距。
            historyList.addView(item)
        }
    }

    /** 历史列表标题取 AI 首答的前 18 个字符，去掉 Markdown 后保持单行。 */
    private fun historyTitle(answer: String): String {
        val plain = Preview.plain(answer)
            .replace(Regex("\\s+"), " ")
            .trim()
        if (plain.isEmpty()) return "AI 暂无回复"
        val maxChars = 18
        val codePoints = plain.codePointCount(0, plain.length)
        if (codePoints <= maxChars) return plain
        return plain.substring(0, plain.offsetByCodePoints(0, maxChars)).trimEnd() + "…"
    }

    private fun openHistoryDetail(conv: Conversation) {
        startActivity(
            Intent(this, HistoryDetailActivity::class.java)
                .putExtra(HistoryDetailActivity.EXTRA_ID, conv.id)
        )
    }

    private fun confirmClearHistory(store: ChatStore) {
        AlertDialog.Builder(this)
            .setMessage("删除本机保存的全部对话记录？这个操作不能撤销。")
            .setPositiveButton("删除") { _, _ ->
                store.clear()
                refreshHistory()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val CLIP_MIN = 5
        const val CLIP_MAX = 60
        const val FRAME_MIN = 1
        const val FRAME_MAX = 20
    }
}
