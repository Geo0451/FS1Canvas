package com.fs1.replay

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Base64
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewAssetLoader
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

@SuppressLint("SetJavaScriptEnabled", "MissingPermission")
class MainActivity : AppCompatActivity(), WatchLink.Listener {

    private lateinit var web: WebView
    private lateinit var link: WatchLink
    private val prefs by lazy { getSharedPreferences("fs1", MODE_PRIVATE) }

    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingSend: List<List<ByteArray>>? = null
    private var afterPermissions: (() -> Unit)? = null

    private val userTemplateDir get() = File(filesDir, "templates").also { it.mkdirs() }

    // ------------------------------------------------------------------ activity plumbing
    private val chooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val uris = if (r.resultCode == Activity.RESULT_OK)
            WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data) else null
        fileCallback?.onReceiveValue(uris); fileCallback = null
    }

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        val cont = afterPermissions; afterPermissions = null
        if (res.values.all { it }) cont?.invoke()
        else { toast("Bluetooth permission is required to talk to the watch."); status("error", "Bluetooth permission denied.") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        link = WatchLink(this, this)
        web = WebView(this)
        web.setBackgroundColor(0xFF0D0B14.toInt())   // no white flash while the page loads
        setContentView(web)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            textZoom = 100                 // ignore system font scaling so layout stays predictable
            useWideViewPort = true
            loadWithOverviewMode = false
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
        }
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(v: WebView, req: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(req.url)
            override fun shouldOverrideUrlLoading(v: WebView, req: WebResourceRequest) =
                req.url.host != WebViewAssetLoader.DEFAULT_DOMAIN   // never navigate away
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                v: WebView, cb: ValueCallback<Array<Uri>>, params: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = cb
                return try { chooser.launch(params.createIntent()); true }
                catch (e: Exception) { fileCallback = null; false }
            }
        }
        // Back: let the page close fullscreen first, otherwise leave the app as usual.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("!!(window.fs1&&fs1.handleBack&&fs1.handleBack())") { r ->
                    if (r != "true") { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
                }
            }
        })
        web.addJavascriptInterface(Bridge(), "Android")
        web.loadUrl("https://${WebViewAssetLoader.DEFAULT_DOMAIN}/assets/editor.html")
    }

    override fun onDestroy() {
        link.close(); super.onDestroy()
    }

    // ------------------------------------------------------------------ JS bridge (page -> Kotlin)
    inner class Bridge {
        @JavascriptInterface fun setImmersive(on: Boolean) = runOnUiThread {
            val c = WindowCompat.getInsetsController(window, window.decorView)
            if (on) {
                c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                c.hide(WindowInsetsCompat.Type.systemBars())
            } else c.show(WindowInsetsCompat.Type.systemBars())
        }

        @JavascriptInterface fun pageReady() { status("idle", "Not connected.") }

        @JavascriptInterface fun listTemplates(): String {
            val arr = JSONArray()
            val bundled = (assets.list("templates") ?: emptyArray()).filter { it.endsWith(".bin", true) }.sorted()
            bundled.forEach { arr.put(JSONObject().put("name", it).put("user", false)) }
            (userTemplateDir.listFiles() ?: emptyArray()).map { it.name }
                .filter { it.endsWith(".bin", true) && it !in bundled }.sorted()
                .forEach { arr.put(JSONObject().put("name", it).put("user", true)) }
            return arr.toString()
        }

        @JavascriptInterface fun getTemplate(name: String): String {
            if (!validName(name)) return ""
            return try {
                val f = File(userTemplateDir, name)
                val bytes = if (f.exists()) f.readBytes() else assets.open("templates/$name").use { it.readBytes() }
                Base64.encodeToString(bytes, Base64.NO_WRAP)
            } catch (e: Exception) { "" }
        }

        @JavascriptInterface fun saveTemplate(name: String, b64: String): Boolean {
            if (!validName(name)) return false
            return try { File(userTemplateDir, name).writeBytes(Base64.decode(b64, Base64.DEFAULT)); true }
            catch (e: Exception) { false }
        }

        @JavascriptInterface fun saveFile(name: String, b64: String) {
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
            try {
                val cv = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, safe)
                    put(MediaStore.Downloads.MIME_TYPE, if (safe.endsWith(".txt")) "text/plain" else "application/octet-stream")
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download/FS1")
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)!!
                contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
                log("Saved Download/FS1/$safe", "ok"); toast("Saved to Downloads/FS1/$safe")
            } catch (e: Exception) { log("Save failed: ${e.message}", "err") }
        }

        @JavascriptInterface fun connectWatch() = runOnUiThread { withBlePermissions { pickDevice() } }

        @JavascriptInterface fun disconnectWatch() = runOnUiThread { link.close() }

        @JavascriptInterface fun sendToWatch(lines: String) {
            val blocks = try { parseBlocks(lines) } catch (e: Exception) {
                log("Bad write list: ${e.message}", "err"); status("error", "Bad write list."); return
            }
            runOnUiThread {
                if (link.isReady) link.send(blocks)
                else if (link.isConnected) toast("Watch is busy.")
                else {
                    pendingSend = blocks
                    withBlePermissions {
                        log("Connecting to ${savedMac}…", "")
                        connectTo(savedMac)
                    }
                }
            }
        }
    }

    private fun validName(n: String) = Regex("[A-Za-z0-9._ -]{1,64}\\.bin", RegexOption.IGNORE_CASE).matches(n) && !n.contains("..")

    /** Hex-line writes ("<n>\t<hex>" or bare hex) -> list of [header, frames...]. AT lines are ignored. */
    private fun parseBlocks(text: String): List<List<ByteArray>> {
        val magic = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0x42, 0x47)
        val blocks = ArrayList<MutableList<ByteArray>>()
        for (raw in text.lineSequence()) {
            val hex = raw.substringAfter('\t').trim()
            if (hex.isEmpty() || hex.startsWith("AT+")) continue
            val b = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
            if (b.size == 20 && b.copyOfRange(0, 4).contentEquals(magic)) blocks.add(mutableListOf(b))
            else if (blocks.isNotEmpty()) blocks.last().add(b)
        }
        require(blocks.isNotEmpty()) { "no BG headers found" }
        return blocks
    }

    // ------------------------------------------------------------------ permissions + device picker
    private fun withBlePermissions(action: () -> Unit) {
        if (Build.VERSION.SDK_INT < 31 ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) action()
        else { afterPermissions = action; permLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT)) }
    }

    private val defaultMac = "B1:82:66:8C:6C:E1"
    private val savedMac get() = prefs.getString("mac", defaultMac) ?: defaultMac

    private fun connectTo(mac: String): Boolean {
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) { toast("Turn Bluetooth on first."); status("error", "Bluetooth is off."); return false }
        val dev = try { adapter.getRemoteDevice(mac) } catch (e: IllegalArgumentException) {
            log("Bad MAC address: $mac", "err"); status("error", "Bad MAC address."); return false
        }
        link.connect(dev); return true
    }

    /** Dialog to view/change the watch MAC, then connect. */
    private fun pickDevice() {
        val input = android.widget.EditText(this).apply {
            setText(savedMac); setSingleLine()
            setTextColor(0xFFECE8F5.toInt()); setHintTextColor(0xFF6A6388.toInt())
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF8E73F0.toInt())
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(this)
            .setTitle("Watch MAC address")
            .setView(input)
            .setPositiveButton("Connect") { _, _ ->
                val mac = input.text.toString().trim().uppercase()
                prefs.edit().putString("mac", mac).apply()
                connectTo(mac)
            }
            .setNegativeButton("Cancel") { _, _ -> pendingSend = null }
            .show()
    }

    // ------------------------------------------------------------------ WatchLink.Listener (Kotlin -> page)
    override fun log(msg: String, cls: String) = js("fs1.onLog(${JSONObject.quote(msg)},${JSONObject.quote(cls)})")
    override fun progress(done: Int, total: Int) = js("fs1.onProgress($done,$total)")
    override fun status(state: String, text: String) {
        js("fs1.onStatus(${JSONObject.quote(state)},${JSONObject.quote(text)})")
        runOnUiThread {
            if (state == "idle" || state == "error") pendingSend = null
            if (state == "sending") window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
    override fun ready() {
        val p = pendingSend; pendingSend = null
        if (p != null) runOnUiThread { link.send(p) }
    }

    private fun js(code: String) = runOnUiThread { web.evaluateJavascript("try{$code}catch(e){}", null) }
    private fun toast(m: String) = runOnUiThread { Toast.makeText(this, m, Toast.LENGTH_SHORT).show() }
}
