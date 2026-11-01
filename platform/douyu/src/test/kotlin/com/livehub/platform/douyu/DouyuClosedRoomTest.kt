package com.livehub.platform.douyu

import com.livehub.core.model.Platform
import com.livehub.platform.api.PlatformOfflineException
import com.livehub.platform.api.PlatformProtocolException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What happens when `betard` answers with HTML instead of JSON.
 *
 * Douyu serves a "您观看的房间已被关闭" notice for rooms that the directory still
 * advertises but has since taken down. The viewer used to get `betard 返回了无法解析的
 * 内容 (HTTP 200)` for that, which reads as a bug in the app rather than as "this room
 * closed". The distinction matters because the two cases need different wording.
 */
class DouyuClosedRoomTest {

  private val closedRoomHtml = """
    <!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Transitional//EN" "http://www.w3.org/TR/xhtml1/DTD/xhtml1-transitional.dtd">
    <html xmlns="http://www.w3.org/1999/xhtml">
    <head><title>提示信息 -斗鱼</title></head>
    <body>
      <div class="error">
        <span><p>您观看的房间已被关闭，请选择其他直播进行观看哦！</p></span>
      </div>
    </body>
    </html>
  """.trimIndent()

  private val genericHtml = "<!DOCTYPE html><html><body>maintenance</body></html>"

  @Test
  fun `a closed room notice is reported as offline, not as a parse failure`() {
    val error = assertThrows(PlatformOfflineException::class.java) {
      DouyuResponseRules.requireJson(closedRoomHtml, code = 200, label = "betard")
    }

    assertTrue(
      error.message!!.contains("已关闭"),
      "the message should say the room closed, was: ${error.message}",
    )
    assertEquals(Platform.DOUYU, error.platform)
  }

  @Test
  fun `any other HTML page is reported as a protocol problem with usable wording`() {
    val error = assertThrows(PlatformOfflineException::class.java) {
      DouyuResponseRules.requireJson(genericHtml, code = 200, label = "betard")
    }

    assertTrue(error.message!!.contains("网页"), "was: ${error.message}")
  }

  @Test
  fun `a body that is not JSON at all is still a protocol error`() {
    val error = assertThrows(PlatformProtocolException::class.java) {
      DouyuResponseRules.requireJson("not json at all", code = 200, label = "betard")
    }

    assertTrue(error.message!!.contains("无法解析"), "was: ${error.message}")
    assertTrue(error.message!!.contains("HTTP 200"), "was: ${error.message}")
  }

  @Test
  fun `valid JSON is returned untouched`() {
    val body = """{"room":{"room_id":"226037","show_status":1}}"""
    val parsed = DouyuResponseRules.requireJson(body, code = 200, label = "betard")

    assertEquals("226037", parsed["room"]!!.let { (it as kotlinx.serialization.json.JsonObject)["room_id"]!!.toString().trim('"') })
  }

  @Test
  fun `leading whitespace before the doctype is still detected`() {
    assertThrows(PlatformOfflineException::class.java) {
      DouyuResponseRules.requireJson("   \n\t$closedRoomHtml", code = 200, label = "betard")
    }
  }
}
