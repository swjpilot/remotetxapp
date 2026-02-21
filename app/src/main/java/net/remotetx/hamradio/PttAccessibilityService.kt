package net.remotetx.hamradio

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast

class PttAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        Toast.makeText(this, "RemoteTX PTT Service Connected", Toast.LENGTH_SHORT).show()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used for key events, but required to override
    }

    override fun onInterrupt() {
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        // Send Broadcast to MainActivity
        val intent = Intent("net.remotetx.hamradio.ACCESSIBILITY_KEY_EVENT")
        intent.putExtra("keyCode", event.keyCode)
        intent.putExtra("action", event.action)
        sendBroadcast(intent)

        // Determine if we should consume the event
        // We consume PTT/Media keys to prevent them from launching other apps (Assistant/Music)
        // We DO NOT consume Volume keys so the user can still adjust audio volume
        
        val shouldConsume = when (event.keyCode) {
            KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            221, // PTT
            KeyEvent.KEYCODE_BUTTON_1 -> true
            // Volume keys: Don't consume, allow system to change volume
            else -> false
        }
        
        return shouldConsume
    }
}
