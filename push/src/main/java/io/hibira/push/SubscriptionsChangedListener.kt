package io.hibira.push

/**
 * The listener interface for when Interest subscriptions change
 */
interface SubscriptionsChangedListener {
  /**
   * Called when Interests change
   * Also useful when migrating from a 3rd-party service, as new Interests will be delivered asynchronously from the push servers.
   *
   * @param interests the most up-to-date interests
   */
  fun onSubscriptionsChanged(interests: Set<String>)
}
