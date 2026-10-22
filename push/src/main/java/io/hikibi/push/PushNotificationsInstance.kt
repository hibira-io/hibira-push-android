package io.hikibi.push

import java.util.regex.Pattern
import android.content.Context
import android.os.*
import com.google.firebase.installations.FirebaseInstallations
import com.google.firebase.messaging.FirebaseMessaging
import io.hikibi.push.api.DeviceMetadata
import io.hikibi.push.api.PushNotificationsAPI
import io.hikibi.push.api.PushNotificationsAPIInvalidToken
import io.hikibi.push.auth.TokenProvider
import io.hikibi.push.fcm.MessagingService
import io.hikibi.push.internal.*
import io.hikibi.push.logging.Logger
import io.hikibi.push.validation.Validations

/**
 * Thrown when the device is re-registered to a different instance id. If you wish to register a
 * device to a different instance you will need to reinstall the application.
 *
 * @param message Error message to be shown
 */
class AlreadyRegisteredException(message: String) : RuntimeException(message)

/**
 * Thrown when the device is re-registered to a different user id.
 */
class AlreadyRegisteredAnotherUserIdException(message: String) : IllegalStateException(message)

/**
 * Returned by io.hikibi.push.PushCallback when an async operation fails.
 *
 * @param message Error message to be shown
 * @param cause Throwable that cause the async operation to fail (optional)
 */
data class PushCallbackError(val message: String, val cause: Throwable?)

internal sealed class ServerSyncEvent
internal data class InterestsChangedEvent(val interests: Set<String>): ServerSyncEvent()
internal data class UserIdSet(val userId: String, val callbackError: PushCallbackError?): ServerSyncEvent()
internal data class InvalidTokenEvent(val invalid: Boolean): ServerSyncEvent()

internal class ServerSyncEventHandler private constructor(looper: Looper): Handler(looper) {
  var onSubscriptionsChangedListener: SubscriptionsChangedListener? = null
  var userIdToCallbacks: MutableMap<String, MutableList<PushCallback<Void, PushCallbackError>>> = mutableMapOf()

  fun addUserIdCallback(userId: String, callback: PushCallback<Void, PushCallbackError>) {
    synchronized(userIdToCallbacks) {
      val callbacks = userIdToCallbacks.getOrPut(userId) { mutableListOf() }

      callbacks.add(callback)
    }
  }

  override fun handleMessage(msg: Message) {
    val event = msg.obj

    when (event) {
      is InterestsChangedEvent -> {
        onSubscriptionsChangedListener?.onSubscriptionsChanged(event.interests)
      }
      is UserIdSet -> {
        synchronized(userIdToCallbacks) {
          userIdToCallbacks[event.userId]?.let { callbacks ->
            if (event.callbackError == null) {
              callbacks.forEach { it.onSuccess() }
            } else {
              callbacks.forEach { it.onFailure(event.callbackError) }
            }
          }

          userIdToCallbacks.remove(event.userId)
        }
      }
    }
  }

  internal companion object {
    private val serverSyncEventHandlers = mutableMapOf<String, ServerSyncEventHandler>()
    internal fun obtain(instanceId: String, looper: Looper): ServerSyncEventHandler {
      return synchronized(serverSyncEventHandlers) {
        serverSyncEventHandlers.getOrPut(instanceId) {
          ServerSyncEventHandler(looper)
        }
      }
    }
  }
}

/**
 * The Hikibi Push client.
 *
 * @param context the application context
 * @param instanceId the id of the instance
 */
