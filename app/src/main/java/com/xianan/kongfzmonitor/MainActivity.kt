package com.xianan.kongfzmonitor

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    companion object {
        private const val NOTIFICATION_PERMISSION_REQUEST = 100
    }

    private lateinit var configRepository: MonitorConfigRepository
    private lateinit var statusText: TextView
    private lateinit var keywordInput: EditText
    private lateinit var maxPriceInput: EditText
    private lateinit var conditionInput: EditText
    private lateinit var shopInput: EditText
    private lateinit var intervalInput: EditText
    private var startAfterNotificationPermission = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configRepository = MonitorConfigRepository(this)
        setContentView(buildContentView())
        loadConfigIntoInputs()
    }

    override fun onResume() {
        super.onResume()
        if (::statusText.isInitialized) updateStatus()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != NOTIFICATION_PERMISSION_REQUEST) return

        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        if (granted && startAfterNotificationPermission) {
            startAfterNotificationPermission = false
            startMonitoring()
        } else {
            startAfterNotificationPermission = false
            Toast.makeText(this, "需要通知权限才能提醒新商品", Toast.LENGTH_LONG).show()
        }
    }

    private fun buildContentView(): View {
        val padding = dp(20)
        val fieldGap = dp(8)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }

        root.addView(TextView(this).apply {
            text = "孔夫子旧书监控"
            textSize = 24f
        })

        statusText = TextView(this).apply {
            textSize = 17f
            setPadding(0, dp(14), 0, dp(18))
        }
        root.addView(statusText)

        keywordInput = addTextField(root, "关键词", "例如：鲁迅全集")
        maxPriceInput = addTextField(root, "最高价格（可选）", "例如：300").apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        conditionInput = addTextField(root, "品相（可选）", "例如：八品以上")
        shopInput = addTextField(root, "指定店铺（可选）", "留空则不限定店铺")
        intervalInput = addTextField(root, "刷新间隔（5～15 秒）", "5").apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        }

        root.addView(Button(this).apply {
            text = "打开孔夫子网页登录"
            setOnClickListener {
                startActivity(Intent(this@MainActivity, LoginActivity::class.java))
            }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(12) })

        root.addView(Button(this).apply {
            text = "开始监控"
            setOnClickListener { requestNotificationPermissionThenStart() }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = fieldGap })

        root.addView(Button(this).apply {
            text = "停止监控"
            setOnClickListener { stopMonitoring() }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = fieldGap })

        return ScrollView(this).apply { addView(root) }
    }

    private fun addTextField(parent: LinearLayout, label: String, hint: String): EditText {
        parent.addView(TextView(this).apply {
            text = label
            textSize = 15f
            setPadding(0, dp(10), 0, dp(4))
        })

        return EditText(this).apply {
            this.hint = hint
            isSingleLine = true
            parent.addView(
                this,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
    }

    private fun loadConfigIntoInputs() {
        val config = configRepository.load()
        keywordInput.setText(config.keyword)
        maxPriceInput.setText(config.maxPrice?.let(::formatNumber) ?: "")
        conditionInput.setText(config.condition)
        shopInput.setText(config.shop)
        intervalInput.setText(config.intervalSeconds.toString())
        updateStatus()
    }

    private fun requestNotificationPermissionThenStart() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            startAfterNotificationPermission = true
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                NOTIFICATION_PERMISSION_REQUEST,
            )
            return
        }

        startMonitoring()
    }

    private fun startMonitoring() {
        val keyword = keywordInput.text.toString().trim()
        if (keyword.isBlank()) {
            Toast.makeText(this, "请输入关键词", Toast.LENGTH_SHORT).show()
            return
        }

        val maxPriceText = maxPriceInput.text.toString().trim()
        val maxPrice = if (maxPriceText.isBlank()) null else maxPriceText.toDoubleOrNull()
        if (maxPriceText.isNotBlank() && (maxPrice == null || maxPrice < 0.0)) {
            Toast.makeText(this, "最高价格格式不正确", Toast.LENGTH_SHORT).show()
            return
        }

        val interval = intervalInput.text.toString().trim().toIntOrNull()
        if (interval == null || interval !in 5..15) {
            Toast.makeText(this, "刷新间隔必须是 5～15 秒", Toast.LENGTH_SHORT).show()
            return
        }

        val config = MonitorConfig(
            keyword = keyword,
            maxPrice = maxPrice,
            condition = conditionInput.text.toString().trim(),
            shop = shopInput.text.toString().trim(),
            intervalSeconds = interval,
            monitoring = true,
        )
        configRepository.save(config)

        try {
            startForegroundService(
                Intent(this, MonitorService::class.java)
                    .setAction(MonitorService.ACTION_START)
                    .putExtra(MonitorService.EXTRA_NEW_MONITORING_SESSION, true)
            )
            updateStatus()
        } catch (error: Exception) {
            configRepository.setMonitoring(false)
            updateStatus()
            Toast.makeText(this, "无法启动后台监控：${error.message ?: "未知错误"}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopMonitoring() {
        configRepository.setMonitoring(false)
        startService(Intent(this, MonitorService::class.java).setAction(MonitorService.ACTION_STOP))
        updateStatus()
    }

    private fun updateStatus() {
        val running = configRepository.load().monitoring
        statusText.text = if (running) "监控状态：运行中" else "监控状态：已停止"
    }

    private fun formatNumber(value: Double): String {
        return if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
