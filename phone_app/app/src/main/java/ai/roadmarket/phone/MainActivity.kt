package ai.roadmarket.phone

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Environment
import android.provider.MediaStore
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.window.OnBackInvokedDispatcher
import org.json.JSONTokener

/** 서버의 /phone 페이지를 그대로 띄우는 화면이에요. */
class MainActivity : Activity() {

    private val home = BuildConfig.SERVER_URL + "/phone"
    private val serverHost: String? = Uri.parse(BuildConfig.SERVER_URL).host

    private lateinit var root: FrameLayout
    private lateinit var web: WebView
    private lateinit var offline: LinearLayout

    // 사진 고르기: 페이지에 돌려줄 콜백과, 카메라가 찍은 사진을 담을 자리
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var cameraUri: Uri? = null

    // 주 화면을 못 불러왔을 때 다시 열 주소
    private var failedUrl: String? = null

    // 로그인 표를 앱 저장소로 옮겨 두는 곳이에요. 뒤에서 알림 서비스가 이 표로 서버에 물어요.
    private lateinit var store: Store
    private val main = Handler(Looper.getMainLooper())
    private val syncTick = object : Runnable {
        override fun run() {
            syncToken()
            main.postDelayed(this, SYNC_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 앱이 내려갔다 살아났으면, 찍다 만 빈 사진 자리를 기억해 뒀다가 지워요.
        cameraUri = savedInstanceState?.getString(KEY_CAMERA)?.let(Uri::parse)
        store = Store(this)

        root = FrameLayout(this).apply { setBackgroundColor(getColor(R.color.page_bg)) }
        web = WebView(this).apply { setBackgroundColor(getColor(R.color.page_bg)) }
        offline = buildOffline()
        root.addView(web, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(offline, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        setContentView(root)

        fitInsets()
        setupWeb()
        setupBack()
        web.loadUrl(home)
    }

    // ── 화면 여백 ──────────────────────────────────────────────

    /** 상태 줄·내비게이션 줄·키보드만큼 안쪽 여백을 줘요. 페이지가 가려지지 않게요. */
    private fun fitInsets() {
        // 안드로이드 10은 화면이 바 밑으로 깔리지 않고, 키보드는 adjustResize가 맡아요.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        // 11~14도 15와 똑같이 바 밑까지 깔고 직접 여백을 줘요. 이러면 adjustResize 대신 아래 ime 값으로 키보드를 피해요.
        window.setDecorFitsSystemWindows(false)
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsets.CONSUMED
        }
    }

    // ── WebView ───────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWeb() {
        // 디버그 빌드에서만 맥의 크롬 도구로 페이지를 들여다볼 수 있어요.
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true      // 로그인 표(rm_phone)를 localStorage에 둬요.
            allowFileAccess = false       // 기기 파일을 주소로 열 일은 없어요.
            allowContentAccess = false    // 사진은 고르는 창으로만 받아요.
            setSupportMultipleWindows(false)
        }
        CookieManager.getInstance().setAcceptCookie(true)

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                handleUrl(request.url)

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                if (failedUrl == null) offline.visibility = View.GONE
            }

            override fun onPageFinished(view: WebView, url: String?) {
                // /phone에 닿으면 기록을 비워요. 로그인 뒤 뒤로 가기로 카카오 화면에 돌아가지 않게요.
                if (failedUrl == null && isHome(url)) view.clearHistory()
                syncToken()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) showOffline(request.url.toString())
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                // 인증서가 이상하면 열지 않아요.
                handler.cancel()
                showOffline(error.url)
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams
            ): Boolean = openChooser(callback, params)
        }
    }

    private fun isHome(url: String?): Boolean {
        val u = Uri.parse(url ?: return false)
        return u.host == serverHost && (u.path == "/phone" || u.path == "/phone/")
    }

    /** true를 돌려주면 WebView가 직접 열지 않아요. */
    private fun handleUrl(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme == "http" || scheme == "https") return false
        if (scheme == "intent") {
            val intent = try {
                Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
            } catch (e: Exception) {
                return true
            }
            // 아무 화면이나 열지 못하게 막아요 (카카오 하이브리드 앱 가이드 방식).
            intent.addCategory(Intent.CATEGORY_BROWSABLE)
            intent.component = null
            intent.selector = null
            try {
                startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                // 앱이 없으면 대신 열 주소를 WebView에서 열어요.
                val fallback = intent.getStringExtra("browser_fallback_url")
                val fs = fallback?.let { Uri.parse(it).scheme?.lowercase() }
                if (fs == "http" || fs == "https") web.loadUrl(fallback!!)
            }
            return true
        }
        // kakaotalk://, market:// 같은 주소는 맞는 앱에 넘기고, 없으면 조용히 넘어가요.
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (e: ActivityNotFoundException) {
        }
        return true
    }

    // ── 연결 안 됨 화면 ─────────────────────────────────────────

    private fun buildOffline(): LinearLayout {
        val text = getColor(R.color.page_text)
        val msg = TextView(this).apply {
            setText(R.string.offline)
            setTextColor(text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            gravity = Gravity.CENTER
        }
        val retry = TextView(this).apply {
            setText(R.string.retry)
            setTextColor(getColor(R.color.button_text))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(24), 0, dp(24), 0)
            background = GradientDrawable().apply {
                cornerRadius = dp(19).toFloat()
                setColor(getColor(R.color.button_bg))
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { retry() }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(getColor(R.color.page_bg))
            isClickable = true   // 뒤의 WebView가 눌리지 않게 막아요.
            visibility = View.GONE
            addView(msg, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            addView(retry, LinearLayout.LayoutParams(WRAP_CONTENT, dp(48)).apply { topMargin = dp(20) })
        }
    }

    private fun showOffline(url: String?) {
        failedUrl = url ?: home
        offline.visibility = View.VISIBLE
    }

    private fun retry() {
        val url = failedUrl ?: home
        failedUrl = null
        offline.visibility = View.GONE
        web.loadUrl(url)
    }

    // ── 사진 찍기·고르기 ────────────────────────────────────────

    /** 기본 동작은 요청을 다 취소해서 직접 열어요. 콜백에는 꼭 한 번 답해야 다음에도 버튼이 눌려요. */
    private fun openChooser(callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams): Boolean {
        // 앞선 요청이 남아 있으면 먼저 닫아요.
        fileCallback?.onReceiveValue(null)
        fileCallback = callback
        deleteCameraUri()

        val type = params.acceptTypes?.firstOrNull { it.isNotBlank() } ?: "image/*"
        val pick = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            setType(type)
            if (params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
        }
        val chooser = Intent.createChooser(pick, getString(R.string.pick_photo))
        if (type.startsWith("image/")) cameraIntent()?.let {
            chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(it))
        }
        return try {
            @Suppress("DEPRECATION")
            startActivityForResult(chooser, REQ_FILE)
            true
        } catch (e: ActivityNotFoundException) {
            finishChooser(null)
            true
        }
    }

    /** 카메라 앱이 있으면, 사진을 담을 빈 자리를 MediaStore에 만들어 넘겨요. */
    private fun cameraIntent(): Intent? {
        val capture = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        if (capture.resolveActivity(packageManager) == null) return null
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "roadmarket_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/RoadMarket")
        }
        val uri = try {
            contentResolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
        } catch (e: Exception) {
            null
        } ?: return null
        cameraUri = uri
        return capture.apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            clipData = ClipData.newRawUri("", uri)   // 고르는 창을 거쳐도 쓰기 허락이 넘어가게요.
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    @Deprecated("Activity 기본 API를 그대로 써요.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQ_FILE) {
            @Suppress("DEPRECATION")
            super.onActivityResult(requestCode, resultCode, data)
            return
        }
        if (resultCode != RESULT_OK) {
            finishChooser(null)
            return
        }
        // 고른 사진
        val picked = mutableListOf<Uri>()
        data?.clipData?.let { clip -> for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let(picked::add) }
        if (picked.isEmpty()) data?.data?.let(picked::add)
        val cam = cameraUri
        if (cam != null) picked.remove(cam)   // 카메라 앱이 담을 자리를 그대로 돌려주기도 해요.
        if (picked.isNotEmpty()) {
            finishChooser(picked.toTypedArray())
            return
        }
        // 찍은 사진
        if (cam != null && hasBytes(cam)) {
            cameraUri = null   // 찍은 사진은 지우지 않아요.
            finishChooser(arrayOf(cam))
            return
        }
        finishChooser(null)
    }

    /** 페이지에 답하고, 쓰지 않은 빈 사진 자리는 지워요. */
    private fun finishChooser(result: Array<Uri>?) {
        fileCallback?.onReceiveValue(result)
        fileCallback = null
        deleteCameraUri()
    }

    private fun deleteCameraUri() {
        val uri = cameraUri ?: return
        cameraUri = null
        try {
            contentResolver.delete(uri, null, null)
        } catch (e: Exception) {
        }
    }

    private fun hasBytes(uri: Uri): Boolean = try {
        (contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L) > 0L
    } catch (e: Exception) {
        false
    }

    // ── 뒤로 가기 ──────────────────────────────────────────────

    /** 뒤로 갈 곳이 있으면 뒤로, 없으면 앱을 닫아요. */
    private fun goBack() {
        if (!web.canGoBack()) return finish()
        // 연결 안 됨 화면이 떠 있었으면 닫고 앞 페이지로 가요.
        failedUrl = null
        offline.visibility = View.GONE
        web.goBack()
    }

    private fun setupBack() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { goBack() }
        }
    }

    @Deprecated("안드로이드 12 이하에서만 불려요.")
    override fun onBackPressed() {
        goBack()
    }

    // ── 생명 주기 ──────────────────────────────────────────────

    override fun onPause() {
        super.onPause()
        WatchService.appVisible = false
        main.removeCallbacks(syncTick)
        syncToken()   // 뒤로 가기 직전의 표를 한 번 더 옮겨요.
        web.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
        WatchService.appVisible = true
        // 서비스는 앱이 앞에 있을 때만 켤 수 있어요 (안드로이드 12+).
        if (store.token.isNotEmpty()) WatchService.start(this)
        main.removeCallbacks(syncTick)
        main.postDelayed(syncTick, SYNC_MS)
    }

    // ── 로그인 표 옮기기 ────────────────────────────────────────

    /** 페이지의 localStorage에서 표(rm_phone)를 읽어 앱 저장소에 둬요. 표는 로그에 찍지 않아요. */
    private fun syncToken() {
        // /phone 페이지일 때만 읽어요. 카카오 화면 같은 다른 곳의 localStorage는 비어 있어서 로그아웃으로 잘못 봐요.
        if (failedUrl != null || !isHome(web.url)) return
        web.evaluateJavascript(READ_TOKEN) { v ->
            if (isDestroyed || failedUrl != null || !isHome(web.url)) return@evaluateJavascript
            val t = try { JSONTokener(v ?: "").nextValue() as? String } catch (e: Exception) { null } ?: ""
            onToken(t)
        }
    }

    private fun onToken(t: String) {
        if (t.isEmpty()) {
            // 로그아웃: 저장소를 비우고 서비스도 멈춰요.
            if (store.token.isNotEmpty()) store.clearToken()
            WatchService.stop(this)
            return
        }
        if (t == store.deadToken) return   // 서버가 이미 거절한 표예요. 페이지가 곧 로그아웃해요.
        if (t != store.token) store.token = t
        askNotifyOnce()
        if (WatchService.appVisible) WatchService.start(this)
    }

    /** 로그인된 뒤 한 번만 알림 허용을 물어요. 거절해도 다시 조르지 않아요. */
    private fun askNotifyOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || store.askedNotify) return
        if (!WatchService.appVisible) return
        store.askedNotify = true
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        cameraUri?.let { outState.putString(KEY_CAMERA, it.toString()) }
    }

    override fun onDestroy() {
        main.removeCallbacks(syncTick)
        // 답하지 못한 요청은 닫고 가요.
        fileCallback?.onReceiveValue(null)
        fileCallback = null
        root.removeView(web)
        web.destroy()
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val REQ_FILE = 1
        const val REQ_NOTIFY = 2
        // 화면이 보이는 동안 표를 옮기는 간격이에요. 페이지가 묻는 간격(3초)에 맞춘 임의로 정한 값이에요.
        const val SYNC_MS = 3_000L
        const val READ_TOKEN = "(function(){try{return localStorage.getItem('rm_phone')||''}catch(e){return ''}})()"
        const val KEY_CAMERA = "camera_uri"
    }
}
