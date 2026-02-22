package net.remotetx.hamradio

import android.app.*
import android.bluetooth.*
import android.content.*
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import com.blueparrott.blueparrottsdk.BPSdk
import com.blueparrott.blueparrottsdk.BPHeadset
import com.blueparrott.blueparrottsdk.BPHeadsetListener
import java.util.*


class WebViewService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private val NOTIFICATION_ID = 1
    private val CHANNEL_ID = "RemoteTX_Channel"
    
    // BlueParrott SDK
    private var bpHeadset: BPHeadset? = null
    
    // Generic GATT
    private var bluetoothGatt: BluetoothGatt? = null
    private var currentConnectedAddress: String? = null
    
    private val STANDARD_PTT_SERVICE_UUID = UUID.fromString("03b80e5a-ede8-4b33-a751-6ce34ec4c700")
    private val STANDARD_PTT_CHAR_UUID = UUID.fromString("7772e5db-3868-4112-a1a9-f2669d106bf3")
    private val FFE0_SERVICE_UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
    private val FFE1_CHAR_UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
    private val CLIENT_CHARACTERISTIC_CONFIG = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    
    companion object {
        const val ACTION_BLE_PTT = "net.remotetx.hamradio.BLE_PTT_EVENT"
        const val EXTRA_IS_PRESSED = "is_pressed"
        const val ACTION_SERVICE_LOG = "net.remotetx.hamradio.SERVICE_LOG"
        const val EXTRA_LOG_MESSAGE = "log_message"
    }

    private val headsetListener = object : BPHeadsetListener() {
        override fun onButtonDown(buttonId: Int) {
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

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            try {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    addServiceLog("GATT Error: status=$status")
                    if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        bluetoothGatt = null
                        currentConnectedAddress = null
                    }
                    return
                }

                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    addServiceLog("Generic GATT Connected, discovering services...")
                    gatt.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    addServiceLog("Generic GATT Disconnected")
                    bluetoothGatt = null
                    currentConnectedAddress = null
                }
            } catch (e: SecurityException) {
                addServiceLog("GATT Security Error: ${e.message}")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                addServiceLog("Services Discovered")
                setupStandardPttNotifications(gatt)
            } else {
                addServiceLog("Service Discovery Failed: $status")
            }
        }

        // Android 13+ signature
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            handlePttData(value)
        }

        // Older Android signature
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            handlePttData(characteristic.value)
        }
    }

    private fun handlePttData(value: ByteArray?) {
        if (value != null && value.isNotEmpty()) {
            val firstByte = value[0].toInt()
            // Interpretation: Non-zero or ASCII '1' (48) is down. Zero or ASCII '0' is up.
            val isDown = firstByte != 0 && firstByte != 48
            addLog("GATT PTT Event: ${if (isDown) "Down" else "Up"} (Byte: $firstByte)")
            sendPttBroadcast(isDown)
        }
    }

    private fun setupStandardPttNotifications(gatt: BluetoothGatt) {
        try {
            // Log discovered services for debugging
            for (service in gatt.services) {
                Log.d("WebViewService", "Discovered Service: ${service.uuid}")
            }

            // Try known UUIDs first
            var characteristic = gatt.getService(STANDARD_PTT_SERVICE_UUID)?.getCharacteristic(STANDARD_PTT_CHAR_UUID)
            if (characteristic == null) {
                characteristic = gatt.getService(FFE0_SERVICE_UUID)?.getCharacteristic(FFE1_CHAR_UUID)
            }
            
            // Fallback: Find any characteristic with NOTIFY or INDICATE property
            if (characteristic == null) {
                for (service in gatt.services) {
                    for (char in service.characteristics) {
                        if ((char.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0 ||
                            (char.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
                            characteristic = char
                            addServiceLog("Found notifying char: ${char.uuid} in service: ${service.uuid}")
                            break
                        }
                    }
                    if (characteristic != null) break
                }
            }

            if (characteristic != null) {
                gatt.setCharacteristicNotification(characteristic, true)
                val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG)
                if (descriptor != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    } else {
                        @Suppress("DEPRECATION")
                        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        gatt.writeDescriptor(descriptor)
                    }
                    addServiceLog("Notifications enabled for PTT")
                } else {
                    addServiceLog("PTT char found but CCC descriptor missing")
                }
            } else {
                addServiceLog("Could not find any PTT characteristic")
            }
        } catch (e: SecurityException) {
            addServiceLog("GATT Setup Security Error: ${e.message}")
        } catch (e: Exception) {
            addServiceLog("GATT Setup Error: ${e.message}")
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
        
        try {
            bpHeadset = BPSdk.getBPHeadset(this)
            bpHeadset?.addListener(headsetListener)
            BPSdk.setCustomerUUID("remotetx_ham_radio")
            addServiceLog("BlueParrott SDK Ready")
        } catch (e: Exception) {
            addServiceLog("BlueParrott SDK Error: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val callsign = intent?.getStringExtra("callsign") ?: "Unknown"
        
        val sharedPrefs = getSharedPreferences("RemoteTX", MODE_PRIVATE)
        val savedAddress = sharedPrefs.getString("ble_device_address", null)
        val savedIsBP = sharedPrefs.getBoolean("is_blue_parrot", false)

        val address = intent?.getStringExtra("device_address") ?: savedAddress
        
        // Priority: Intent Extra > SharedPrefs > Default (false)
        val isBlueParrott = if (intent != null && intent.hasExtra("is_blue_parrot")) {
            intent.getBooleanExtra("is_blue_parrot", false)
        } else {
            savedIsBP
        }
        
        if (address != null) {
            if (isBlueParrott) {
                addServiceLog("Attempting connection to BlueParrott...")
                bpHeadset?.connect()
                // Extra safety: trigger SDK mode in case already connected
                bpHeadset?.enableSDKMode()
            } else if (address != currentConnectedAddress || bluetoothGatt == null) {
                addServiceLog("Connecting Generic GATT: $address")
                connectGenericGatt(address)
            } else {
                addServiceLog("Already connected to $address")
            }
        }

        val notification = createNotification(callsign)
        startForeground(NOTIFICATION_ID, notification)
        
        return START_STICKY
    }

    private fun connectGenericGatt(address: String) {
        try {
            val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val device = bluetoothManager.adapter.getRemoteDevice(address)
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
            
            addServiceLog("Initiating GATT connection to $address")
            currentConnectedAddress = address
            bluetoothGatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            addServiceLog("GATT Connect Security Error: ${e.message}")
            currentConnectedAddress = null
        } catch (e: Exception) {
            addServiceLog("GATT Connect Error: ${e.message}")
            currentConnectedAddress = null
        }
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
    
    private fun addLog(msg: String) {
        addServiceLog(msg)
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
            .setContentText("Connected to $callsign. PTT monitoring active.")
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
        try {
            bpHeadset?.removeListener(headsetListener)
            bpHeadset?.disconnect()
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
        } catch (e: SecurityException) {}

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
