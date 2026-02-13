package com.example.thevpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.io.File
import kotlin.concurrent.thread

class ForegroundService : Service() {

    // Use thread-safe maps to manage multiple processes and their monitor threads.
    private val runningProcesses = mutableMapOf<String, Process>()
    private val monitorThreads = mutableMapOf<String, Thread>()
    private val processLock = Any()

    companion object {
        const val CHANNEL_ID = "TheVpnServiceChannel"
        const val NOTIFICATION_ID = 1
        const val ACTION_LOG = "com.example.thevpn.LOG"
        const val EXTRA_LOG_MESSAGE = "log_message"
        const val ACTION_START = "com.example.thevpn.START"
        const val ACTION_STOP = "com.example.thevpn.STOP"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val toolName = intent.getStringExtra("toolName") ?: return START_NOT_STICKY

                synchronized(processLock) {
                    if (runningProcesses.containsKey(toolName)) {
                        broadcastLog("[系統] $toolName 已经运行，请勿重复启动。")
                        return START_NOT_STICKY
                    }

                    val params = intent.getStringExtra("params") ?: ""
                    val fullCommand = "${File(filesDir, toolName).absolutePath} $params"

                    // If this is the first tool being started, create and show the foreground notification.
                    if (runningProcesses.isEmpty()) {
                        createNotificationChannel()
                        val notificationIntent = Intent(this, MainActivity::class.java)
                        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)
                        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                            .setContentTitle("工具正在运行")
                            .setContentText("点击返回应用")
                            .setSmallIcon(R.mipmap.ic_launcher)
                            .setContentIntent(pendingIntent)
                            .build()
                        startForeground(NOTIFICATION_ID, notification)
                    }

                    // The monitor thread is now specific to each tool.
                    val monitorThread = thread {
                        try {
                            val process = ProcessBuilder("su", "-c", fullCommand).start()
                            synchronized(processLock) { runningProcesses[toolName] = process }

                            thread { try { process.inputStream.bufferedReader().forEachLine { line -> broadcastLog("[$toolName] $line") } } catch (e: Exception) { /* ignore */ } }
                            thread { try { process.errorStream.bufferedReader().forEachLine { line -> broadcastLog("[$toolName ERR] $line") } } catch (e: Exception) { /* ignore */ } }

                            val exitCode = process.waitFor()
                            broadcastLog("[系統] 进程 $toolName 已结束，退出代码: $exitCode")

                        } catch (e: InterruptedException) {
                            broadcastLog("[系統] 进程 $toolName 被用户手动停止")
                            Thread.currentThread().interrupt()
                        } catch (e: Exception) {
                            broadcastLog("[系統] 进程 $toolName 异常: ${e.message}")
                        } finally {
                            synchronized(processLock) {
                                runningProcesses.remove(toolName)
                                monitorThreads.remove(toolName)
                                // Only stop the entire service if this was the last running tool.
                                if (runningProcesses.isEmpty()) {
                                    stopForeground(true)
                                    stopSelf()
                                }
                            }
                        }
                    }
                    monitorThreads[toolName] = monitorThread
                }
            }
            ACTION_STOP -> {
                val toolToStop = intent.getStringExtra("toolName") ?: return START_NOT_STICKY
                thread {
                    val processToKill = synchronized(processLock) { runningProcesses[toolToStop] }

                    try {
                        val stopCmd = "pkill -9 $toolToStop"
                        ProcessBuilder("su", "-c", stopCmd).start().waitFor()
                        processToKill?.destroy() // This interrupts the monitor thread's waitFor()
                    } catch (e: Exception) {
                        broadcastLog("停止操作失败: ${e.message}")
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun broadcastLog(message: String) {
        val intent = Intent(ACTION_LOG).apply {
            putExtra(EXTRA_LOG_MESSAGE, message)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    override fun onDestroy() {
        broadcastLog("[系統] 所有服務已停止")
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(CHANNEL_ID, "工具运行状态", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }
}
