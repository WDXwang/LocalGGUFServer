package com.workbuddy.localmodel.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.workbuddy.localmodel.MainActivity
import com.workbuddy.localmodel.R
import com.workbuddy.localmodel.server.ServerController

/**
 * 前台服务：保证 App 退到后台后 HTTP 服务仍可被局域网内的 PC 调用。
 */
class ApiServerService : Service() {

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                ServerController.stop()
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                val port = intent.getIntExtra(EXTRA_PORT, 8080)
                val apiKey = intent.getStringExtra(EXTRA_API_KEY).orEmpty()
                val maxTokens = intent.getIntExtra(EXTRA_MAX_TOKENS, 512)
                val temperature = intent.getFloatExtra(EXTRA_TEMPERATURE, 0.7f)

                startForegroundCompat(
                    buildNotification("正在启动本地模型服务", "端口 $port")
                )

                val result = ServerController.start(port, apiKey, maxTokens, temperature)
                if (result.isFailure) {
                    val message = result.exceptionOrNull()?.message ?: "启动失败"
                    notify(buildNotification("本地模型服务启动失败", message))
                    ServerController.stop()
                    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return START_NOT_STICKY
                }

                notify(
                    buildNotification(
                        "本地模型服务运行中",
                        "端口 $port · 可从 PC 通过局域网调用"
                    )
                )
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        ServerController.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundCompat(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun notify(notification: Notification) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(title: String, text: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_model)
            .setContentIntent(pending)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "本地模型服务",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "用于保持本地 GGUF 推理 HTTP 服务运行"
                    setShowBadge(false)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    companion object {
        private const val CHANNEL_ID = "local_model_server"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.workbuddy.localmodel.START_SERVER"
        const val ACTION_STOP = "com.workbuddy.localmodel.STOP_SERVER"

        const val EXTRA_PORT = "port"
        const val EXTRA_API_KEY = "api_key"
        const val EXTRA_MAX_TOKENS = "max_tokens"
        const val EXTRA_TEMPERATURE = "temperature"

        fun start(
            context: Context,
            port: Int,
            apiKey: String,
            maxTokens: Int,
            temperature: Float
        ) {
            val intent = Intent(context, ApiServerService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PORT, port)
                putExtra(EXTRA_API_KEY, apiKey)
                putExtra(EXTRA_MAX_TOKENS, maxTokens)
                putExtra(EXTRA_TEMPERATURE, temperature)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, ApiServerService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
