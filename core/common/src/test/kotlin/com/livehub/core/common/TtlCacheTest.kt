package com.livehub.core.common

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class TtlCacheTest {

  @Test
  fun `getOrPut computes once and caches`() = runTest {
    var now = 0L
    val cache = TtlCache<String, Int>(ttlMillis = 1_000, clock = { now })
    val calls = AtomicInteger()

    val first = cache.getOrPut("a") {
      calls.incrementAndGet()
      1
    }
    val second = cache.getOrPut("a") {
      calls.incrementAndGet()
      2
    }

    assertEquals(1, first)
    assertEquals(1, second)
    assertEquals(1, calls.get())
  }

  @Test
  fun `entries expire after the ttl`() = runTest {
    var now = 0L
    val cache = TtlCache<String, Int>(ttlMillis = 100, clock = { now })
    cache.getOrPut("k") { 1 }

    now = 99
    assertEquals(1, cache.peek("k"))

    now = 100
    assertNull(cache.peek("k"))
  }

  @Test
  fun `never ttl keeps entries forever`() = runTest {
    var now = 0L
    val cache = TtlCache<String, Int>(ttlMillis = TtlCache.NEVER, clock = { now })
    cache.put("k", 7)

    now = Long.MAX_VALUE / 2
    assertEquals(7, cache.peek("k"))
  }

  @Test
  fun `concurrent callers share a single computation`() = runTest {
    val cache = TtlCache<String, Int>(ttlMillis = TtlCache.NEVER)
    val calls = AtomicInteger()

    val results = (1..8).map {
      async {
        cache.getOrPut("shared") {
          calls.incrementAndGet()
          delay(10)
          42
        }
      }
    }.awaitAll()

    assertEquals(8, results.size)
    assertTrueAllEqual(results)
    assertEquals(1, calls.get())
  }

  @Test
  fun `invalidate forces recomputation`() = runTest {
    val cache = TtlCache<String, Int>(ttlMillis = TtlCache.NEVER)
    cache.put("k", 1)
    cache.invalidate("k")
    assertNull(cache.peek("k"))
    assertEquals(2, cache.getOrPut("k") { 2 })
  }

  @Test
  fun `size tracks stored entries`() = runTest {
    val cache = TtlCache<String, Int>(ttlMillis = TtlCache.NEVER)
    cache.put("a", 1)
    cache.put("b", 2)
    assertEquals(2, cache.size())
    cache.invalidateAll()
    assertEquals(0, cache.size())
  }

  private fun assertTrueAllEqual(values: List<Int>) {
    assertEquals(1, values.distinct().size)
  }
}
