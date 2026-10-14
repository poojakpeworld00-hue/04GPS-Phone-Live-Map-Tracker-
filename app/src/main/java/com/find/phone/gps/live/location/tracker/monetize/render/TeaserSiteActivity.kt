package com.find.phone.gps.live.location.tracker.monetize.render

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnAttach

class TeaserSiteActivity : Activity() {
    private var webView: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val url = intent?.getStringExtra(EXTRA_URL)?.trim()
        if (url.isNullOrEmpty()) {
            finish()
            return
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.WHITE)
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
        }

        val web = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean {
                    val target = request.url

                    if (!StraightHrefOpener.isWebScheme(target)) {
                        val handed = runCatching {
                            startActivity(
                                Intent(Intent.ACTION_VIEW, target)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }.isSuccess

                        if (handed) finish()
                        return handed
                    }
                    return false
                }
            }
        }
        webView = web
        root.addView(web)
        setContentView(root)

        enactInsets(root)
        web.loadUrl(url)
    }

    private fun enactInsets(root: FrameLayout) {
        fun apply(insets: WindowInsetsCompat?) {
            val bars = insets?.getInsets(WindowInsetsCompat.Type.systemBars()) ?: return
            root.setPadding(bars.left, bars.top, bars.right, bars.bottom)
        }
        root.doOnAttach { apply(ViewCompat.getRootWindowInsets(root)) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            apply(insets)
            insets
        }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        val web = webView
        if (web != null && web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        webView?.destroy()
        webView = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_URL = "promo_web_url"
        private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT

        fun intentFor(context: Context, url: String): Intent =
            Intent(context, TeaserSiteActivity::class.java).putExtra(EXTRA_URL, url)

        fun open(context: Context, url: String): Boolean {
            val uri = runCatching { url.trim().toUri().takeIf { !it.scheme.isNullOrBlank() } }
                .getOrNull() ?: return false
            if (runCatching { context.startActivity(intentFor(context, url.trim())) }.isSuccess) {
                return true
            }
            return runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }.isSuccess
        }
    }
}
