# Hibira Push and Chat for Android

Two libraries in one repository:

- `push`: Android client for [Hibira Push](https://hibira.io), the push
  notification service of Hibira.
- `chat`: conversations, message history and live chat events for the
  [Hibira Chat API](https://hibira.io/chat-api). See [Chat](#chat).

## Push

Hibira Push is the push notification service of Hibira. Hibira Push speaks a Beams-compatible device API, and this
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
    implementation 'com.github.hibira-io:hibira-push-android:1.1.1'
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

The SDK uses `https://api.hibira.io`. Self-hosted servers or another region can
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

## Chat

`io.hibira.chat` talks to the Chat API's user routes and keeps conversations
live. It is plain Kotlin (OkHttp and coroutines), works from Android API 24
up, and needs no other realtime library: `ChatSocket` connects, signs in as
the user and receives the `chat.*` events on the user's channel.

```groovy
dependencies {
    implementation 'com.github.hibira-io.hibira-push-android:chat:1.2.0'
}
```

(`com.github.hibira-io.hibira-push-android:push-android:1.2.0` is the push
library on its own.)

### Your server

The app needs two things from your backend, both signed with the app
secret, so the secret never ships in the app:

1. A **user token** for the HTTP calls: an HS256 JWT `{"sub": userId, "exp": ...}`
   (or one from the Chat API's `POST /apps/{app_id}/chat/tokens`).
2. A **user authentication endpoint** for the realtime connection, the same
   one `pusher.signin()` uses. With the Node `pusher` library:

```js
app.post('/chat/token', requireLogin, (req, res) => {
  const token = jwt.sign({ sub: req.user.id }, process.env.HIBIRA_SECRET,
    { algorithm: 'HS256', expiresIn: '1h' });
  res.json({ token });
});

app.post('/pusher/user-auth', requireLogin, (req, res) => {
  res.json(pusher.authenticateUser(req.body.socket_id, { id: req.user.id }));
});
```

Your server also creates conversations and adds members (`PUT
/apps/{app_id}/chat/conversations/{id}`); apps cannot.

### Usage

```kotlin
import io.hibira.chat.ChatClient
import io.hibira.chat.ChatSocket

val chat = ChatClient(appId = "YOUR_APP_ID", token = { api.fetchChatToken() })
val socket = ChatSocket(
    key = "YOUR_APP_KEY",
    client = chat,
    authenticator = ChatSocket.endpoint(
        "https://your-backend.example.com/pusher/user-auth",
        headers = mapOf("Authorization" to "Bearer $session"),
    ),
)
socket.connect()

// The conversation list, with unread counts, kept live.
val inbox = chat.conversationList()
inbox.start()
lifecycleScope.launch { inbox.state.collect { render(it.conversations) } }

// One conversation: newest messages, older pages, typing and read receipts.
val room = chat.conversationController("trip-1")
room.start()
lifecycleScope.launch {
    room.state.collect { s -> show(s.messages, s.typing, s.hasOlder) }
}
lifecycleScope.launch {
    room.send("Hello")
    room.markRead()
    room.loadOlder()
}
editText.doAfterTextChanged { room.typing() }

// Later
room.close(); inbox.close(); socket.close()
```

`ConversationController` re-reads the newest messages each time the socket
comes back, so edits, deletes and reactions made while the app was offline
show up. Without a socket it still works, from what it loads.

`ChatClient` has every user route: `conversations`, `conversation`,
`messages` (`after`, `last`, `limit`), `search`, `send`, `edit`, `delete`,
`react`, `unreact`, `report`, `read`, `typing`, `translate`,
`setLanguage`, `me` and `upload` (straight to storage, up to 25 MB; send
the returned `Attachment` in a message). Errors are `ChatException` with
the HTTP status.

**Translations.** Set the user's language once (`chat.setLanguage("ja")`)
and messages from others arrive translated a moment later
(`chat.message_updated`). `message.textFor("ja")` gives the translation
when there is one, else the original text; `room.translate(seq, "ja")`
translates one message on request. Translation needs a Chat add-on on the
hosted service.

If your app already runs a Pusher client that signs in, skip `ChatSocket`
and forward the user's events:

```kotlin
ChatEvent.NAMES.forEach { name ->
    pusher.user().bind(name) { e -> chat.handleEvent(e.eventName, e.data) }
}
// and after each reconnect:
chat.notifyLive()
```

Offline messages arrive as push notifications through the `push` library,
registered under the same user ID.

### 中文摘要

`chat` 模組是 Hibira Chat API 的 Android 用戶端：`ChatClient` 呼叫使用者路由（對話、訊息紀錄、傳送、編輯、刪除、表情回應、檢舉、已讀、輸入中、翻譯、使用者語言、附件上傳），`ChatSocket` 自己連線並以使用者身分登入，收 `chat.*` 即時事件，不需要其他 Pusher 函式庫。`conversationList()` 提供含未讀數的對話列表，`conversationController(id)` 提供單一對話的訊息、往前翻頁、輸入中與已讀狀態，斷線重連後會自動補齊。App 需要你的後端提供使用者 token（用 app secret 簽的 JWT）和使用者認證端點（`pusher.authenticateUser`）；建立對話、加成員仍由你的後端呼叫。

## Building

```sh
./gradlew :push:testDebugUnitTest :push:assembleRelease :chat:test
```

## License

MIT. See [LICENSE.md](LICENSE.md). Based on
[pusher/push-notifications-android](https://github.com/pusher/push-notifications-android),
Copyright (c) 2017 Pusher Ltd.
