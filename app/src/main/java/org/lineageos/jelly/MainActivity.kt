/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly

import android.app.Activity
import android.app.ActivityManager.TaskDescription
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Icon
import android.net.Uri
import android.net.http.HttpResponseCache
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ResultReceiver
import android.print.PrintAttributes
import android.print.PrintManager
import android.text.TextUtils
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.MimeTypeMap
import android.webkit.WebChromeClient.CustomViewCallback
import android.webkit.WebView
import android.widget.LinearLayout
import android.widget.PopupWindow
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.graphics.toColorInt
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.PreferenceManager
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.lineageos.jelly.ext.buildShareIntent
import org.lineageos.jelly.favorite.FavoriteActivity
import org.lineageos.jelly.history.HistoryActivity
import org.lineageos.jelly.models.PwaManifest
import org.lineageos.jelly.models.WebShare
import org.lineageos.jelly.shortcut.BackgroundShortcut
import org.lineageos.jelly.shortcut.BackgroundShortcutActivity
import org.lineageos.jelly.shortcut.BackgroundShortcutService
import org.lineageos.jelly.ui.CursorOverlay
import org.lineageos.jelly.ui.MenuDialog
import org.lineageos.jelly.ui.UrlBarLayout
import org.lineageos.jelly.utils.AdBlock
import org.lineageos.jelly.utils.IntentUtils
import org.lineageos.jelly.utils.PermissionsUtils
import org.lineageos.jelly.utils.SharedPreferencesExt
import org.lineageos.jelly.utils.TabUtils.openInNewTab
import org.lineageos.jelly.utils.UiUtils
import org.lineageos.jelly.utils.UrlUtils
import org.lineageos.jelly.viewmodels.FavoriteViewModel
import org.lineageos.jelly.viewmodels.HistoryViewModel
import org.lineageos.jelly.viewmodels.SuggestionProviderViewModel
import org.lineageos.jelly.webview.WebViewExt
import org.lineageos.jelly.webview.WebViewExtActivity
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.reflect.cast

class MainActivity : WebViewExtActivity(), SharedPreferences.OnSharedPreferenceChangeListener {
    // View model
    private val historyViewModel: HistoryViewModel by viewModels()
    private val favoritesViewModel: FavoriteViewModel by viewModels()
    private val suggestionProviderViewModel: SuggestionProviderViewModel by viewModels()

    // Views
    private val appBarLayout by lazy { findViewById<AppBarLayout>(R.id.appBarLayout) }
    private val constraintLayout by lazy { findViewById<ConstraintLayout>(R.id.constraintLayout) }
    private val toolbar by lazy { findViewById<MaterialToolbar>(R.id.toolbar) }
    private val urlBarLayout by lazy { findViewById<UrlBarLayout>(R.id.urlBarLayout) }

