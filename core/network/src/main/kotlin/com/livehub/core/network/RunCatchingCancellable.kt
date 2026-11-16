package com.livehub.core.network

import kotlin.coroutines.cancellation.CancellationException

/**
 * [runCatching], except that a cancelled coroutine stays cancelled.
 *
 * `runCatching` catches `Throwable`, and `CancellationException` is a `Throwable` — so a request
 * that was abandoned because its caller went away is indistinguishable from a request that failed.
 * In this codebase that difference is not academic: almost every adapter call sits inside a lenient
 * shape (`runCatching { … }.getOrNull()`, `?: fallback`, `getOrDefault(…)`), so swallowing
 * cancellation does not just lose an error, it **runs the fallback on behalf of nobody**. A
 * cancelled `DouyinAdapter.rooms` used to go on to request the recommendation feed — a second
 * network round trip for a screen that had already moved on — and a cancelled danmaku reconnect loop
 * kept reconnecting.
 *
 * This is the same rule the view models follow by hand (`catch (cancelled: CancellationException) {
 * throw cancelled }`); it exists as a function so the twenty-odd lenient call sites in the platform
 * modules can follow it with one word instead of a rewritten `try`.
 *
 * Not to be used around code that cannot suspend — there is nothing to cancel there, and the longer
 * name would only imply otherwise.
 */
suspend fun <T> runCatchingCancellable(block: suspend () -> T): Result<T> = try {
  Result.success(block())
} catch (cancelled: CancellationException) {
  throw cancelled
} catch (error: Throwable) {
  Result.failure(error)
}