class PushNotificationsInstance @JvmOverloads constructor(
    context: Context,
    protected val instanceId: String
) {
  private val log = Logger.get(this::class)

  private val sdkConfig = SDKConfiguration(context)
  private val deviceStateStore = InstanceDeviceStateStore.obtain(instanceId, context)
  private val oldSDKDeviceStateStore = OldSDKDeviceStateStore(context)

  private val serverSyncEventHandler = ServerSyncEventHandler.obtain(instanceId, context.mainLooper)

  private val serverSyncHandler = {
    ServerSyncHandler.obtain(
        instanceId = instanceId,
        api = PushNotificationsAPI(instanceId, sdkConfig.endpoint),
        deviceStateStore = deviceStateStore,
        secureFileDir = context.filesDir,
        handleServerSyncEvent = { msg ->
          serverSyncEventHandler.sendMessage(Message.obtain().apply { obj = msg })
          if (msg is InvalidTokenEvent) {
            // requeue start job
            startHasBeenCalledThisSession = true
            deviceStateStore.startJobHasBeenEnqueued = false
            start()
            for (i in deviceStateStore.interests) {
              // this forces device interests to be re-registered
              // the set of queued interests will be cleared by the registration process
              forceSubscribe(i)
            }
          }
        },
        getTokenProvider = {
          PushNotifications.tokenProvider[instanceId]
        }
    )
  }()

  init {
    Validations.validateApplicationIcon(context)
    val dds = DeviceStateStore(context)

    dds.instanceIds = dds.instanceIds.apply { add(instanceId) }
  }

  companion object {
    private val validInterestRegex = Pattern.compile("^[a-zA-Z0-9_\\-=@,.;]{1,164}$").toRegex()
  }

  private fun addInterestToStore(interest: String): Boolean {
    val interests = deviceStateStore.interests
    if (interests.add(interest)) {
      deviceStateStore.interests = interests
      return true
    }
    return false // nothing changed
  }

  private fun removeInterestFromStore(interest: String): Boolean {
    val interests = deviceStateStore.interests
    if (interests.remove(interest)) {
      deviceStateStore.interests = interests
      return true
    }
    return false // nothing changed
  }

  private fun replaceAllInterestsInStore(interests: Set<String>): Boolean {
    val localInterests = deviceStateStore.interests
    val areInterestsDifferent = !(localInterests.containsAll(interests) && interests.containsAll(localInterests))
    if (areInterestsDifferent) {
      deviceStateStore.interests = interests.toMutableSet()
      return true
    }
    return false // nothing changed
  }

  private var startHasBeenCalledThisSession = false
  /**
   * Starts the PushNotification client and synchronizes the FCM device token with
   * Hikibi Push.
   */
  fun start(): PushNotificationsInstance {
    startHasBeenCalledThisSession = true
    val handleFcmToken = { fcmToken: String ->
      synchronized(deviceStateStore) {
        if (startHasBeenCalledThisSession) {
          if (deviceStateStore.startJobHasBeenEnqueued) {
            serverSyncHandler.sendMessage(ServerSyncHandler.refreshToken(fcmToken))
          } else {
            serverSyncHandler.sendMessage(
              ServerSyncHandler.start(
                fcmToken,
                oldSDKDeviceStateStore.clientIds()
              )
            )
            deviceStateStore.startJobHasBeenEnqueued = true
          }
        }
      }

      Unit
    }

    MessagingService.onRefreshToken = handleFcmToken

    FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
      if (!task.isSuccessful) {
        log.w("Failed to get the token from FCM", task.exception)
      } else {

        // Get new FCM registration token
        val token = task.result
        handleFcmToken(token)
      }
    }

    return this
  }

  private fun forceSubscribe(interest: String) {
    serverSyncHandler.sendMessage(ServerSyncHandler.subscribe(interest))
  }

  /**
   * Subscribes the device to an interest. For example:
   * <pre>{@code pushNotifications.subscribe("hello");}</pre>
   * @param interest the name of the interest
   * @deprecated use addDeviceInterest instead
   */
  @Deprecated("use addDeviceInterest instead", ReplaceWith("addDeviceInterest(interest)"))
  fun subscribe(interest: String) {
    addDeviceInterest(interest)
  }

  /**
   * Subscribes the device to an interest. For example:
   * <pre>{@code pushNotifications.addDeviceInterest("hello");}</pre>
   * @param interest the name of the interest
   */
  fun addDeviceInterest(interest: String) {
    if (!interest.matches(validInterestRegex)) {
      throw IllegalArgumentException(
          "Interest `$interest` is not valid. It can only contain up to 164 characters " +
              "and can only be ASCII upper/lower-case letters, numbers and one of _-=@,.:")
    }

    synchronized(deviceStateStore) {
      val haveInterestsChanged = addInterestToStore(interest)
      if (haveInterestsChanged) {
        serverSyncHandler.sendMessage(ServerSyncHandler.subscribe(interest))
        serverSyncEventHandler.onSubscriptionsChangedListener?.onSubscriptionsChanged(deviceStateStore.interests)
      }
    }
  }

  /**
   * Unsubscribes the device from an interest. For example:
   * <pre>{@code pushNotifications.unsubscribe("hello");}</pre>
   * @param interest the name of the interest
   * @deprecated use removeDeviceInterest instead
   */
  @Deprecated("use removeDeviceInterest instead", ReplaceWith("removeDeviceInterest(interest)"))
  fun unsubscribe(interest: String) {
    removeDeviceInterest(interest)
  }

  /**
   * Unsubscribes the device from an interest. For example:
   * <pre>{@code pushNotifications.removeDeviceInterest("hello");}</pre>
   * @param interest the name of the interest
   */
  fun removeDeviceInterest(interest: String) {
    synchronized(deviceStateStore) {
      val haveInterestsChanged = removeInterestFromStore(interest)

      if (haveInterestsChanged) {
        serverSyncHandler.sendMessage(ServerSyncHandler.unsubscribe(interest))
        serverSyncEventHandler.onSubscriptionsChangedListener?.onSubscriptionsChanged(deviceStateStore.interests)
      }
    }
  }

  /**
   * Unsubscribes the device from all the interests. For example:
   * <pre>{@code pushNotifications.unsubscribeAll();}</pre>
   * @deprecated use clearDeviceInterests instead
   */
  @Deprecated("use clearDeviceInterests instead", ReplaceWith("clearDeviceInterests()"))
  fun unsubscribeAll() {
    clearDeviceInterests()
  }

  /**
   * Unsubscribes the device from all the interests. For example:
   * <pre>{@code pushNotifications.clearDeviceInterests();}</pre>
   */
  fun clearDeviceInterests() {
    setDeviceInterests(emptySet())
  }

  /**
   * Sets the subscriptions state for the device. Any interests not in the set will be
   * unsubscribed from, so this will replace the interest set by the one provided.
   * <br>
   * For example:
   * <pre>{@code pushNotifications.setSubscriptions(Arrays.asList("hello", "donuts").toSet());}</pre>
   * @param interests the new set of interests
   * @deprecated use setDeviceInterests instead
   */
  @Deprecated("use setDeviceInterests instead", ReplaceWith("setDeviceInterests(interests)"))
  fun setSubscriptions(interests: Set<String>) {
    setDeviceInterests(interests)
  }

  /**
   * Sets the subscriptions state for the device. Any interests not in the set will be
   * unsubscribed from, so this will replace the interest set by the one provided.
   * <br>
   * For example:
   * <pre>{@code pushNotifications.setDeviceInterests(Arrays.asList("hello", "donuts").toSet());}</pre>
   * @param interests the new set of interests
   */
  fun setDeviceInterests(interests: Set<String>) {
    interests.find {
      !it.matches(validInterestRegex)
    }?.let {
      throw IllegalArgumentException(
          "Interest `$it` is not valid. It can only contain up to 164 characters " +
              "and can only be ASCII upper/lower-case letters, numbers and one of _-=@,.:")
    }

    synchronized(deviceStateStore) {
      val haveInterestsChanged = replaceAllInterestsInStore(interests)

      if (haveInterestsChanged) {
        serverSyncHandler.sendMessage(ServerSyncHandler.setSubscriptions(deviceStateStore.interests))
        serverSyncEventHandler.onSubscriptionsChangedListener?.onSubscriptionsChanged(deviceStateStore.interests)
      }
    }
  }

  /**
   * @return the set of subscriptions that the device is currently subscribed to
   * @deprecated use getDeviceInterests instead
   */
  @Deprecated("use getDeviceInterests instead", ReplaceWith("getDeviceInterests()"))
  fun getSubscriptions(): Set<String> {
    return getDeviceInterests()
  }

  /**
   * @return the set of subscriptions that the device is currently subscribed to
   */
  fun getDeviceInterests(): Set<String> {
    synchronized(deviceStateStore) {
      return deviceStateStore.interests
    }
  }

  /**
   * Registers a listener for when subscriptions have changed
   *
   * @param listener - the listener object
   * @deprecated use setOnDeviceInterestsChangedListener instead
   */
  @Deprecated("use setOnDeviceInterestsChangedListener instead", ReplaceWith("setOnDeviceInterestsChangedListener(listener)"))
  fun setOnSubscriptionsChangedListener(listener: SubscriptionsChangedListener) {
    setOnDeviceInterestsChangedListener(listener)
  }

  /**
   * Registers a listener for when subscriptions have changed
   *
   * @param listener - the listener object
   */
  fun setOnDeviceInterestsChangedListener(listener: SubscriptionsChangedListener) {
    serverSyncEventHandler.onSubscriptionsChangedListener = listener
  }

  internal fun onApplicationStarted() {
    val deviceMetadata = DeviceMetadata(BuildConfig.VERSION_NAME, Build.VERSION.RELEASE)

    serverSyncHandler.sendMessage(ServerSyncHandler.applicationStart(deviceMetadata))
  }

  /**
   * Sets the user id that is associated with this device.
   * <i>Note: This method can only be called after start. Once a user id has been set for the device
   * it cannot be changed until stop is called.</i>
   * <br>
   * For example:
   * <pre>{@code pushNotifications.setUserId("bob");}</pre>
   * @param userId the id of the user you would like to associate with the device
   * @param callback callback used to indicate whether the user association process has succeeded
   */
  @JvmOverloads
  fun setUserId(userId: String, tokenProvider: TokenProvider?, callback: PushCallback<Void, PushCallbackError> = NoopPushCallback()) {
    if (tokenProvider == null) { // this can happen when using Java
      throw IllegalStateException("Token provider can't be null")
    }
    PushNotifications.tokenProvider[instanceId] = tokenProvider

    if (!startHasBeenCalledThisSession) {
      throw IllegalStateException("Start method must be called before setUserId")
    }

    synchronized(deviceStateStore) {
      if (
          deviceStateStore.setUserIdHasBeenCalledWith != null &&
          deviceStateStore.setUserIdHasBeenCalledWith != userId
      ) {
        throw AlreadyRegisteredAnotherUserIdException(
            "This device has already been registered to another user id.")
      }

      deviceStateStore.setUserIdHasBeenCalledWith = userId
    }

    serverSyncEventHandler.addUserIdCallback(userId, callback)
    serverSyncHandler.sendMessage(ServerSyncHandler.setUserId(userId))
  }

  /**
   * Stops the SDK by deleting all state (both locally and remotely).
   * Calling this will mean the device will cease to receive push notifications.
   *
   * `Start` must be called if you want to use the SDK again.
   */
  fun stop() {
    synchronized(deviceStateStore) {
      val hadAnyInterests = deviceStateStore.interests.isNotEmpty()

      deviceStateStore.interests = mutableSetOf()
      deviceStateStore.startJobHasBeenEnqueued = false
      deviceStateStore.setUserIdHasBeenCalledWith = null
      startHasBeenCalledThisSession = false
      serverSyncHandler.sendMessage(ServerSyncHandler.stop())

      if (hadAnyInterests) {
        serverSyncEventHandler.onSubscriptionsChangedListener?.onSubscriptionsChanged(emptySet())
      }
    }
  }

  /**
   * Clears all the state in the SDK, leaving it in a empty started state.
   *
   * You should call this method when your user logs out of the application.
   */
  fun clearAllState() {
    stop()
    start()
  }
}
