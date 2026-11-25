package com.livehub.app.ui.components

import com.livehub.core.model.Platform
import com.livehub.core.model.Streamer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The drag that reorders 特别关注 avatars.
 *
 * The defect this exists for is a *sequence*: the second slot of one drag was computed against the
 * order from before the first slot, so it resolved to the slot the avatar was already in and the drag
 * stopped after one position. A single-step test would have been green throughout.
 *
 * Pure JVM: `AvatarReorder` takes pixels and a list, no `Density` and no Compose. Driving a real
 * long-press-drag under Robolectric is the part that is unreliable, and it is not where the bug was.
 */
class AvatarReorderTest {

  private val a = Streamer(platform = Platform.DOUYU, roomId = "a", streamerName = "A", isLive = true)
  private val b = Streamer(platform = Platform.DOUYU, roomId = "b", streamerName = "B", isLive = true)
  private val c = Streamer(platform = Platform.DOUYU, roomId = "c", streamerName = "C", isLive = true)

  /** Applies a move the way the store does, so the next step is asked about the new order. */
  private fun MutableList<Streamer>.moveTo(key: String, index: Int) {
    val item = first { it.key == key }
    remove(item)
    add(index, item)
  }

  @Test
  fun `less than a slot moves nothing`() {
    val reorder = AvatarReorder(slotPx = 100f)
    reorder.start()

    assertNull(reorder.nextTarget(deltaX = 99f, streamers = listOf(a, b, c), key = a.key))
  }

  /**
   * The regression: two slots, delivered as a slow drag delivers them — one at a time, with the list
   * changing in between.
   */
  @Test
  fun `two slots fed one at a time move the avatar twice`() {
    val reorder = AvatarReorder(slotPx = 100f)
    val order = mutableListOf(a, b, c)
    reorder.start()

    val first = reorder.nextTarget(deltaX = 100f, streamers = order, key = a.key)
    assertEquals("the first slot takes A to index 1", 1, first)
    order.moveTo(a.key, first!!)

    val second = reorder.nextTarget(deltaX = 100f, streamers = order, key = a.key)
    assertEquals(
      "the second step is measured against the order the first one produced, so it is index 2",
      2,
      second,
    )
  }

  /** Dragging three places in one gesture is the reason the accumulator resets instead of piling up. */
  @Test
  fun `one long delta covering two slots moves two places at once`() {
    val reorder = AvatarReorder(slotPx = 100f)
    reorder.start()

    assertEquals(2, reorder.nextTarget(deltaX = 250f, streamers = listOf(a, b, c), key = a.key))
  }

  /**
   * At either end the accumulator keeps its distance, so reversing answers on the first pixel back
   * rather than after re-covering the whole slot.
   */
  @Test
  fun `a drag that runs out of room keeps its distance`() {
    val reorder = AvatarReorder(slotPx = 100f)
    val order = mutableListOf(a, b, c)
    reorder.start()

    assertNull("there is nothing to the left of the first avatar", reorder.nextTarget(-100f, order, a.key))
    assertEquals("reversing one slot moves right immediately", 1, reorder.nextTarget(200f, order, a.key))
  }

  @Test
  fun `a new gesture starts from zero`() {
    val reorder = AvatarReorder(slotPx = 100f)
    val order = mutableListOf(a, b, c)
    reorder.start()
    assertNull(reorder.nextTarget(deltaX = 60f, streamers = order, key = a.key))

    reorder.start()
    assertNull(
      "the 60px of the previous gesture do not carry over",
      reorder.nextTarget(deltaX = 60f, streamers = order, key = a.key),
    )
    assertEquals(1, reorder.nextTarget(deltaX = 40f, streamers = order, key = a.key))
  }
}
