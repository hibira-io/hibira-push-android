package io.hikibi.push.api

import io.hikibi.push.BuildConfig
import okhttp3.Interceptor
import okhttp3.Response

class LibraryHeaderInterceptor : Interceptor {
  override fun intercept(chain: Interceptor.Chain): Response {
    val request = chain.request()
    val newRequest = request.newBuilder()
        .addHeader("x-hikibi-library", "hikibi-push-android ${BuildConfig.VERSION_NAME}")
        .build()
    return chain.proceed(newRequest)
  }
}
