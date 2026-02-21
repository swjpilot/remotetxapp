package net.remotetx.hamradio

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.*
import android.content.pm.PackageManager
import android.media.*
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.webkit.*
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import java.text.SimpleDateFormat
import java.util.*
import kotlin.system.exitProcess

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var callsignInput: EditText
    private lateinit var connectButton: Button
    private lateinit var callsignLabel: TextView
    private lateinit var mediaSession: MediaSession
    private lateinit var sharedPrefs: SharedPreferences
    private val PERMISSION_REQUEST_CODE = 100
    private val CHANNEL_ID = "RemoteTX_Channel"
    private val ACCESSIBILITY_ACTION = "net.remotetx.hamradio.ACCESSIBILITY_KEY_EVENT"

    private var versionTapCount = 0
    private var isDebugToastEnabled = false
    private val sessionLogs = mutableListOf<String>()

    private var audioTrack: AudioTrack? = null
    private var keepAliveThread: Thread? = null
    private var isKeepingAlive = false

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var discoveredDevices = mutableMapOf<String, BluetoothDevice>()
    private var deviceListAdapter: ArrayAdapter<String>? = null
    private var deviceDialog: AlertDialog? = null
    
    private var audioManager: AudioManager? = null

    private val pttReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            addLog("Broadcast: $action")
            var handled = false
            when (action) {
                Intent.ACTION_MEDIA_BUTTON -> {
                    val event = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                    }
                    if (event != null && isPttKey(event.keyCode)) {
                        addLog("Media Button: ${event.keyCode}")
                        triggerPtt(event.action)
                        handled = true
                    }
                }
                ACCESSIBILITY_ACTION -> {
                    val keyCode = intent.getIntExtra("keyCode", 0)
                    if (isPttKey(keyCode)) triggerPtt(intent.getIntExtra("action", 0))
                }
                BluetoothHeadset.ACTION_VENDOR_SPECIFIC_HEADSET_EVENT -> {
                    val command = intent.getStringExtra(BluetoothHeadset.EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_CMD)
                    addLog("Headset Vendor Cmd: $command")
                }
                "net.remotetx.hamradio.SERVICE_LOG" -> {
                    addLog(intent.getStringExtra("log_message") ?: "")
                }
                "net.remotetx.hamradio.BLE_PTT_EVENT" -> {
                    val isPressed = intent.getBooleanExtra("is_pressed", false)
                    triggerPtt(if (isPressed) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP)
                }
                AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED -> {
                    val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
                    addLog("SCO State: $state")
                    if (state == AudioManager.SCO_AUDIO_STATE_CONNECTED) {
                        addLog("Bluetooth SCO Connected")
                    } else if (state == AudioManager.SCO_AUDIO_STATE_DISCONNECTED) {
                        addLog("Bluetooth SCO Disconnected")
                        // Optionally retry if we expect it to be on
                    }
                }
                else -> {
                    if (action?.endsWith("PTT.DOWN") == true) {
                        triggerPtt(KeyEvent.ACTION_DOWN)
                        handled = true
                    } else if (action?.endsWith("PTT.UP") == true) {
                        triggerPtt(KeyEvent.ACTION_UP)
                        handled = true
                    }
                }
            }
            if (handled && isOrderedBroadcast) abortBroadcast()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sharedPrefs = getSharedPreferences("RemoteTX", MODE_PRIVATE)
        isDebugToastEnabled = sharedPrefs.getBoolean("debug_toast_enabled", false)

        if (sharedPrefs.getBoolean("dark_mode", false)) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        }

        setContentView(R.layout.activity_main)
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        callsignInput = findViewById(R.id.callsignInput)
        connectButton = findViewById(R.id.connectButton)
        webView = findViewById(R.id.webView)
        callsignLabel = findViewById(R.id.callsignLabel)

        addLog("App Started")
        createNotificationChannel() 
        setupWebView(sharedPrefs.getBoolean("dark_mode", false))
        requestPermissions()
        requestBatteryOptimizationExemption()
        setupAudio()
        setupMediaSession()
        registerPttReceiver()

        connectButton.setOnClickListener {
            val callsign = callsignInput.text.toString().trim()
            if (callsign.isNotEmpty()) {
                saveCallsign(callsign)
                connectToCallsign(callsign)
            } else {
                Toast.makeText(this, "Please enter a callsign", Toast.LENGTH_SHORT).show()
            }
        }
        loadAndConnectSavedCallsign()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopBluetoothSco()
        stopSilentAudio()
        try { unregisterReceiver(pttReceiver) } catch (e: Exception) {}
        mediaSession.release()
    }

    private fun addLog(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val logLine = "[$timestamp] $message"
        sessionLogs.add(logLine)
        Log.d("RemoteTX_Log", logLine)
        if (isDebugToastEnabled) {
            runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
        }
    }

    private fun triggerPtt(action: Int) {
        if (action == KeyEvent.ACTION_DOWN) {
            injectHidEvent("\\", "Backslash", 220, true)
        } else if (action == KeyEvent.ACTION_UP) {
            injectHidEvent("\\", "Backslash", 220, false)
        }
    }

    private fun isPttKey(keyCode: Int): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            221, // PTT
            KeyEvent.KEYCODE_BUTTON_1,
            KeyEvent.KEYCODE_F1,
            KeyEvent.KEYCODE_F2,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_DPAD_CENTER -> true
            else -> false
        }
    }

    private fun showBleDeviceDialog() {
        discoveredDevices.clear()
        val deviceNames = mutableListOf<String>()
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            bluetoothAdapter?.bondedDevices?.forEach { device ->
                discoveredDevices[device.address] = device
                deviceNames.add("[Paired] ${device.name ?: "Unknown"}\n${device.address}")
            }
        }
        deviceListAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, deviceNames)
        val builder = AlertDialog.Builder(this)
            .setTitle("Select Bluetooth PTT Device")
            .setAdapter(deviceListAdapter) { _, which ->
                val item = deviceListAdapter!!.getItem(which)
                val address = item!!.split("\n")[1]
                val intent = Intent(this, WebViewService::class.java)
                intent.action = "RECONNECT"
                intent.putExtra("device_address", address)
                startService(intent)
                sharedPrefs.edit().putString("ble_device_address", address).apply()
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Rescan", null)
        deviceDialog = builder.create()
        deviceDialog!!.show()
        deviceDialog!!.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
             val intent = Intent(this, WebViewService::class.java)
             intent.action = "START_SCAN"
             startService(intent)
        }
    }

    private fun injectHidEvent(key: String, code: String, keyCode: Int, isDown: Boolean) {
        val jsKey = if (key == "\\") "\\\\" else key
        val eventType = if (isDown) "keydown" else "keyup"
        val js = "var e = new KeyboardEvent('$eventType', {key:'$jsKey',code:'$code',keyCode:$keyCode,which:$keyCode,bubbles:true}); document.dispatchEvent(e); if(document.activeElement) document.activeElement.dispatchEvent(e);"
        webView.evaluateJavascript(js, null)
    }

    private fun connectToCallsign(callsign: String) {
        webView.loadUrl("https://${callsign.lowercase()}.remotetx.net")
        val serviceIntent = Intent(this, WebViewService::class.java)
        serviceIntent.putExtra("callsign", callsign)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        startSilentAudio() 
        callsignLabel.visibility = View.GONE
        callsignInput.visibility = View.GONE
        connectButton.visibility = View.GONE
    }
    
    private fun startSilentAudio() {
        if (isKeepingAlive) return
        isKeepingAlive = true
        val sampleRate = 44100
        val bufferSize = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        audioTrack?.play()
        keepAliveThread = Thread {
            val silence = ByteArray(bufferSize)
            while (isKeepingAlive) {
                try {
                    audioTrack?.write(silence, 0, bufferSize)
                    Thread.sleep(50) 
                } catch (e: Exception) { break }
            }
        }
        keepAliveThread?.start()
    }
    
    private fun stopSilentAudio() {
        isKeepingAlive = false
        keepAliveThread?.interrupt()
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {}
        audioTrack = null
    }

    private fun setupWebView(isDarkMode: Boolean) {
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.mediaPlaybackRequiresUserGesture = false
        webView.settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        
        // Ensure microphone access is allowed for web content
        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread { 
                    addLog("WebView requesting permissions: ${request.resources.joinToString()}")
                    request.grant(request.resources) 
                }
            }
        }
        
        applyWebViewDarkMode(isDarkMode)
        webView.webViewClient = WebViewClient()
    }

    private fun applyWebViewDarkMode(isDarkMode: Boolean) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
             WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.settings, isDarkMode)
        } else if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            @Suppress("DEPRECATION")
            WebSettingsCompat.setForceDark(webView.settings, if (isDarkMode) WebSettingsCompat.FORCE_DARK_ON else WebSettingsCompat.FORCE_DARK_OFF)
        }
    }

    private fun requestPermissions() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.MODIFY_AUDIO_SETTINGS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val permissionsToRequest = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }

    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                AlertDialog.Builder(this)
                    .setTitle("Battery Optimization")
                    .setMessage("Disable battery optimization.")
                    .setPositiveButton("Settings") { _, _ ->
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        intent.data = Uri.parse("package:$packageName")
                        startActivity(intent)
                    }
                    .setNegativeButton("Cancel", null).show()
            }
        }
    }
    
    private fun setupAudio() {
        audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION
        requestAudioFocus()
        startBluetoothSco()
    }
    
    private fun startBluetoothSco() {
        try {
            audioManager?.let { am ->
                if (am.isBluetoothScoAvailableOffCall) {
                    addLog("Starting Bluetooth SCO")
                    am.startBluetoothSco()
                    am.isBluetoothScoOn = true
                } else {
                    addLog("Bluetooth SCO not available off call")
                }
            }
        } catch (e: Exception) {
            addLog("Error starting SCO: ${e.message}")
        }
    }
    
    private fun stopBluetoothSco() {
        try {
            audioManager?.let { am ->
                am.stopBluetoothSco()
                am.isBluetoothScoOn = false
                am.mode = AudioManager.MODE_NORMAL
            }
        } catch (e: Exception) {}
    }
    
    private fun requestAudioFocus() {
        audioManager?.let { am ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .setOnAudioFocusChangeListener { focusChange ->
                        addLog("Focus Change: $focusChange")
                    }
                    .build()
                am.requestAudioFocus(focusRequest)
            } else {
                 @Suppress("DEPRECATION")
                 am.requestAudioFocus(null, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN)
            }
        }
    }

    private fun saveCallsign(callsign: String) {
        sharedPrefs.edit().putString("callsign", callsign).apply()
    }

    private fun loadAndConnectSavedCallsign() {
        val callsign = sharedPrefs.getString("callsign", null)
        if (callsign != null) {
            callsignInput.setText(callsign)
            connectToCallsign(callsign)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.any { it != PackageManager.PERMISSION_GRANTED }) {
                // ... rationale check ...
            } else {
                setupAudio()
            }
        }
    }

    private fun setupMediaSession() {
        mediaSession = MediaSession(this, "RemoteTXSession")
        mediaSession.setCallback(object : MediaSession.Callback() {
            override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                val event = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                }
                if (event != null && isPttKey(event.keyCode)) {
                    triggerPtt(event.action)
                    return true
                }
                return super.onMediaButtonEvent(mediaButtonIntent)
            }
        })
        @Suppress("DEPRECATION")
        mediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
        val state = PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY)
            .setState(PlaybackState.STATE_PLAYING, 0, 1.0f)
            .build()
        mediaSession.setPlaybackState(state)
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        mediaSession.setPlaybackToLocal(attrs)
        mediaSession.isActive = true
    }

    private fun registerPttReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_BUTTON)
            addAction(BluetoothHeadset.ACTION_VENDOR_SPECIFIC_HEADSET_EVENT)
            addAction(ACCESSIBILITY_ACTION)
            addAction("net.remotetx.hamradio.SERVICE_LOG")
            addAction("net.remotetx.hamradio.BLE_PTT_EVENT")
            addAction(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
            addAction("android.intent.action.PTT.DOWN")
            addAction("android.intent.action.PTT.UP")
            addAction("com.sonim.intent.action.PTT.DOWN")
            addAction("com.sonim.intent.action.PTT.UP")
            addAction("com.kyocera.intent.action.PTT.DOWN")
            addAction("com.kyocera.intent.action.PTT.UP")
            addAction("com.symbol.button.L1") 
            addAction("com.symbol.button.L2")
            priority = 2147483647 
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(pttReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(pttReceiver, filter)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "RemoteTX Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            menu.findItem(R.id.action_app_version).title = "Version: ${pInfo.versionName}"
        } catch (e: Exception) {}
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_toggle_dark_mode -> {
                val isDarkMode = !sharedPrefs.getBoolean("dark_mode", false)
                sharedPrefs.edit().putBoolean("dark_mode", isDarkMode).apply()
                AppCompatDelegate.setDefaultNightMode(if (isDarkMode) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO)
                applyWebViewDarkMode(isDarkMode)
            }
            R.id.action_change_callsign -> {
                callsignLabel.visibility = View.VISIBLE
                callsignInput.visibility = View.VISIBLE
                connectButton.visibility = View.VISIBLE
            }
            R.id.action_connect_ble -> showBleDeviceDialog()
            R.id.action_shutdown -> {
                stopBluetoothSco()
                stopSilentAudio()
                stopService(Intent(this, WebViewService::class.java))
                finishAndRemoveTask()
                exitProcess(0)
            }
            R.id.action_app_version -> {
                versionTapCount++
                if (versionTapCount >= 3) {
                    versionTapCount = 0
                    showDebugMenu()
                }
            }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun showDebugMenu() {
        val options = arrayOf("Show Key Debug Toasts")
        val checkedItems = booleanArrayOf(isDebugToastEnabled)
        AlertDialog.Builder(this)
            .setTitle("Hidden Debug Menu")
            .setMultiChoiceItems(options, checkedItems) { _, _, isChecked ->
                isDebugToastEnabled = isChecked
                sharedPrefs.edit().putBoolean("debug_toast_enabled", isChecked).apply()
            }
            .setNeutralButton("View Log") { _, _ -> showLogDialog() }
            .setPositiveButton("Close", null).show()
    }

    private fun showLogDialog() {
        val logText = sessionLogs.joinToString("\n")
        val textView = TextView(this).apply {
            text = logText
            setPadding(32, 32, 32, 32)
            textSize = 10f
        }
        val scrollView = ScrollView(this).apply { addView(textView) }
        AlertDialog.Builder(this).setTitle("Session Log").setView(scrollView)
            .setPositiveButton("Close", null)
            .setNeutralButton("Clear") { _, _ -> sessionLogs.clear() }.show()
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (ev.action != MotionEvent.ACTION_HOVER_MOVE && ev.action != MotionEvent.ACTION_MOVE) addLog("Motion: ${ev.action}")
        return super.dispatchGenericMotionEvent(ev)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) addLog("Key: ${event.keyCode}")
        if (isPttKey(event.keyCode)) {
            triggerPtt(event.action)
            return true 
        }
        return super.dispatchKeyEvent(event)
    }
}
