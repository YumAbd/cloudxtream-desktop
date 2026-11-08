package com.lagradost.cloudstream3.network

import okhttp3.Interceptor
import okhttp3.Response

/**
 * On Android this solves Cloudflare challenges in a hidden WebView. Phase 0 passes requests through
 * unchanged so we can see which sites need it; the desktop app will use an embedded browser.
 */
class CloudflareKiller : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response = chain.proceed(chain.request())
}
