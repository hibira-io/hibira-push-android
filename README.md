# Hibira Push for Android

Android client for [Hibira Push](https://hibira.io), the push notification
service of Hibira. Hibira Push speaks a Beams-compatible device API, and this
library is an MIT-licensed fork of `pusher/push-notifications-android` 1.10.0
with the host made configurable.

- Minimum SDK 24, compiled against API 34.
- Written in Kotlin and usable from Java.
- Delivery uses Firebase Cloud Messaging, so your app still needs a Firebase
  project and `google-services.json`. Upload the FCM service account in the
  Hibira portal (app page, Push notifications).

## Installation

Add JitPack to `settings.gradle`:

```groovy
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url 'https://jitpack.io' }
    }
}
```

Then in your app's `build.gradle`:

```groovy
plugins {
    id 'com.android.application'
    id 'com.google.gms.google-services'
}

dependencies {
    implementation 'com.google.firebase:firebase-messaging:23.4.0'
    implementation 'com.github.hibira-io:hibira-push-android:1.0.0'
}
```

## Usage

```kotlin
import io.hibira.push.PushNotifications

PushNotifications.start(applicationContext, "YOUR_INSTANCE_ID")
PushNotifications.addDeviceInterest("hello")
```

The instance ID is shown in the portal on the app's Push notifications card.

### Authenticated users

```kotlin
import io.hibira.push.PushCallback
import io.hibira.push.PushCallbackError
import io.hibira.push.auth.AuthData
import io.hibira.push.auth.AuthDataGetter
import io.hibira.push.auth.PushTokenProvider

val tokenProvider = PushTokenProvider(
    "https://your-backend.example.com/push-auth",
    object : AuthDataGetter {
        override fun getAuthData() = AuthData(
            headers = hashMapOf("Authorization" to "Bearer $sessionToken"),
        )
    },
)

PushNotifications.setUserId("user-1", tokenProvider, object : PushCallback<Void, PushCallbackError> {
    override fun onSuccess(vararg values: Void) {}
    override fun onFailure(error: PushCallbackError) {}
})
```

Your backend returns `{"token": "..."}`, made with `generateToken` from any
Beams-compatible server SDK and your Hibira secret key.

### Host

The SDK uses `https://ws.hibira.io`. Self-hosted servers or another region can
set the host in the manifest:

```xml
<application>
    <meta-data
        android:name="io.hibira.push:endpoint"
        android:value="https://push.example.com" />
</application>
```

or in code, before `start`:

```kotlin
PushNotifications.setEndpoint(applicationContext, "https://push.example.com")
```

The value set in code wins over the manifest and is kept across launches.

## Moving from the Beams Android SDK

| Before | After |
|---|---|
| `com.pusher:push-notifications-android` | `com.github.hibira-io:hibira-push-android` |
| `com.pusher.pushnotifications.*` | `io.hibira.push.*` |
| `BeamsTokenProvider` | `PushTokenProvider` |
| `BeamsCallback` | `PushCallback` |
| `PusherCallbackError` | `PushCallbackError` |
| `PusherAlreadyRegisteredException` | `AlreadyRegisteredException` |

`PushNotificationsInstance`, `MessagingService` and the listener interfaces
keep their names. Devices register again on first start with the new SDK.

## Building

```sh
./gradlew :push:testDebugUnitTest :push:assembleRelease
```

## License

MIT. See [LICENSE.md](LICENSE.md). Based on
[pusher/push-notifications-android](https://github.com/pusher/push-notifications-android),
Copyright (c) 2017 Pusher Ltd.
