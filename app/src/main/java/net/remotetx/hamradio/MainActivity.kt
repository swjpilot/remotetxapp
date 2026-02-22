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
import android.util.TypedValue
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.webkit.*
import android.widget.*
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
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
    private var isScanning = false
    private val handler = Handler(Looper.getMainLooper())

    private val pttReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            if (action != "net.remotetx.hamradio.SERVICE_LOG") {
                addLog("Broadcast: $action", showToast = false)
            }
            
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
                    val keyAction = if (isPressed) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP
                    addLog("[PTT] Trigger: $keyAction", showToast = false)
                    triggerPtt(keyAction)
                }
                AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED -> {
                    val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
                    addLog("SCO State: $state")
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
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        
        sharedPrefs = getSharedPreferences("RemoteTX", MODE_PRIVATE)
        isDebugToastEnabled = sharedPrefs.getBoolean("debug_toast_enabled", false)

        if (sharedPrefs.getBoolean("dark_mode", false)) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        }

        setContentView(R.layout.activity_main)

        // Adjust padding for system bars to prevent overlap of UI elements and WebView
        val mainLayout = findViewById<LinearLayout>(R.id.mainLayout)
        ViewCompat.setOnApplyWindowInsetsListener(mainLayout) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navigationBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            
            val density = resources.displayMetrics.density
            val defaultPaddingPx = (16 * density).toInt()

            // Calculate the actual ActionBar height
            val actionBarHeight = getActionBarHeight()

            // Apply padding to root container to push WebView and other UI into safe area.
            // top padding = statusBarHeight + actionBarHeight + 16dp
            v.updatePadding(
                left = systemBars.left + defaultPaddingPx,
                top = statusBars.top + actionBarHeight + defaultPaddingPx,
                right = systemBars.right + defaultPaddingPx,
                bottom = navigationBars.bottom + defaultPaddingPx
            )
            insets
        }

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

    private fun getActionBarHeight(): Int {
        val tv = TypedValue()
        return if (theme.resolveAttribute(android.R.attr.actionBarSize, tv, true)) {
            TypedValue.complexToDimensionPixelSize(tv.data, resources.displayMetrics)
        } else {
            0
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopBluetoothSco()
        stopSilentAudio()
        try { unregisterReceiver(pttReceiver) } catch (e: Exception) {}
        mediaSession.release()
    }

    private fun addLog(message: String, showToast: Boolean = true) {
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val logLine = "[$timestamp] $message"
        sessionLogs.add(logLine)
        Log.d("RemoteTX_Log", logLine)
        if (isDebugToastEnabled && showToast) {
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
                if (item != null) {
                    val parts = item.split("\n")
                    val name = parts[0]
                    val address = parts[1]
                    
                    val isPTTZ = name.contains("PTT-Z", ignoreCase = true) || name.contains("PTT", ignoreCase = true)
                    val isBlueParrott = (name.contains("BlueParrott", ignoreCase = true) || 
                                       name.contains("Jabra", ignoreCase = true) || 
                                       name.contains("BP ", ignoreCase = true)) && !isPTTZ
                    
                    addLog("Selected Device: $name ($address). Is BP: $isBlueParrott")
                    
                    val intent = Intent(this, WebViewService::class.java)
                    intent.action = "RECONNECT"
                    intent.putExtra("device_address", address)
                    intent.putExtra("is_blue_parrot", isBlueParrott)
                    startService(intent)
                    
                    sharedPrefs.edit().apply {
                        putString("ble_device_address", address)
                        putBoolean("is_blue_parrot", isBlueParrott)
                        apply()
                    }
                }
            }
            .setNegativeButton("Cancel") { _, _ -> stopBleScan() }
            .setNeutralButton("Rescan", null)
        deviceDialog = builder.create()
        deviceDialog!!.show()
        deviceDialog!!.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
             startBleScan()
             val intent = Intent(this, WebViewService::class.java)
             intent.action = "START_SCAN"
             startService(intent)
        }
        startBleScan()
    }

    private val leScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            if (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                val name = device.name ?: "Unknown"
                if (!discoveredDevices.containsKey(device.address)) {
                    discoveredDevices[device.address] = device
                    runOnUiThread {
                        deviceListAdapter?.add("${name}\n${device.address}")
                        deviceListAdapter?.notifyDataSetChanged()
                    }
                }
            }
        }
    }

    private fun startBleScan() {
        if (isScanning) return
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions()
            return
        }
        isScanning = true
        addLog("Starting BLE Scan")
        bluetoothAdapter?.bluetoothLeScanner?.startScan(leScanCallback)
        handler.postDelayed({ stopBleScan() }, 10000)
    }

    private fun stopBleScan() {
        if (!isScanning) return
        isScanning = false
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(leScanCallback)
        }
        addLog("Stopped BLE Scan")
    }

    private fun injectHidEvent(key: String, code: String, keyCode: Int, isDown: Boolean) {
        val jsKey = if (key == "\\") "\\\\" else key
        val eventType = if (isDown) "keydown" else "keyup"
        val js = "(function(){ var e = new KeyboardEvent('$eventType', {key:'$jsKey',code:'$code',keyCode:$keyCode,which:$keyCode,bubbles:true,cancelable:true}); window.dispatchEvent(e); document.dispatchEvent(e); if(document.activeElement) document.activeElement.dispatchEvent(e); })();"
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
        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread { request.grant(request.resources) }
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
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }
        
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        
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
        audioManager?.let { am ->
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            requestAudioFocus()
            startBluetoothSco()
        }
    }
    
    private fun startBluetoothSco() {
        try {
            audioManager?.let { am ->
                if (am.isBluetoothScoAvailableOffCall) {
                    am.startBluetoothSco()
                    am.isBluetoothScoOn = true
                }
            }
        } catch (e: Exception) {}
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
                    .setOnAudioFocusChangeListener { _ -> }
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
                // ...
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
            priority = 2147483647 
        }
        
        ContextCompat.registerReceiver(this, pttReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
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
