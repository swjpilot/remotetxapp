package net.remotetx.hamradio

import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

class DiagnosticActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val webView = WebView(this)
        setContentView(webView)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = WebViewClient()

        // Load the HTML file from assets
        webView.loadUrl("file:///android_asset/bt_diagnostic.html")
    }
}
