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
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.window.OnBackInvokedDispatcher
import org.json.JSONTokener
import java.io.IOException

/** 서버 주소의 /phone 페이지를 띄우는 화면이에요. 페이지 파일은 앱 안의 것을 써요. */
class MainActivity : Activity() {

    private val home = BuildConfig.SERVER_URL + "/phone"
    private val server: Uri = Uri.parse(BuildConfig.SERVER_URL)
    private val serverHost: String? = server.host

    private lateinit var root: FrameLayout
    private lateinit var web: WebView
    private lateinit var offline: LinearLayout

    // 사진 고르기: 페이지에 돌려줄 콜백과, 카메라가 찍은 사진을 담을 자리
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var cameraUri: Uri? = null

    // 주 화면을 못 불러왔을 때 다시 열 주소
    private var failedUrl: String? = null

    // 새 페이지를 여는 중인지예요. 기록 비우기를 진짜 새로 열 때만 하려고 둬요.
    private var freshLoad = false

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
        // 안드로이드 11 이하는 시작 화면을 창 바탕으로 그려요. 화면이 뜨면 페이지 바탕색으로 바꿔요.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) window.setBackgroundDrawableResource(R.color.page_bg)

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
            // 페이지가 앱 안인지 알 수 있게 뒤에만 덧붙여요. 앞부분은 카카오가 WebView를 알아보는 데 써요.
            userAgentString = "$userAgentString $UA_TAG"
        }
        CookieManager.getInstance().setAcceptCookie(true)
        // 페이지가 window.RoadMarketApp.askNotify()로 알림 허용 창을 부를 수 있어요. 우리 서버의 /phone에서만 들어줘요.
        web.addJavascriptInterface(Bridge(), "RoadMarketApp")

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // 서버가 /phone으로 돌려보내면(로그인 뒤 /phone#t=…) 새로 열어요. 그래야 앱 안의 페이지로 가로채져요.
                if (BuildConfig.LOCAL_PAGE && request.isForMainFrame && request.isRedirect &&
                    isLocalTarget(request.url) && hasLocalPage()
                ) {
                    view.loadUrl(request.url.toString())
                    return true
                }
                return handleUrl(request.url)
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (!BuildConfig.LOCAL_PAGE || !request.isForMainFrame) return null
                if (!request.method.equals("GET", ignoreCase = true) || !isLocalTarget(request.url)) return null
                return localPage()
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                if (failedUrl == null) offline.visibility = View.GONE
                freshLoad = true   // 진짜로 새 페이지를 여는 때만 불려요. 페이지 안의 기록 쌓기(pushState)에는 안 불려요.
            }

            override fun onPageFinished(view: WebView, url: String?) {
                // /phone에 닿으면 기록을 비워요. 로그인 뒤 뒤로 가기로 카카오 화면에 돌아가지 않게요.
                // 페이지가 덮는 화면을 열며 쌓은 기록(pushState)에도 이 함수가 불려요. 그때 비우면 화면이 안 닫혀요.
                if (freshLoad && failedUrl == null && isHome(url)) view.clearHistory()
                freshLoad = false
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

    /** 서버와 같은 주소(scheme·호스트·포트)의 /phone 인지 봐요. 그 밖의 /phone/state 같은 요청은 서버로 가요. */
    private fun isLocalTarget(u: Uri): Boolean =
        u.scheme.equals(server.scheme, ignoreCase = true) && u.host == serverHost && u.port == server.port &&
            (u.path == "/phone" || u.path == "/phone/")

    private fun hasLocalPage(): Boolean = try {
        assets.open(LOCAL_ASSET).close(); true
    } catch (e: IOException) {
        false
    }

    /** 앱 안의 페이지를 내줘요. 파일이 없거나 못 읽으면 null을 돌려 서버 것을 쓰게 해요. */
    private fun localPage(): WebResourceResponse? {
        val stream = try { assets.open(LOCAL_ASSET) } catch (e: IOException) { return null }
        val headers = mapOf(
            "Cache-Control" to "no-store, no-cache, must-revalidate",
            "Pragma" to "no-cache",
            "Expires" to "0",
        )
        return WebResourceResponse("text/html", "utf-8", 200, "OK", headers, stream)
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
        // 우리 페이지가 떠 있으면 먼저 페이지에 물어요. 덮는 화면이 열려 있으면 그것만 닫아요.
        if (failedUrl == null && isHome(web.url)) {
            web.evaluateJavascript("(function(){try{return !!(window.__rmBack&&window.__rmBack())}catch(e){return false}})()") { v ->
                if (isDestroyed) return@evaluateJavascript
                if (v != "true") finish()
            }
            return
        }
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
        if (WatchService.appVisible) WatchService.start(this)
    }

    /** 페이지가 부르는 다리예요. 첫 물건을 올린 뒤처럼 알림이 쓸모 있을 때 페이지가 물어 달라고 해요. */
    private inner class Bridge {
        @android.webkit.JavascriptInterface
        fun askNotify() {
            runOnUiThread { if (!isDestroyed && failedUrl == null && isHome(web.url)) askNotifyOnce() }
        }

        /** 알림이 이미 허용돼 있는지예요. 페이지가 「알림 받기」 줄을 보일지 정할 때 써요. */
        @android.webkit.JavascriptInterface
        fun notifyOn(): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 사람이 「알림 받기」를 눌렀을 때 불려요. 처음이면 안드로이드 허용 창을 띄워요.
     * 이미 한 번 물었는데 다시 물을 수 없는 상태면(거절함) 이 앱의 알림 설정 화면을 열어요.
     */
    private fun askNotifyOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val perm = android.Manifest.permission.POST_NOTIFICATIONS
        if (checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED) return
        if (store.askedNotify && !shouldShowRequestPermissionRationale(perm)) {
            try {
                startActivity(
                    Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)
                )
            } catch (e: Exception) {
            }
            return
        }
        store.askedNotify = true
        requestPermissions(arrayOf(perm), REQ_NOTIFY)
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
        const val LOCAL_ASSET = "phone.html"
        const val UA_TAG = "RoadMarketApp/1.0"
    }
}
