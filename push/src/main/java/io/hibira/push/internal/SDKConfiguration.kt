package io.hibira.push.internal

import android.content.Context
import android.content.pm.PackageManager

/**
 * Where the SDK sends its requests. The endpoint is, in order: the value
 * passed to PushNotifications.setEndpoint (kept across restarts, so
 * background work uses it too), the `io.hibira.push:endpoint` meta-data in
 * the app's manifest, or Hibira's hosted push API.
 */
class SDKConfiguration(context: Context) {
  private val appContext = context.applicationContext ?: context

  private val metadataBundle =
      appContext.packageManager.getApplicationInfo(appContext.packageName, PackageManager.GET_META_DATA)
          .metaData

  val endpoint: String
    get() = (prefs(appContext).getString(PREF_ENDPOINT, null)
        ?: metadataBundle?.getString(METADATA_ENDPOINT, null)
        ?: DEFAULT_ENDPOINT).trimEnd('/')

  companion object {
    const val DEFAULT_ENDPOINT = "https://ws.hibira.io"
    private const val METADATA_ENDPOINT = "io.hibira.push:endpoint"
    private const val PREF_ENDPOINT = "endpoint"

    private fun prefs(context: Context) =
        context.getSharedPreferences("io.hibira.push.Configuration", Context.MODE_PRIVATE)

    fun setEndpoint(context: Context, endpoint: String?) {
      if (endpoint != null) {
        require(endpoint.startsWith("https://") || endpoint.startsWith("http://")) {
          "endpoint must be an http(s) URL, for example $DEFAULT_ENDPOINT"
        }
      }
      val appContext = context.applicationContext ?: context
      prefs(appContext).edit().apply {
        if (endpoint == null) remove(PREF_ENDPOINT) else putString(PREF_ENDPOINT, endpoint.trimEnd('/'))
      }.apply()
    }
  }
}
