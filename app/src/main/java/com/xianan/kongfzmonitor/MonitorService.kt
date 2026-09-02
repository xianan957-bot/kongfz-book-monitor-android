package com.xianan.kongfzmonitor

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MonitorService : Service() {
    companion object {
        const val ACTION_START = "com.xianan.kongfzmonitor.action.START"
        const val ACTION_STOP = "com.xianan.kongfzmonitor.action.STOP"

        private const val TAG = "MonitorService"
    }

    private val running = AtomicBoolean(false)

    private lateinit var configRepository: MonitorConfigRepository
    private lateinit var processedItemStore: ProcessedItemStore
    private val searchClient = KongfzSearchClient()
    private var executor: ExecutorService? = null

    override fun onCreate() {
        super.onCreate()
        configRepository = MonitorConfigRepository(this)
        processedItemStore = ProcessedItemStore(this)
        NotificationHelper.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            configRepository.setMonitoring(false)
            stopMonitoring()
            return START_NOT_STICKY
        }

        val config = configRepository.load()
        if (!config.monitoring || config.keyword.isBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }

        promoteToForeground()
        startLoopIfNeeded()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running.set(false)
        executor?.shutdownNow()
        executor = null
        processedItemStore.close()
        super.onDestroy()
    }

    private fun promoteToForeground() {
        val notification = NotificationHelper.buildMonitorNotification(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NotificationHelper.MONITOR_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NotificationHelper.MONITOR_NOTIFICATION_ID, notification)
        }
    }

    private fun startLoopIfNeeded() {
        if (!running.compareAndSet(false, true)) return

        executor = Executors.newSingleThreadExecutor().also { serviceExecutor ->
            serviceExecutor.execute {
                try {
                    monitorLoop()
                } finally {
                    running.set(false)
                }
            }
        }
    }

    private fun monitorLoop() {
        while (running.get()) {
            val config = configRepository.load()
            if (!config.monitoring || config.keyword.isBlank()) break

            try {
                val items = searchClient.fetch(config)
                processItems(items, config)
            } catch (error: Exception) {
                Log.w(TAG, "This monitoring cycle failed; the next cycle will retry.", error)
            }

            try {
                Thread.sleep(config.intervalSeconds.coerceIn(5, 15) * 1_000L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }

        if (!configRepository.load().monitoring) {
            stopSelf()
        }
    }

    private fun processItems(items: List<KongfzItem>, config: MonitorConfig) {
        for (item in items) {
            if (!running.get()) return
            if (processedItemStore.contains(item.itemId)) continue

            try {
                if (ItemFilter.matches(item, config)) {
                    NotificationHelper.notifyNewItem(this, item)
                    tryOpenItemPage(item)
                }
            } finally {
                processedItemStore.markProcessed(item)
            }
        }
    }

    private fun tryOpenItemPage(item: KongfzItem) {
        val intent = Intent(this, ItemWebViewActivity::class.java).apply {
            putExtra(ItemWebViewActivity.EXTRA_ITEM_URL, item.itemUrl)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        try {
            startActivity(intent)
        } catch (error: Exception) {
            // Modern Android may block background activity launches. The system notification
            // remains the reliable user-controlled path to the same item page.
            Log.i(TAG, "Android did not allow the item page to open automatically.", error)
        }
    }

    private fun stopMonitoring() {
        running.set(false)
        executor?.shutdownNow()
        executor = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
}
