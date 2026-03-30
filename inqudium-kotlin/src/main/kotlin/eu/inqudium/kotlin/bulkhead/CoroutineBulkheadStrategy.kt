package eu.inqudium.kotlin.bulkhead

import eu.inqudium.core.bulkhead.BulkheadStateMachine
import eu.inqudium.core.bulkhead.InqBulkheadFullException
import java.time.Duration

/**
 * The coroutine-based implementation of the bulkhead strategy.
 */
class CoroutineBulkheadStrategy(private val bulkheadName: String) {

  /**
   * Decorates a suspending block of code with bulkhead protection.
   */
  suspend fun <T> decorate(callId: String, stateMachine: BulkheadStateMachine, block: suspend () -> T): T {

    // Duty 1: Wait / Acquire (Non-blocking / Fail-fast)
    if (!stateMachine.tryAcquireNonBlocking(callId)) {
      throw InqBulkheadFullException(
        callId, bulkheadName, stateMachine.concurrentCalls, -1
      )
    }

    // Duty 3 (Start): Measurement
    val startNanos = System.nanoTime()
    var businessError: Throwable? = null

    return try {
      // Execute the suspending business logic.
      // The underlying thread is free to do other work while this suspends!
      block()
    } catch (t: Throwable) {
      businessError = t
      throw t
    } finally {
      // Duty 2: Guaranteed Release
      // In Coroutines, finally is entirely safe to use around suspend functions!

      // Duty 3 (End): Measurement calculation
      val rtt = Duration.ofNanos(System.nanoTime() - startNanos)
      stateMachine.releaseAndReport(callId, rtt, businessError)
    }
  }
}
