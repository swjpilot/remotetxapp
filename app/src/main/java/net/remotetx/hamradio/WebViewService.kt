package net.remotetx.hamradio

import android.app.*
import android.content.*
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import com.blueparrott.blueparrottsdk.BPSdk
import com.blueparrott.blueparrottsdk.BPHeadset
import com.blueparrott.blueparrottsdk.BPHeadsetListener


class WebViewService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private val NOTIFICATION_ID = 1
    private val CHANNEL_ID = "RemoteTX_Channel"
    
    // BlueParrott SDK
    private var bpHeadset: BPHeadset? = null
    
    companion object {
        const val ACTION_BLE_PTT = "net.remotetx.hamradio.BLE_PTT_EVENT"
        const val EXTRA_IS_PRESSED = "is_pressed"
        const val ACTION_SERVICE_LOG = "net.remotetx.hamradio.SERVICE_LOG"
        const val EXTRA_LOG_MESSAGE = "log_message"
    }

    private val headsetListener = object : BPHeadsetListener() {
        override fun onButtonDown(buttonId: Int) {
            // Parrott Button is usually ID 1
            if (buttonId == 1) {
                addServiceLog("BlueParrott Button Down (PTT)")
                sendPttBroadcast(true)
            } else {
                addServiceLog("BlueParrott Button Down: $buttonId")
            }
        }

        override fun onButtonUp(buttonId: Int) {
            if (buttonId == 1) {
                addServiceLog("BlueParrott Button Up (PTT)")
                sendPttBroadcast(false)
            } else {
                addServiceLog("BlueParrott Button Up: $buttonId")
            }
        }
        
        override fun onConnect() {
            addServiceLog("BlueParrott Connected")
            bpHeadset?.enableSDKMode()
        }

        override fun onDisconnect() {
            addServiceLog("BlueParrott Disconnected")
        }

        override fun onConnectFailure(reason: Int) {
            addServiceLog("BlueParrott Connect Failure: $reason")
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
        
        // Initialize BlueParrott SDK
        try {
            bpHeadset = BPSdk.getBPHeadset(this)
            bpHeadset?.addListener(headsetListener)
            BPSdk.setCustomerUUID("remotetx_ham_radio")
            addServiceLog("BlueParrott SDK Initialized")
        } catch (e: Exception) {
            addServiceLog("BlueParrott SDK Error: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val callsign = intent?.getStringExtra("callsign") ?: "Unknown"
        
        if (action == "RECONNECT") {
            addServiceLog("BlueParrott Reconnecting...")
            bpHeadset?.connect()
        } else if (action == "START_SCAN") {
            addServiceLog("BlueParrott Scan started")
            bpHeadset?.connect() // BlueParrott connect() often initiates a scan/connect process
        }

        val notification = createNotification(callsign)
        startForeground(NOTIFICATION_ID, notification)
        
        return START_STICKY
    }

    private fun sendPttBroadcast(isPressed: Boolean) {
        val pttIntent = Intent(ACTION_BLE_PTT).apply {
            putExtra(EXTRA_IS_PRESSED, isPressed)
        }
        sendBroadcast(pttIntent)
    }

    private fun addServiceLog(msg: String) {
        Log.d("WebViewService", msg)
        val intent = Intent(ACTION_SERVICE_LOG).apply {
            putExtra(EXTRA_LOG_MESSAGE, "[SVC] $msg")
        }
        sendBroadcast(intent)
    }

    private fun createNotification(callsign: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("RemoteTX Active")
            .setContentText("Connected to $callsign. BP PTT monitoring active.")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "RemoteTX Service", NotificationManager.IMPORTANCE_LOW
            )
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RemoteTX::WakeLock")
        wakeLock?.acquire()
    }

    override fun onDestroy() {
        super.onDestroy()
        addServiceLog("Service Stopping")
        wakeLock?.release()
        bpHeadset?.removeListener(headsetListener)
        bpHeadset?.disconnect()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