    private lateinit var webView: WebViewExt
    private val cursorOverlay by lazy { findViewById<CursorOverlay>(R.id.cursorOverlay) }
    private var shortcutId: String? = null
    private var shortcutName: String? = null
    private var backgroundShortcutId: String? = null
    private var backgroundShortcutServiceConnected: Boolean = false
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            backgroundShortcutServiceConnected = true
            val binder = BackgroundShortcutService.ServiceBinder::class.cast(service)
            val id = backgroundShortcutId!!
            val name = shortcutName ?: id
            val backgroundShortcut = BackgroundShortcut(id, name, true)
            val backgroundShortcutService = binder.getService()
            webView = backgroundShortcutService.getWebView(backgroundShortcut)
            onWebViewResolved()
        }

        override fun onServiceDisconnected(className: ComponentName) {
            backgroundShortcutServiceConnected = false
        }
    }

    private var backgroundShortcutResultFinished: Boolean = true
    private val backgroundShortcutLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            backgroundShortcutResultFinished = true
            if (it.resultCode == RESULT_OK) finish()
            else prepareWebView()
        }

    private val shortcutManager by lazy { getSystemService(ShortcutManager::class.java) }

    private val fileRequest =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
            fileRequestCallback.invoke(it)
        }
    private lateinit var fileRequestCallback: ((data: List<Uri>) -> Unit)

    private var pwaManifest: PwaManifest? = null
    private var webRequestPermissions: ((granted: Array<String>) -> Unit)? = null
    private var webRequestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            val granted = ArrayList<String>()
            it.entries.forEach { permission ->
                if (permission.value) granted.add(permission.key)
            }
            webRequestPermissions?.invoke(granted.toTypedArray())
        }

    override fun launchFileRequest(input: Array<String>) {
        fileRequest.launch(input)
    }

    override fun setFileRequestCallback(cb: (data: List<Uri>) -> Unit) {
        fileRequestCallback = cb
    }

    private val urlResolvedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!intent.hasExtra(Intent.EXTRA_INTENT) ||
                !intent.hasExtra(Intent.EXTRA_RESULT_RECEIVER)
            ) {
                return
            }
            @Suppress("UnsafeIntentLaunch")
            val resolvedIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
            } else {
                @Suppress("Deprecation")
                intent.getParcelableExtra(Intent.EXTRA_INTENT)!!
            }
            if (TextUtils.equals(packageName, resolvedIntent.getPackage())) {
                val url: String = intent.getStringExtra(IntentUtils.EXTRA_URL)!!
                webView.loadUrl(url)
            } else {
                startActivity(resolvedIntent)
            }
            val receiver = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(
                    Intent.EXTRA_RESULT_RECEIVER,
                    ResultReceiver::class.java
                )!!
            } else {
                @Suppress("Deprecation")
                intent.getParcelableExtra(Intent.EXTRA_RESULT_RECEIVER)!!
            }
            receiver.send(Activity.RESULT_CANCELED, Bundle())
        }
    }
    private var urlIcon: Bitmap? = null
    private var url: String? = null
    private var desktopMode = false
    private var incognito = false
    private var isFullscreenPwa = false
    private var customView: View? = null
    private var fullScreenCallback: CustomViewCallback? = null
    private lateinit var menuDialog: MenuDialog

    private val sharedPreferencesExt by lazy { SharedPreferencesExt(this) }

    private val permissionsUtils by lazy { PermissionsUtils(this) }

    private lateinit var locationDialogCallback: (() -> Unit)
    private val locationPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (it.isNotEmpty()) {
            if (permissionsUtils.locationPermissionsGranted()) {
                // Precise or approximate location access granted.
                locationDialogCallback()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WebView.setWebContentsDebuggingEnabled(sharedPreferencesExt.webDebuggingEnabled)
        AdBlock.init(this)
        if (AdBlock.enabled(this)) {
            AdBlock.ensureFresh(this)
        }
        setContentView(R.layout.activity_main)
        cursorOverlay.iconCallback = {
            updateCursorIcon(cursorOverlay.cursorX, cursorOverlay.cursorY)
        }
        cursorOverlay.isVisible = cursorUiEnabled()
        setSupportActionBar(toolbar)
        shortcutId = intent.getStringExtra(IntentUtils.EXTRA_SHORTCUT_ID)
        shortcutName = intent.getStringExtra(IntentUtils.EXTRA_SHORTCUT_NAME)
        url = when (intent.getBooleanExtra(IntentUtils.EXTRA_IGNORE_DATA, false)) {
            true -> intent.getStringExtra(IntentUtils.EXTRA_PAGE_URL)
            false -> intent.dataString
        }
        desktopMode = sharedPreferencesExt.desktopShortcuts.contains(shortcutId)
        incognito = intent.getBooleanExtra(IntentUtils.EXTRA_INCOGNITO, false)
        isFullscreenPwa = when {
            sharedPreferencesExt.fullscreenShortcutsEnabled && !shortcutId.isNullOrEmpty() -> true
            else -> intent.getStringExtra(MANIFEST_DISPLAY)?.let {
                ALLOWED_FULLSCREEN_PWA_VALUES.contains(it)
            } ?: false
        }

        // Restore from previous instance
        savedInstanceState?.let {
            url = url?.takeIf { url ->
                url.isNotEmpty()
            } ?: it.getString(IntentUtils.EXTRA_URL, null)
            desktopMode = it.getBoolean(IntentUtils.EXTRA_DESKTOP_MODE, desktopMode)
            incognito = it.getBoolean(IntentUtils.EXTRA_INCOGNITO, incognito)
            isFullscreenPwa = it.getBoolean(IntentUtils.EXTRA_FULLSCREEN_PWA, isFullscreenPwa)
        }

        // Make sure prefs are set before loading them
        PreferenceManager.setDefaultValues(this, R.xml.settings, false)
        val preferenceManager = PreferenceManager.getDefaultSharedPreferences(this)
        preferenceManager.registerOnSharedPreferenceChangeListener(this)

        // Register app shortcuts
        registerShortcuts()

        urlBarLayout.isIncognito = incognito

        menuDialog = MenuDialog(this, desktopMode) { option: MenuDialog.Option ->
            when (option) {
                MenuDialog.Option.BACK -> webView.goBack()
                MenuDialog.Option.FORWARD -> webView.goForward()
                MenuDialog.Option.NEW_TAB -> openInNewTab(this, null, false)
                MenuDialog.Option.NEW_PRIVATE_TAB -> openInNewTab(this, null, true)
                MenuDialog.Option.REFRESH -> webView.reload()
                MenuDialog.Option.ADD_TO_FAVORITE ->
                    webView.title?.let { title ->
                        webView.url?.let { url ->
                            setAsFavorite(title, url)
                        }
                    }

                MenuDialog.Option.SHARE -> {
                    // Delay a bit to allow popup menu hide animation to play
                    Handler(Looper.getMainLooper()).postDelayed({
                        webView.url?.let { url -> shareUrl(url) }
                    }, 300)
                }

                MenuDialog.Option.FIND_IN_PAGE -> {
                    // Run the search setup
                    showSearch()
                }

                MenuDialog.Option.FAVORITES -> startActivity(
                    Intent(
                        this,
                        FavoriteActivity::class.java
                    )
                )

                MenuDialog.Option.HISTORY -> startActivity(
                    Intent(
                        this,
                        HistoryActivity::class.java
                    )
                )

                MenuDialog.Option.DOWNLOADS -> startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
                MenuDialog.Option.ADD_TO_HOME_SCREEN -> addShortcut()
                MenuDialog.Option.PRINT -> {
                    val printManager = getSystemService(PrintManager::class.java)
                    val documentName = "Jelly document"
                    val printAdapter = webView.createPrintDocumentAdapter(documentName)
                    printManager.print(
                        documentName, printAdapter,
                        PrintAttributes.Builder().build()
                    )
                }

                MenuDialog.Option.DESKTOP_VIEW -> {
                    desktopMode = !desktopMode
                    webView.isDesktopMode = desktopMode
                    menuDialog.isDesktopMode = desktopMode
                }

                MenuDialog.Option.DESKTOP_SHORTCUTS -> Intent(
                    this,
                    BackgroundShortcutActivity::class.java
                ).apply {
                    putExtra(BackgroundShortcutActivity.DESKTOP_SHORTCUTS, true)
                    startActivity(this)
                }

                MenuDialog.Option.BACKGROUND_SHORTCUTS -> backgroundShortcutLauncher.launch(
                    Intent(
                        this,
                        BackgroundShortcutActivity::class.java
                    ).apply {
                        backgroundShortcutResultFinished = false
                        putExtra(IntentUtils.EXTRA_SHORTCUT_ID, shortcutId)
                    }
                )

                MenuDialog.Option.SETTINGS -> startActivity(
                    Intent(
                        this,
                        SettingsActivity::class.java
                    )
                )
            }
            menuDialog.dismiss()
        }
        urlBarLayout.onMoreButtonClickCallback = {
            UiUtils.hideKeyboard(window, urlBarLayout)
            menuDialog.showAsDropdownMenu(urlBarLayout, sharedPreferencesExt.reachModeEnabled)
        }

        CookieManager.getInstance()
            .setAcceptCookie(!incognito && sharedPreferencesExt.cookiesEnabled)

        try {
            val httpCacheDir = File(cacheDir, "suggestion_responses")
            val httpCacheSize = 1024 * 1024.toLong() // 1 MiB
            HttpResponseCache.install(httpCacheDir, httpCacheSize)
        } catch (e: IOException) {
            Log.i(TAG, "HTTP response cache installation failed:$e")
        }

        onBackPressedDispatcher.addCallback(object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    crossOpen -> closeCross()

                    wheelMode -> exitWheelMode()

                    urlBarLayout.currentMode == UrlBarLayout.UrlBarMode.SEARCH -> {
                        urlBarLayout.currentMode = UrlBarLayout.UrlBarMode.URL
                    }

                    customView != null -> {
                        onHideCustomView()
                    }

                    webView.canGoBack() -> {
                        webView.goBack()
                    }

                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            }
        })

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                suggestionProviderViewModel.suggestionProvider().collectLatest {
                    urlBarLayout.setSuggestionsProvider(it)
                }
            }
        }

        appBarLayout.isVisible = !isFullscreenPwa
        intent.extras?.let {
            it.getString(MANIFEST_THEME_COLOR)?.let { themeColor ->
                setStatusBarColor(themeColor)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                urlResolvedReceiver, IntentFilter(IntentUtils.EVENT_URL_RESOLVED),
                RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(urlResolvedReceiver, IntentFilter(IntentUtils.EVENT_URL_RESOLVED))
        }
        if (backgroundShortcutResultFinished) prepareWebView()
    }

    override fun onStop() {
        CookieManager.getInstance().flush()
        if (backgroundShortcutServiceConnected) unbindService(serviceConnection)
        unregisterReceiver(urlResolvedReceiver)
        HttpResponseCache.getInstalled().flush()
        super.onStop()
    }

    public override fun onPause() {
        if (backgroundShortcutId == null) webView.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        cursorOverlay.isVisible = cursorUiEnabled() && !realPointerActive
        if (cursorOverlay.isVisible) scheduleCursorHide()
        if (backgroundShortcutId == null) webView.onResume()
        CookieManager.getInstance()
            .setAcceptCookie(!incognito && sharedPreferencesExt.cookiesEnabled)
        if (sharedPreferencesExt.lookLockEnabled) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    public override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        // Preserve webView status
        outState.putString(IntentUtils.EXTRA_URL, webView.url)
        outState.putBoolean(IntentUtils.EXTRA_INCOGNITO, webView.isIncognito)
        outState.putBoolean(IntentUtils.EXTRA_DESKTOP_MODE, webView.isDesktopMode)

        // Pwa fullscreen status
        outState.putBoolean(IntentUtils.EXTRA_FULLSCREEN_PWA, isFullscreenPwa)
    }

    private fun prepareWebView() {
        val backgroundShortcuts = sharedPreferencesExt.backgroundShortcuts
        val isBackgroundShortcut = backgroundShortcuts.contains(shortcutId)
        backgroundShortcutId = if (isBackgroundShortcut) shortcutId else null
        if (backgroundShortcutId == null) {
            if (::webView.isInitialized) return
            webView = WebViewExt.newInstance(this)
            onWebViewResolved()
        } else {
            Intent(this, BackgroundShortcutService::class.java).let {
                it.action = BackgroundShortcutService.START_FOREGROUND_ACTION
                startForegroundService(it)
                bindService(it, serviceConnection, Context.BIND_AUTO_CREATE)
            }
        }
    }

    private fun onWebViewResolved() {
        if (urlBarLayout.url == null) urlBarLayout.url = webView.url
        webView.parent?.let { ViewGroup::class.cast(it).removeView(webView) }
        constraintLayout.addView(webView)
        setUiMode()
        if (webView.initialized) return
        webView.init(this, urlBarLayout, desktopMode, incognito)
        if (url != null || sharedPreferencesExt.homePageAutoload) {
            webView.loadUrl(url ?: sharedPreferencesExt.homePage)
        }
    }

    private fun registerShortcuts() {
        shortcutManager.dynamicShortcuts = listOf(
            ShortcutInfo.Builder(this, "new_incognito_tab_shortcut")
                .setShortLabel(getString(R.string.shortcut_new_incognito_tab))
                .setLongLabel(getString(R.string.shortcut_new_incognito_tab))
                .setIcon(Icon.createWithResource(this, R.drawable.shortcut_incognito))
                .setIntent(
                    Intent(this, MainActivity::class.java)
                        .putExtra("extra_incognito", true)
                        .setAction(Intent.ACTION_VIEW)
                )
                .build()
        )
    }

    private fun showSearch() {
        urlBarLayout.currentMode = UrlBarLayout.UrlBarMode.SEARCH
    }

    private fun shareUrl(url: String) {
        val intent = Intent(Intent.ACTION_SEND)
        intent.putExtra(Intent.EXTRA_TEXT, url)
        if (sharedPreferencesExt.advancedShareEnabled && url == webView.url) {
            val file = File(cacheDir, System.currentTimeMillis().toString() + ".png")
            try {
                FileOutputStream(file).use { out ->
                    val bm = webView.snap
                    bm.compress(Bitmap.CompressFormat.PNG, 70, out)
                    out.flush()
                    out.close()
                    intent.putExtra(
                        Intent.EXTRA_STREAM,
                        FileProvider.getUriForFile(
                            this,
                            "${application.packageName}.fileprovider",
                            file
                        )
                    )
                    intent.type = "image/png"
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            } catch (e: IOException) {
                Log.e(TAG, "${e.message}", e)
            }
        } else {
            intent.type = "text/plain"
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share_title)))
    }


    private fun setAsFavorite(title: String, url: String) {
        val color = urlIcon?.takeUnless { it.isRecycled }?.let { bitmap ->
            UiUtils.getColor(bitmap, false).takeUnless { it == Color.TRANSPARENT }
        } ?: ContextCompat.getColor(
            this, com.google.android.material.R.color.material_dynamic_primary50
        )
        favoritesViewModel.insert(title, url, color)
        Snackbar.make(
            constraintLayout, getString(R.string.favorite_added),
            Snackbar.LENGTH_LONG
        ).show()
    }

    override fun downloadFileAsk(
        url: String?,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
        contentLength: Long
    ) {
        val fileName = UrlUtils.guessFileName(url, contentDisposition, mimeType)
        AlertDialog.Builder(this)
            .setTitle(R.string.download_title)
            .setMessage(getString(R.string.download_message, fileName))
            .setPositiveButton(
                getString(R.string.download_positive)
            ) { _: DialogInterface?, _: Int -> fetchFile(url, userAgent, fileName) }
            .setNegativeButton(
                getString(R.string.dismiss)
            ) { dialog: DialogInterface, _: Int -> dialog.dismiss() }
            .show()
    }

    private fun fetchFile(url: String?, userAgent: String?, fileName: String) {
        val request = try {
            DownloadManager.Request(Uri.parse(url))
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Cannot download non http or https scheme")
            return
        }

        // Let this downloaded file be scanned by MediaScanner - so that it can
        // show up in Gallery app, for example.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            @Suppress("Deprecation")
            request.allowScanningByMediaScanner()
        }
        request.setNotificationVisibility(
            DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
        )
        request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
        request.setMimeType(
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                MimeTypeMap.getFileExtensionFromUrl(url)
            )
        )
        userAgent?.let {
            request.addRequestHeader("User-Agent", it)
        }
        CookieManager.getInstance().getCookie(url)?.takeUnless { it.isEmpty() }?.let {
            request.addRequestHeader("Cookie", it)
        }
        getSystemService(DownloadManager::class.java).enqueue(request)
    }

    override fun showSheetMenu(url: String, shouldAllowDownload: Boolean) {
        val sheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.sheet_actions, LinearLayout(this))
        val tabLayout = view.findViewById<View>(R.id.sheetNewTabLayout)
        val shareLayout = view.findViewById<View>(R.id.sheetShareLayout)
        val favouriteLayout = view.findViewById<View>(R.id.sheetFavouriteLayout)
        val downloadLayout = view.findViewById<View>(R.id.sheetDownloadLayout)
        tabLayout.setOnClickListener {
            openInNewTab(this, url, incognito)
            sheet.dismiss()
        }
        shareLayout.setOnClickListener {
            shareUrl(url)
            sheet.dismiss()
        }
        favouriteLayout.setOnClickListener {
            setAsFavorite(url, url)
            sheet.dismiss()
        }
        if (shouldAllowDownload) {
            downloadLayout.setOnClickListener {
                downloadFileAsk(url, webView.settings.userAgentString, null, null, 0)
                sheet.dismiss()
            }
            downloadLayout.visibility = View.VISIBLE
        }
        sheet.setContentView(view)
        sheet.show()
    }

    /*
     * This is called only if GeolocationPermissions doesn't have an explicit entry for @origin
     */
    override fun showLocationDialog(origin: String, callback: GeolocationPermissions.Callback) {
        locationDialogCallback = {
            AlertDialog.Builder(this)
                .setTitle(R.string.location_dialog_title)
                .setMessage(getString(R.string.location_dialog_message, origin))
                .setPositiveButton(R.string.location_dialog_allow) { _, _ ->
                    callback(origin, true, true)
                }
                .setNegativeButton(R.string.location_dialog_block) { _, _ ->
                    callback(origin, false, true)
                }
                .setNeutralButton(R.string.location_dialog_cancel) { _, _ ->
                    callback(origin, false, false)
                }
                .setOnCancelListener {
                    callback(origin, false, false)
                }
                .create()
                .show()
        }

        if (!permissionsUtils.locationPermissionsGranted()) {
            locationPermissionRequest.launch(PermissionsUtils.locationPermissions)
        } else {
            locationDialogCallback()
        }
    }

    override fun onFaviconLoaded(favicon: Bitmap?) {
        favicon?.let {
            if (it.isRecycled) {
                return
            }
            urlIcon = it.config?.let { config ->
                it.copy(config, true)
            }
            updateTaskDescription()
            if (!it.isRecycled) {
                it.recycle()
            }
        }
    }

    private fun updateTaskDescription() {
        setTaskDescription(
            @Suppress("Deprecation")
            TaskDescription(
                webView.title,
                urlIcon, Color.WHITE
            )
        )
    }

    override fun onShowCustomView(view: View?, callback: CustomViewCallback) {
        customView?.let {
            callback.onCustomViewHidden()
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        customView = view
        fullScreenCallback = callback
        setImmersiveMode(true)
        customView?.setBackgroundColor(ContextCompat.getColor(this, android.R.color.black))
        addContentView(
            customView, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        appBarLayout.visibility = View.GONE
        webView.visibility = View.GONE
        if (cursorUiEnabled()) {
            cursorOverlay.isVisible = !realPointerActive
            if (cursorOverlay.isVisible) scheduleCursorHide()
            // customView was added to the window content parent, so bringing only the
            // overlay (a child deeper in the hierarchy) would keep it painted under the
            // video. Re-order the overlay's own branch to the front as well.
            (cursorOverlay.parent as? View)?.bringToFront()
            cursorOverlay.bringToFront()
        }
    }

    override fun onHideCustomView() {
        val customView = customView ?: return
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setImmersiveMode(false)
        appBarLayout.isVisible = !isFullscreenPwa
        webView.visibility = View.VISIBLE
        val viewGroup = customView.parent as ViewGroup
        viewGroup.removeView(customView)
        fullScreenCallback?.onCustomViewHidden()
        fullScreenCallback = null
        this.customView = null
    }

    private fun addShortcut() {
        val shortcutInfo = buildShortcutInfo()
        shortcutManager.requestPinShortcut(shortcutInfo, null)
    }

    private fun buildShortcutInfo(): ShortcutInfo {
        val id = pwaManifest?.id ?: System.currentTimeMillis().toString()
        val shortName = pwaManifest?.shortName ?: webView.title.toString()
        val intent = Intent(this, MainActivity::class.java).apply {
            data = Uri.parse(pwaManifest?.startUrl ?: webView.url)
            action = Intent.ACTION_MAIN
            putExtra(IntentUtils.EXTRA_SHORTCUT_ID, id)
            putExtra(IntentUtils.EXTRA_SHORTCUT_NAME, shortName)
            pwaManifest?.let {
                putExtra(MANIFEST_DISPLAY, it.display)
                putExtra(MANIFEST_THEME_COLOR, it.themeColor)
            }
        }
        val launcherIcon = urlIcon?.let {
            Icon.createWithBitmap(UiUtils.getShortcutIcon(it, Color.WHITE))
        } ?: Icon.createWithResource(this, R.mipmap.ic_launcher)
        return ShortcutInfo.Builder(this, id).apply {
            setShortLabel(shortName)
            setIcon(launcherIcon)
            setIntent(intent)
            pwaManifest?.let {
                setLongLabel(it.name)
            }
        }.build()
    }

    override fun updateHistory(title: String, url: String) {
        historyViewModel.insertOrUpdate(title, url)
    }

    override fun replaceHistory(title: String, url: String, newUrl: String) {
        historyViewModel.replace(title, url, newUrl)
    }

    override fun setPwaManifest(manifest: PwaManifest?) {
        pwaManifest = manifest
        if (manifest == null) return
        // find and update pwa shortcut
        lifecycleScope.launch {
            val pinnedShortcuts = shortcutManager.pinnedShortcuts
            val index = pinnedShortcuts.indexOfFirst { it.id == manifest.id }
            if (index == -1) return@launch
            pinnedShortcuts[index] = buildShortcutInfo()
            shortcutManager.updateShortcuts(pinnedShortcuts)
        }
    }

    override fun setStatusBarColor(color: String) {
        if (!isFullscreenPwa) return
        runCatching {
            val color = color.toColorInt()
            val isColorLight = UiUtils.isColorLight(color)
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = isColorLight
                isAppearanceLightNavigationBars = isColorLight
            }
            window.decorView.setBackgroundColor(color)
        }
    }

    override fun webRequestPermissions(
        permissions: Array<String>,
        cb: ((granted: Array<String>) -> Unit)
    ) {
        webRequestPermissions = cb
        webRequestPermissionsLauncher.launch(permissions)
    }

    override fun webProtectedMedia(origin: String, cb: ((granted: Boolean) -> Unit)) {
        AlertDialog.Builder(this)
            .setTitle(origin)
            .setMessage(R.string.protected_media_dialog_message)
            .setPositiveButton(R.string.protected_media_dialog_grant) { _, _ ->
                cb(true)
            }.setNegativeButton(R.string.protected_media_dialog_deny) { _, _ ->
                cb(false)
            }.show()
    }

    override fun onWebShare(value: WebShare) {
        val intent = buildShareIntent(*value.files.toTypedArray())
        intent.putExtra(Intent.EXTRA_TITLE, value.title)

        buildString {
            value.text?.let { appendLine(it) }
            value.url?.let { appendLine(it) }
        }.trimEnd().takeUnless { it.isEmpty() }?.let {
            intent.putExtra(Intent.EXTRA_TEXT, it)
        }

        startActivity(Intent.createChooser(intent, getString(R.string.share_title)))
    }

    private fun setImmersiveMode(enable: Boolean) {
        val decorView = window.decorView

        if (enable) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.insetsController?.let { controller ->
                    controller.hide(WindowInsets.Type.systemBars())
                    controller.systemBarsBehavior =
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } else {
                @Suppress("Deprecation")
                decorView.systemUiVisibility = (
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                                or View.SYSTEM_UI_FLAG_FULLSCREEN
                                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        )
            }
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.insetsController?.show(WindowInsets.Type.systemBars())
            } else {
                @Suppress("Deprecation")
                decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            }
        }

        ViewCompat.setOnApplyWindowInsetsListener(decorView) { v, insets ->
            val systemBarsInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(
                top = if (enable) systemBarsInsets.top else 0,
                bottom = if (enable) systemBarsInsets.bottom else 0,
                left = if (enable) systemBarsInsets.left else 0,
                right = if (enable) systemBarsInsets.right else 0
            )
            insets
        }
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            "key_reach_mode" -> setUiMode()
        }
    }

    private fun setUiMode() {
        // Now you don't see it
        constraintLayout.alpha = 0f
        // Magic happens
        changeUiMode(sharedPreferencesExt.reachModeEnabled)
        // Now you see it
        constraintLayout.alpha = 1f
    }

    private fun changeUiMode(isReachMode: Boolean) {
        appBarLayout.updateLayoutParams<ConstraintLayout.LayoutParams> {
            topToTop = when (isReachMode) {
                true -> ConstraintLayout.LayoutParams.UNSET
                false -> ConstraintLayout.LayoutParams.PARENT_ID
            }
            bottomToBottom = when (isReachMode) {
                true -> ConstraintLayout.LayoutParams.PARENT_ID
                false -> ConstraintLayout.LayoutParams.UNSET
            }
        }

        webView.updateLayoutParams<ConstraintLayout.LayoutParams> {
            bottomToBottom = when (isReachMode) {
                true -> ConstraintLayout.LayoutParams.UNSET
                false -> ConstraintLayout.LayoutParams.PARENT_ID
            }
            bottomToTop = when (isReachMode) {
                true -> R.id.appBarLayout
                false -> ConstraintLayout.LayoutParams.UNSET
            }
            topToBottom = when (isReachMode) {
                true -> ConstraintLayout.LayoutParams.UNSET
                false -> R.id.appBarLayout
            }
            topToTop = when (isReachMode) {
                true -> ConstraintLayout.LayoutParams.PARENT_ID
                false -> ConstraintLayout.LayoutParams.UNSET
            }
        }
    }

    private fun cursorUiEnabled(): Boolean = when (sharedPreferencesExt.cursorMode) {
        SharedPreferencesExt.CURSOR_MODE_ON -> true
        SharedPreferencesExt.CURSOR_MODE_OFF -> false
        else -> (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_TYPE_MASK) ==
            android.content.res.Configuration.UI_MODE_TYPE_TELEVISION ||
            packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!cursorUiEnabled()) return super.dispatchKeyEvent(event)
        val focus = window.currentFocus
        val inWebContent = focus != null && (
            focus == webView || focus == customView ||
                (customView != null && isDescendantOf(customView, focus))
            )
        if (focus != null && !isInsideUrlBar(focus) && !inWebContent) {
            stopArrowHold()
            // Focus is in a dialog/popup/selection toolbar or any other native UI: keys stay
            // native. Focus inside the fullscreen custom view is still web content.
            return super.dispatchKeyEvent(event)
        }
        val imeVisible = ViewCompat.getRootWindowInsets(window.decorView)
            ?.isVisible(WindowInsetsCompat.Type.ime()) == true
        if (imeVisible) {
            stopArrowHold()
            // Editing (address bar or a web field): keys belong to the text field.
            return super.dispatchKeyEvent(event)
        }
        if (focus != null && isInsideUrlBar(focus)) {
            // Keyboard closed but the field kept focus: release it and drive the cursor.
            focus.clearFocus()
        }
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (event.action == KeyEvent.ACTION_DOWN && crossOpen) {
                    // Only a fresh (non-repeating) press arms the close-on-release; the
                    // auto-repeat of the long press that opened the cross must not, or the
                    // cross would vanish the moment OK is released.
                    if (event.repeatCount == 0) crossSwallowOk = true
                    return true
                }
                if (event.action == KeyEvent.ACTION_UP && crossSwallowOk) {
                    crossSwallowOk = false
                    // OK executes the highlighted arm, or just closes when nothing is lit.
                    actOnFocusedArm()
                    return true
                }
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    okDownTime = event.eventTime
                    heldOk = true
                    okTapDone = false
                    window.decorView.postDelayed(okHoldTap, 400L)
                } else if (event.action == KeyEvent.ACTION_UP && heldOk) {
                    heldOk = false
                    window.decorView.removeCallbacks(okHoldTap)
                    if (crossOpen) return true // release of the long press that opened the cross
                    if (selectionMode && selectionText.isNotEmpty() && selectionMenu == null &&
                        !cursorOverlay.isPressPending && !okTapDone
                    ) {
                        // Short press while a selection is active: open its action menu
                        // next to the selected text.
                        showSelectionMenu()
                    } else if (cursorOverlay.isPressPending) {
                        cursorOverlay.pressUp()
                        checkSelectionAfterGesture()
                    } else if (!okTapDone) {
                        if (selectionMode && selectionText.isEmpty()) clearSelection()
                        cursorOverlay.pressDown()
                        cursorOverlay.pressUp()
                    }
                }
                return true
            }

            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    // stepCursor re-shows the cursor; the real-pointer override ends here.
                    realPointerActive = false
                    if (crossOpen) {
                        focusCrossArm(event.keyCode)
                        return true
                    }
                    if (wheelMode) {
                        if (heldArrowKey != event.keyCode) {
                            heldArrowKey = event.keyCode
                            startWheelHold(event.keyCode)
                        }
                        return true
                    }
                    if (heldOk && !okTapDone && !cursorOverlay.isPressPending) {
                        // Arrows while OK is held (before hold-tap) = drag gesture.
                        cursorOverlay.pressDown()
                    }
                    if (heldArrowKey != event.keyCode) {
                        // Arrow keys are ours now: make sure nothing can summon the IME
                        // while the field merely kept a stale focus.
                        WindowInsetsControllerCompat(window, window.decorView)
                            .hide(WindowInsetsCompat.Type.ime())
                        stepCursor(event.keyCode, 15f)
                        startArrowHold(event.keyCode)
                    }
                } else if (event.action == KeyEvent.ACTION_UP) {
                    if (heldArrowKey == event.keyCode) stopArrowHold()
                    if (wheelHoldKey == event.keyCode) stopWheelHold()
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private var heldArrowKey = -1
    private var arrowHoldTick: Runnable? = null
    private var scrollTicks = 0

    private val mouseGone = Runnable { cursorOverlay.hoverEnabled = true }
    private var realPointerActive = false

    private val cursorIdleHide = Runnable {
        if (!realPointerActive && !wheelMode && !crossOpen) {
            cursorOverlay.isVisible = false
        }
    }

    /** Re-shows the cursor on movement and hides it again 5s after it stops. */
    private fun scheduleCursorHide() {
        window.decorView.removeCallbacks(cursorIdleHide)
        window.decorView.postDelayed(cursorIdleHide, 5000L)
    }

    /** A physical pointer took over: hide the virtual cursor until arrows are used again. */
    private fun yieldToRealPointer() {
        // Over a fullscreen video the OS pointer is not drawn at all (secure/device
        // composited layer), so the virtual cursor is the only visible one: keep it.
        // Wheel mode also needs the cursor visible: it is the wheel's pointer position.
        if (customView != null || wheelMode) return
        if (!realPointerActive) {
            realPointerActive = true
            stopArrowHold()
            window.decorView.removeCallbacks(cursorIdleHide)
            cursorOverlay.isVisible = false
        }
    }

    /**
     * True only for motion events coming from an actual mouse device. Synthetic events
     * (the overlay's own hover injection) carry no hardware device and must not count:
     * DecorView routes even rootView-initiated dispatches back through the activity,
     * so without this check the virtual cursor would hide itself on every settle.
     */
    private fun isRealMouseDevice(event: MotionEvent): Boolean {
        val device = event.device ?: return false
        return !device.isVirtual &&
            device.sources and android.view.InputDevice.SOURCE_MOUSE != 0
    }
    private var okDownTime = 0L
    private var heldOk = false
    private var okTapDone = false

    private var crossOpen = false
    private var crossSwallowOk = false
    private var crossFocusArm = CursorOverlay.ARM_NONE
    private var wheelMode = false
    private var wheelHoldKey = -1
    private var wheelHoldTick: Runnable? = null
    private var wheelTicks = 0

    private val okHoldTap = Runnable {
        if (!heldOk) return@Runnable
        okTapDone = true
        if (selectionMode) return@Runnable
        // Long OK opens the action cross instead of grabbing the page directly.
        openCross()
    }

    private fun openCross() {
        if (selectionMode || wheelMode) return
        crossOpen = true
        crossFocusArm = CursorOverlay.ARM_NONE
        cursorOverlay.setFocusedArm(CursorOverlay.ARM_NONE)
        cursorOverlay.isVisible = true
        window.decorView.removeCallbacks(cursorIdleHide)
        cursorOverlay.showCross()
    }

    private fun closeCross() {
        crossOpen = false
        crossSwallowOk = false
        crossFocusArm = CursorOverlay.ARM_NONE
        cursorOverlay.setFocusedArm(CursorOverlay.ARM_NONE)
        cursorOverlay.hideCross()
        scheduleCursorHide()
    }

    /** Arrows only move the highlight; OK executes the focused arm. */
    private fun focusCrossArm(keyCode: Int) {
        val arm = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> CursorOverlay.ARM_UP
            KeyEvent.KEYCODE_DPAD_LEFT -> CursorOverlay.ARM_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> CursorOverlay.ARM_RIGHT
            else -> return // the bottom arm is reserved for a future action
        }
        crossFocusArm = arm
        cursorOverlay.setFocusedArm(arm)
    }

    private fun actOnFocusedArm() {
        when (crossFocusArm) {
            CursorOverlay.ARM_UP -> {
                closeCross()
                webView.reload()
            }
            CursorOverlay.ARM_LEFT -> {
                closeCross()
                // Drag-selection: the arrows move the cursor and the text between the
                // anchor point and the cursor stays highlighted, like dragging a mouse.
                selectionMode = true
                selAnchorCss = cssCursor(cursorOverlay.cursorX, cursorOverlay.cursorY)
                updateSelectionDrag()
            }
            CursorOverlay.ARM_RIGHT -> {
                closeCross()
                enterWheelMode()
            }
            else -> closeCross()
        }
    }

    private fun enterWheelMode() {
        wheelMode = true
        cursorOverlay.isVisible = true
        window.decorView.removeCallbacks(cursorIdleHide)
        cursorOverlay.pinIcon("all_scroll")
    }

    private fun exitWheelMode() {
        wheelMode = false
        stopWheelHold()
        cursorOverlay.unpinIcon()
        scheduleCursorHide()
    }

    private fun startWheelHold(keyCode: Int) {
        stopWheelHold()
        wheelHoldKey = keyCode
        wheelTicks = 0
        // Same rhythm as the edge scroll in stepCursor: immediate start, then a 16ms
        // tick accelerating from 4 to 14 view pixels per frame.
        sendWheelScroll(keyCode, 4f)
        val tick = object : Runnable {
            override fun run() {
                wheelTicks++
                val amount = (4f + wheelTicks * 0.4f).coerceAtMost(14f)
                sendWheelScroll(wheelHoldKey, amount)
                window.decorView.postDelayed(this, 16L)
            }
        }
        wheelHoldTick = tick
        window.decorView.post(tick)
    }

    private fun stopWheelHold() {
        wheelHoldTick?.let { window.decorView.removeCallbacks(it) }
        wheelHoldTick = null
        wheelHoldKey = -1
    }

    /**
     * Scrolls what a real mouse wheel would scroll at the cursor point: the nearest
     * scrollable ancestor of the element under the cursor, falling back to the page.
     * A hand-made ACTION_SCROLL never reaches Blink (ViewGroup does not forward
     * non-hover generic events to children) and an untrusted WheelEvent has no default
     * action, so the same result is achieved by driving scrollBy() on that element.
     * [amount] is in view pixels (edge-scroll units) converted to CSS pixels.
     */
    private fun sendWheelScroll(keyCode: Int, amount: Float) {
        val wl = IntArray(2)
        webView.getLocationInWindow(wl)
        val density = resources.displayMetrics.density
        val cssX = (cursorOverlay.cursorX - wl[0]) / density
        val cssY = (cursorOverlay.cursorY - wl[1]) / density
        if (cssX < 0f || cssY < 0f || cssX > webView.width / density ||
            cssY > webView.height / density
        ) {
            return
        }
        val notch = amount / density
        var dx = 0f
        var dy = 0f
        var useX = 0
        var useY = 0
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> { dy = -notch; useY = 1 }
            KeyEvent.KEYCODE_DPAD_DOWN -> { dy = notch; useY = 1 }
            KeyEvent.KEYCODE_DPAD_LEFT -> { dx = -notch; useX = 1 }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { dx = notch; useX = 1 }
            else -> return
        }
        val js = "(function(){var el=document.elementFromPoint($cssX,$cssY);" +
            "var t=el;" +
            "while(t){" +
            "var s=getComputedStyle(t);" +
            "if(($useY&&t.scrollHeight>t.clientHeight+1&&" +
            "(s.overflowY==='auto'||s.overflowY==='scroll'))||" +
            "($useX&&t.scrollWidth>t.clientWidth+1&&" +
            "(s.overflowX==='auto'||s.overflowX==='scroll')))break;" +
            "t=t.parentElement;}" +
            "if(t)t.scrollBy($dx,$dy);else window.scrollBy($dx,$dy);})();"
        webView.evaluateJavascript(js, null)
    }

    private fun applySelectionResult(value: String?) {
        val raw = value?.removeSurrounding("\"")
            ?.replace("\\\"", "\"")
            ?.replace("\\n", "\n")
            ?.replace("\\/", "/")
            ?: return
        try {
            val obj = org.json.JSONObject(raw)
            val text = obj.getString("t")
            if (text.isEmpty()) return
            selectionText = text
            val wl = IntArray(2)
            webView.getLocationInWindow(wl)
            val density = resources.displayMetrics.density
            val rects = obj.getJSONArray("r")
            val list = ArrayList<FloatArray>(rects.length())
            for (i in 0 until rects.length()) {
                val row = rects.getJSONArray(i)
                list.add(
                    floatArrayOf(
                        row.getDouble(0).toFloat() * density + wl[0],
                        row.getDouble(1).toFloat() * density + wl[1],
                        row.getDouble(2).toFloat() * density,
                        row.getDouble(3).toFloat() * density
                    )
                )
            }
            cursorOverlay.setHighlight(list)
            if (list.isNotEmpty()) {
                lastSelRect = list.last()
            }
        } catch (ex: Exception) {
        }
    }

    private fun stepCursor(keyCode: Int, step: Float) {
        // Any arrow movement summons the cursor back and restarts its idle timer.
        cursorOverlay.isVisible = true
        scheduleCursorHide()
        val dx = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> -step
            KeyEvent.KEYCODE_DPAD_RIGHT -> step
            else -> 0f
        }
        val dy = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> -step
            KeyEvent.KEYCODE_DPAD_DOWN -> step
            else -> 0f
        }
        if (cursorOverlay.isPressPending) {
            cursorOverlay.dragBy(dx, dy)
            return
        }
        if (!cursorOverlay.moveBy(dx, dy) && dy != 0f) {
            scrollTicks++
            val amount = (4f + scrollTicks * 0.4f).coerceAtMost(14f)
            webView.scrollBy(0, if (dy > 0f) amount.toInt() else -amount.toInt())
        } else if (selectionMode) {
            updateSelectionDrag()
        } else {
            scrollTicks = 0
        }
    }

    private fun stopArrowHold() {
        arrowHoldTick?.let { window.decorView.removeCallbacks(it) }
        arrowHoldTick = null
        heldArrowKey = -1
        scrollTicks = 0
    }

    private fun startArrowHold(keyCode: Int) {
        stopArrowHold()
        heldArrowKey = keyCode
        var ticks = 0
        val tick = object : Runnable {
            override fun run() {
                ticks++
                val step = (1.5f + ticks * 0.12f).coerceAtMost(11f)
                stepCursor(heldArrowKey, step)
                window.decorView.postDelayed(this, 16L)
            }
        }
        arrowHoldTick = tick
        window.decorView.post(tick)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(android.view.InputDevice.SOURCE_MOUSE)) {
            if (cursorUiEnabled()) {
                if (isRealMouseDevice(event)) {
                    yieldToRealPointer()
                }
                cursorOverlay.hoverEnabled = false
                window.decorView.removeCallbacks(mouseGone)
                window.decorView.postDelayed(mouseGone, 3000L)
            }
            if (cursorUiEnabled() && event.action == MotionEvent.ACTION_UP) {
                checkSelectionAfterGesture()
            } else if (cursorUiEnabled() && event.action != MotionEvent.ACTION_DOWN &&
                event.action != MotionEvent.ACTION_BUTTON_PRESS
            ) {
                cursorOverlay.moveToWindowPoint(event.x, event.y)
            }
        }
        return super.dispatchTouchEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (cursorUiEnabled() &&
            event.action == MotionEvent.ACTION_HOVER_MOVE &&
            isRealMouseDevice(event)
        ) {
            yieldToRealPointer()
        }
        return super.dispatchGenericMotionEvent(event)
    }

    // The WebView selection menu lives in the WebView's own process window and cannot be
    // clicked from here, so we offer our own action panel. It is triggered deterministically
    // after long-press click gestures (which create a selection).
    private var selectionText = ""
    private var selectionMode = false
    private var selAnchorCss = floatArrayOf(0f, 0f)
    private var selectionMenu: PopupWindow? = null
    private var lastSelRect: FloatArray? = null

    /**
     * Reads the selection shortly after a long-press gesture. Blink syncs the DOM selection
     * asynchronously, so retry a few times until it is visible; only right after a gesture,
     * never continuously.
     */
    private fun checkSelectionAfterGesture() {
        var attempt = 0
        val runnable = object : Runnable {
            override fun run() {
                attempt++
                checkSelection { found ->
                    if (!found && attempt < 3) {
                        window.decorView.postDelayed(this, 700L)
                    }
                }
            }
        }
        window.decorView.postDelayed(runnable, 300L)
    }

    private fun checkSelection(onResult: ((Boolean) -> Unit)? = null) {
        if (!sharedPreferencesExt.javascriptEnabled) {
            onResult?.invoke(false)
            return
        }
        webView.evaluateJavascript("window.getSelection().toString()") { value ->
            val text = value?.removeSurrounding("\"")?.replace("\\n", "\n") ?: ""
            if (text.isBlank()) {
                clearSelection()
                onResult?.invoke(false)
            } else {
                selectionText = text
                selectionMode = true
                updateHighlightFromSelection { showSelectionMenu() }
                onResult?.invoke(true)
            }
        }
    }

    private fun showSelectionMenu() {
        dismissSelectionMenu()
        val density = resources.displayMetrics.density
        val menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 14f * density
                setColor(0xE6282828.toInt())
            }
            elevation = 12f * density
            setPadding(4, 6, 4, 6)
        }
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(4, 6, 4, 6)
        }
        val actions = listOf(
            "Копировать" to {
                copySelection()
                dismissSelectionMenu()
            },
            "Веб-поиск" to {
                webView.followUrl(selectionText)
                clearSelection()
                dismissSelectionMenu()
            },
            "Снять" to {
                clearSelection()
                dismissSelectionMenu()
            }
        )
        for ((title, action) in actions) {
            val row = android.widget.TextView(this).apply {
                text = title
                textSize = 16f
                minWidth = (180 * density).toInt()
                minHeight = (48 * density).toInt()
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                setPadding((16 * density).toInt(), 0, (16 * density).toInt(), 0)
                background = android.R.attr.selectableItemBackground.let {
                    val tv = android.util.TypedValue()
                    theme.resolveAttribute(it, tv, true)
                    resources.getDrawable(tv.resourceId, theme)
                }
                isFocusable = true
                setOnClickListener { action() }
            }
            list.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        menu.addView(list)
        menu.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val menuW = menu.measuredWidth.coerceAtMost(resources.displayMetrics.widthPixels - 16)
        val menuH = menu.measuredHeight
        val rect = lastSelRect
        var x = (rect?.get(0) ?: cursorOverlay.cursorX).toInt().coerceAtMost(
            resources.displayMetrics.widthPixels - menuW - 8
        )
        var y = ((rect?.get(1) ?: cursorOverlay.cursorY) + (rect?.get(3) ?: 0f) + 4f).toInt()
        if (y + menuH > resources.displayMetrics.heightPixels - 8) {
            y = ((rect?.get(1) ?: cursorOverlay.cursorY) - menuH - 4f).toInt().coerceAtLeast(8)
        }
        val popup = PopupWindow(menu, menuW, menuH, true).apply {
            isFocusable = true
            animationStyle = android.R.style.Animation_Dialog
            setOnDismissListener { clearSelection() }
        }
        popup.showAtLocation(webView, Gravity.START or Gravity.TOP, x, y)
        selectionMenu = popup
        findFirstFocusable(menu)?.requestFocus()
    }

    private fun dismissSelectionMenu() {
        selectionMenu?.dismiss()
        selectionMenu = null
    }

    private fun cssCursor(x: Float, y: Float): FloatArray {
        val wl = IntArray(2)
        webView.getLocationInWindow(wl)
        val d = resources.displayMetrics.density
        return floatArrayOf((x - wl[0]) / d, (y - wl[1]) / d)
    }

    private fun updateHighlightFromSelection(onDone: (() -> Unit)? = null) {
        if (!sharedPreferencesExt.javascriptEnabled) {
            onDone?.invoke()
            return
        }
        val js = """
            (function(){
              var s=window.getSelection();
              if(!s.rangeCount) return JSON.stringify({t:'',r:[]});
              var rects=Array.from(s.getRangeAt(0).getClientRects())
                .map(function(b){return [b.x,b.y,b.width,b.height];});
              return JSON.stringify({t:s.toString(),r:rects});
            })()
        """.trimIndent()
        webView.evaluateJavascript(js) { value ->
            applySelectionResult(value)
            onDone?.invoke()
        }
    }

    private fun updateSelectionDrag() {
        if (!sharedPreferencesExt.javascriptEnabled) return
        val a = selAnchorCss
        val c = cssCursor(cursorOverlay.cursorX, cursorOverlay.cursorY)
        val js = """
            (function(ax,ay,cx,cy){
              function caretAt(x,y){
                var r=null;
                if(document.caretRangeFromPoint){r=document.caretRangeFromPoint(x,y);}
                else if(document.caretPositionFromPoint){var p=document.caretPositionFromPoint(x,y);if(p){r=document.createRange();r.setStart(p.offsetNode,p.offset);r.setEnd(p.offsetNode,p.offset);}}
                return r;
              }
              var A=caretAt(ax,ay), B=caretAt(cx,cy);
              if(!A||!B) return JSON.stringify({t:'',r:[]});
              var ap=A.startContainer, ao=A.startOffset, bp=B.startContainer, bo=B.startOffset;
              var range=document.createRange();
              var before = ap===bp ? ao<=bo : (ap.compareDocumentPosition(bp)&Node.DOCUMENT_POSITION_FOLLOWING)!==0;
              if(before){range.setStart(ap,ao);range.setEnd(bp,bo);}else{range.setStart(bp,bo);range.setEnd(ap,ao);}
              var rects=[];var cs=range.getClientRects();
              for(var i=0;i<cs.length;i++){var q=cs[i];rects.push([q.left,q.top,q.width,q.height]);}
              return JSON.stringify({t:range.toString(),r:rects});
            })(${a[0]},${a[1]},${c[0]},${c[1]})
        """.trimIndent()
        webView.evaluateJavascript(js) { value -> applySelectionResult(value) }
    }

    private fun copySelection() {
        if (selectionText.isEmpty()) return
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("Jelly", selectionText))
    }

    private fun clearSelection() {
        selectionMode = false
        selectionText = ""
        lastSelRect = null
        cursorOverlay.setHighlight(emptyList())
        dismissSelectionMenu()
        if (sharedPreferencesExt.javascriptEnabled) {
            webView.evaluateJavascript("window.getSelection().removeAllRanges()", null)
        }
    }

    private fun findFirstFocusable(view: View?): View? {
        if (view == null) return null
        if (view.isFocusable && view.isVisible) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findFirstFocusable(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    private fun isInsideUrlBar(view: View): Boolean {
        var v: View? = view
        while (v != null) {
            if (v === urlBarLayout) return true
            v = v.parent as? View
        }
        return false
    }

    private fun isDescendantOf(root: View?, view: View): Boolean {
        var v: View? = view
        while (v != null) {
            if (v === root) return true
            v = v.parent as? View
        }
        return false
    }

    private fun updateCursorIcon(x: Float, y: Float) {
        if (wheelMode) return // the wheel mode pins its own icon
        val wl = IntArray(2)
        webView.getLocationInWindow(wl)
        val wx = x - wl[0]
        val wy = y - wl[1]
        if (wx < 0f || wy < 0f || wx > webView.width || wy > webView.height) {
            cursorOverlay.setIconKind("arrow")
            return
        }
        val density = resources.displayMetrics.density
        val cssX = (wx / density).toInt()
        val cssY = (wy / density).toInt()
        val js = """
            (function() {
              var el = document.elementFromPoint($cssX, $cssY);
              if (!el) return 'arrow';
              var field = el.closest('input,textarea,[contenteditable="true"]');
              if (field) return 'text';
              var link = el.closest('a,button,[role="button"]');
              if (link) return 'hand';
              return 'arrow';
            })()
        """.trimIndent()
        webView.evaluateJavascript(js) { value ->
            val kind = when {
                value?.contains("hand") == true -> "hand"
                value?.contains("text") == true -> "text"
                else -> "arrow"
            }
            cursorOverlay.setIconKind(kind)
        }
    }

    companion object {
        private val TAG = MainActivity::class.java.simpleName
        private const val MANIFEST_DISPLAY = "manifest_display"
        private const val MANIFEST_THEME_COLOR = "manifest_theme_color"
        private val ALLOWED_FULLSCREEN_PWA_VALUES = arrayOf("fullscreen", "standalone")
    }
}
