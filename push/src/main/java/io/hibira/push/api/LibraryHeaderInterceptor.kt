package io.hibira.push.api

import io.hibira.push.BuildConfig
import okhttp3.Interceptor
import okhttp3.Response

class LibraryHeaderInterceptor : Interceptor {
  override fun intercept(chain: Interceptor.Chain): Response {
    val request = chain.request()
    val newRequest = request.newBuilder()
        .addHeader("x-hibira-library", "hibira-push-android ${BuildConfig.VERSION_NAME}")
        .build()
    return chain.proceed(newRequest)
  }
}
