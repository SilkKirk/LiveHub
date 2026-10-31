package com.livehub.core.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ViewerCountFormatterTest {

  @Test
  fun `values below ten thousand are shown verbatim`() {
    assertEquals("0", ViewerCountFormatter.format(0))
    assertEquals("1", ViewerCountFormatter.format(1))
    assertEquals("8532", ViewerCountFormatter.format(8532))
    assertEquals("9999", ViewerCountFormatter.format(9999))
  }

  @Test
  fun `values in the ten thousands use the wan suffix`() {
    assertEquals("1万", ViewerCountFormatter.format(10_000))
    assertEquals("1.3万", ViewerCountFormatter.format(12_500))
    assertEquals("9999.9万", ViewerCountFormatter.format(99_999_000))
  }

  @Test
  fun `large values use the yi suffix`() {
    assertEquals("1亿", ViewerCountFormatter.format(100_000_000))
    assertEquals("1.9亿", ViewerCountFormatter.format(186_000_000))
  }

  @Test
  fun `negative values are clamped to zero`() {
    assertEquals("0", ViewerCountFormatter.format(-5))
  }

  @Test
  fun `parse understands the suffixes the platforms emit`() {
    assertEquals(0L, ViewerCountFormatter.parse(null))
    assertEquals(0L, ViewerCountFormatter.parse(""))
    assertEquals(0L, ViewerCountFormatter.parse("--"))
    assertEquals(1234L, ViewerCountFormatter.parse("1,234"))
    assertEquals(12_000L, ViewerCountFormatter.parse("1.2万"))
    assertEquals(12_000L, ViewerCountFormatter.parse("1.2w"))
    assertEquals(150_000_000L, ViewerCountFormatter.parse("1.5亿"))
  }
}
