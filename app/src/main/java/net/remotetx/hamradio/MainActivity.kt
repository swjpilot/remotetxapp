package net.remotetx.hamradio

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var callsignInput: EditText
    private lateinit var connectButton: Button
    private lateinit var callsignLabel: TextView
    private val PERMISSION_REQUEST_CODE = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Set the theme based on saved preference
        val sharedPrefs = getSharedPreferences("RemoteTX", MODE_PRIVATE)
        val isDarkMode = sharedPrefs.getBoolean("dark_mode", false)
        if (isDarkMode) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        }

        setContentView(R.layout.activity_main)

        callsignInput = findViewById(R.id.callsignInput)
        connectButton = findViewById(R.id.connectButton)
        webView = findViewById(R.id.webView)
        callsignLabel = findViewById(R.id.callsignLabel)

        setupWebView(isDarkMode)
        requestPermissions()
        requestBatteryOptimizationExemption()
        setupAudio()

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

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        
        try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            val version = pInfo.versionName
            val versionItem = menu.findItem(R.id.action_app_version)
            versionItem.title = "Version: $version"
        } catch (e: PackageManager.NameNotFoundException) {
            e.printStackTrace()
        }

        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_toggle_dark_mode -> {
                val sharedPrefs = getSharedPreferences("RemoteTX", MODE_PRIVATE)
                val isDarkMode = sharedPrefs.getBoolean("dark_mode", false)
                sharedPrefs.edit().putBoolean("dark_mode", !isDarkMode).apply()
                if (!isDarkMode) {
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                } else {
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                }
                applyWebViewDarkMode(!isDarkMode)
                true
            }
            R.id.action_change_callsign -> {
                callsignLabel.visibility = View.VISIBLE
                callsignInput.visibility = View.VISIBLE
                connectButton.visibility = View.VISIBLE
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun connectToCallsign(callsign: String) {
        loadWebsite(callsign)
        startForegroundService(callsign)

        callsignLabel.visibility = View.GONE
        callsignInput.visibility = View.GONE
        connectButton.visibility = View.GONE
    }

    private fun setupWebView(isDarkMode: Boolean) {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        applyWebViewDarkMode(isDarkMode)

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    request.grant(request.resources)
                }
            }
        }

        webView.webViewClient = WebViewClient()
    }

    private fun applyWebViewDarkMode(isDarkMode: Boolean) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            // Priority for Android 13+ (API 33+)
             if (isDarkMode) {
                WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.settings, true)
            } else {
                WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.settings, false)
            }
        } else if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            // Fallback for older versions
            if (isDarkMode) {
                WebSettingsCompat.setForceDark(webView.settings, WebSettingsCompat.FORCE_DARK_ON)
            } else {
                WebSettingsCompat.setForceDark(webView.settings, WebSettingsCompat.FORCE_DARK_OFF)
            }
        }
    }

    private fun loadWebsite(callsign: String) {
        val url = "https://${callsign.lowercase()}.remotetx.net"
        webView.loadUrl(url)
    }

    private fun requestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.MODIFY_AUDIO_SETTINGS
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        val permissionsToRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permissionsToRequest.toTypedArray(),
                PERMISSION_REQUEST_CODE
            )
        }
    }

    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            val packageName = packageName

            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                AlertDialog.Builder(this)
                    .setTitle("Battery Optimization")
                    .setMessage("To ensure uninterrupted operation, please disable battery optimization for this app.")
                    .setPositiveButton("Settings") { _, _ ->
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:$packageName")
                        }
                        startActivity(intent)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }
    
    private fun setupAudio() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val devices = audioManager.availableCommunicationDevices
            val bluetoothDevice = devices.firstOrNull { 
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            }
            if (bluetoothDevice != null) {
                audioManager.setCommunicationDevice(bluetoothDevice)
            } else {
                 // Fallback to earpiece if no bluetooth
                 val earpiece = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
                 if (earpiece != null) {
                    audioManager.setCommunicationDevice(earpiece)
                 }
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = false
            @Suppress("DEPRECATION")
            audioManager.startBluetoothSco()
            @Suppress("DEPRECATION")
            audioManager.isBluetoothScoOn = true
        }
    }

    private fun startForegroundService(callsign: String) {
        val serviceIntent = Intent(this, WebViewService::class.java).apply {
            putExtra("callsign", callsign)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun saveCallsign(callsign: String) {
        getSharedPreferences("RemoteTX", MODE_PRIVATE)
            .edit()
            .putString("callsign", callsign)
            .apply()
    }

    private fun loadAndConnectSavedCallsign() {
        val callsign = getSharedPreferences("RemoteTX", MODE_PRIVATE)
            .getString("callsign", null)
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
            val deniedPermissions = permissions.filterIndexed { index, _ ->
                grantResults[index] != PackageManager.PERMISSION_GRANTED
            }
            if (deniedPermissions.isNotEmpty()) {
                Toast.makeText(
                    this,
                    "Some permissions were denied. Audio may not work properly.",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                // Permissions granted, retry audio setup
                setupAudio()
            }
        }
    }
}
