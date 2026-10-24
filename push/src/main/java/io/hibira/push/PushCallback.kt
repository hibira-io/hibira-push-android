package io.hibira.push

/**
 * Generic callback to deal with async successes and failures.
 */
interface PushCallback<S, E> {
  fun onSuccess(vararg values: S)

  fun onFailure(error: E)
}

internal class NoopPushCallback<S, E> : PushCallback<S, E> {
  override fun onSuccess(vararg values: S) {}

  override fun onFailure(error: E) {}
}
