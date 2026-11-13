package app.planetarium.studio

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * The AI studio: the spatial wallpaper editor's page, laid out for a phone, in a WebView. The
 * page is served by [StudioServer] on a loopback address, so it behaves as it does on a
 * computer; "export" adds the wallpaper to the library and makes it the one in use.
 */
class StudioActivity : ComponentActivity() {
    private lateinit var web: WebView
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    private val pickFiles = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        fileCallback?.onReceiveValue(uris.toTypedArray())
        fileCallback = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // A drawing takes a few minutes; the connection must not be cut by the screen turning off.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) WebView.setWebContentsDebuggingEnabled(true)

        val origin = StudioServer.start(this)
        web = WebView(this).apply {
            setBackgroundColor(BACKGROUND)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.textZoom = 100
            webViewClient = object : WebViewClient() {
                // The WebView shows the editor only; any other address goes to the browser.
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (request.url.toString().startsWith(origin)) return false
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, request.url))
                    } catch (e: Exception) {
                        Toast.makeText(this@StudioActivity, "沒有可以開啟連結的瀏覽器", Toast.LENGTH_LONG).show()
                    }
                    return true
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                    fileCallback?.onReceiveValue(null)
                    fileCallback = callback
                    val types = params.acceptTypes.filter { it.isNotBlank() }
                    val mime = when {
                        types.isNotEmpty() && types.all { it.startsWith("image/") } -> "image/*"
                        else -> "*/*"
                    }
                    return try {
                        pickFiles.launch(mime)
                        true
                    } catch (e: Exception) {
                        fileCallback = null
                        Toast.makeText(this@StudioActivity, "此裝置沒有可用的檔案選擇器", Toast.LENGTH_LONG).show()
                        false
                    }
                }
            }
        }

        // Keeps the page clear of the status bar, the navigation bar and the keyboard.
        val frame = FrameLayout(this).apply {
            setBackgroundColor(BACKGROUND)
            addView(web, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        ViewCompat.setOnApplyWindowInsetsListener(frame) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime(),
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        setContentView(frame)

        web.loadUrl(pageUrl(origin, intent))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Coming back from the sign-in page changes nothing; being asked to open another
        // wallpaper loads it.
        if (intent.hasExtra(EXTRA_PACK)) web.loadUrl(pageUrl(StudioServer.start(this), intent))
    }

    private fun pageUrl(origin: String, intent: Intent): String {
        val pack = intent.getStringExtra(EXTRA_PACK)
        return if (pack.isNullOrEmpty()) origin else origin + "?pack=" + Uri.encode(pack)
    }

    override fun onDestroy() {
        fileCallback?.onReceiveValue(null)
        web.destroy()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_PACK = "pack"
        private val BACKGROUND = Color.rgb(0x14, 0x15, 0x1a)

        /** Opens the studio, empty or (with [packId]) on a wallpaper from the library. */
        fun open(context: Context, packId: String? = null) {
            val intent = Intent(context, StudioActivity::class.java)
            if (packId != null) intent.putExtra(EXTRA_PACK, packId)
            context.startActivity(intent)
        }
    }
}
