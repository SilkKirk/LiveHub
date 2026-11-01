package com.livehub.platform.douyu

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DouyuParserTest {
  private val json = Json { ignoreUnknownKeys = true; isLenient = true }

  private fun obj(raw: String): JsonObject = json.parseToJsonElement(raw) as JsonObject

  @Test
  fun `categories join the parent name onto the child`() {
    val root = obj(
      """
      {"code":0,"data":{
        "cate1Info":[{"cate1Id":"1","cate1Name":"网游"}],
        "cate2Info":[
          {"cate1Id":"1","cate2Id":"86","cate2Name":"英雄联盟","shortName":"LOL","icon":"a.png"}
        ]
      }}
      """,
    )
    val data = root["data"] as JsonObject
    val categories = DouyuParser.categories(
      data["cate1Info"] as kotlinx.serialization.json.JsonArray,
      data["cate2Info"] as kotlinx.serialization.json.JsonArray,
    )

    assertEquals(1, categories.size)
    assertEquals("86", categories.first().id)
    assertEquals("英雄联盟", categories.first().name)
    assertEquals("网游", categories.first().parentName)
  }

  @Test
  fun `recList maps every field and reports more pages from the total`() {
    val root = obj(
      """
      {"error":0,"data":{"total":100,"list":[
        {"rid":123,"rn":"标题","nn":"主播","rs16":"https://img/1.jpg","av":"https://img/a.jpg","ol":12345}
      ]}}
      """,
    )
    val page = DouyuParser.recList(root, offset = 0, limit = 30)

    assertEquals(1, page.rooms.size)
    val room = page.rooms.first()
    assertEquals("123", room.roomId)
    assertEquals("标题", room.title)
    assertEquals("主播", room.streamerName)
    assertEquals(12345L, room.viewerCount)
    assertTrue(room.isLive)
    assertEquals("30", page.nextOffset)
  }

  @Test
  fun `recList stops when the total is consumed`() {
    val root = obj("""{"data":{"total":30,"list":[{"rid":1,"rn":"t","nn":"n"}]}}""")
    val page = DouyuParser.recList(root, offset = 0, limit = 30)
    assertNull(page.nextOffset)
  }

  @Test
  fun `recList ends when an empty page arrives`() {
    val page = DouyuParser.recList(obj("""{"data":{"total":500,"list":[]}}"""), 0, 30)
    assertTrue(page.rooms.isEmpty())
    assertNull(page.nextOffset)
  }

  @Test
  fun `mixList ends on an empty page`() {
    val page = DouyuParser.mixList(obj("""{"data":{"rl":[]}}"""), page = 3)
    assertNull(page.nextOffset)

    val next = DouyuParser.mixList(obj("""{"data":{"rl":[{"rid":9,"rn":"t","nn":"n"}]}}"""), page = 3)
    assertEquals("4", next.nextOffset)
  }

  @Test
  fun `search drops non anchor results and keeps anchors`() {
    val root = obj(
      """
      {"data":[
        {"type":1,"rid":"111","nickName":"主播A","roomName":"直播中","avatar":"a.jpg","isLive":1},
        {"type":2,"rid":"222","nickName":"视频B"},
        {"type":1,"rid":"333","nickname":"主播C"}
      ]}
      """,
    )
    val page = DouyuParser.search(root, page = 1, pageSize = 20)

    assertEquals(2, page.rooms.size)
    assertEquals(listOf("111", "333"), page.rooms.map { it.roomId })
    assertTrue(page.rooms.first().isLive)
    assertEquals("暂无标题", page.rooms.last().title)
  }

  /**
   * The real payload wraps every anchor in `{"type":1,"anchorInfo":{…}}`.
   *
   * Reading `rid`/`nickName` straight off the element — which is what this mapper used to
   * do — found nothing and silently dropped every result, so search never showed a single
   * room on Douyu.
   */
  @Test
  fun `search reads the fields out of the anchorInfo envelope`() {
    val root = obj(
      """
      {"error":0,"data":{"pageSize":20,"total":915,"relateUser":[
        {"type":1,"anchorInfo":{"rid":475252,"nickName":"孙悟空丨兰林汉",
          "description":"国服第一孙悟空","avatar":"https://apic.douyucdn.cn/a.jpg",
          "isLive":1,"isLoop":0,"videoLoop":0}},
        {"type":1,"anchorInfo":{"rid":11761342,"nickName":"主播B","isLive":2}},
        {"type":2,"anchorInfo":{"rid":999,"nickName":"录像"}}
      ]}}
      """,
    )
    val page = DouyuParser.search(root, page = 1, pageSize = 20)

    assertEquals(listOf("475252", "11761342"), page.rooms.map { it.roomId })
    val first = page.rooms.first()
    assertEquals("孙悟空丨兰林汉", first.streamerName)
    assertEquals("国服第一孙悟空", first.title)
    assertTrue(first.isLive)
    // isLive == 2 means offline, and it must survive as such rather than be dropped.
    assertTrue(!page.rooms.last().isLive)
    // total=915 with 20 already consumed: another page exists.
    assertEquals("2", page.nextOffset)
  }

  @Test
  fun `search stops once the reported total is consumed`() {
    val items = (1..20).joinToString(",") { """{"type":1,"anchorInfo":{"rid":$it,"nickName":"n$it"}}""" }
    val root = obj("""{"data":{"pageSize":20,"total":40,"relateUser":[$items]}}""")

    assertEquals("3", DouyuParser.search(root, page = 1, pageSize = 20).nextOffset)
    assertNull(DouyuParser.search(root, page = 2, pageSize = 20).nextOffset)
  }

  @Test
  fun `search resolves the room id from the room url when rid is absent`() {
    val root = obj("""{"data":[{"type":1,"bkUrl":"https://www.douyu.com/777","nickName":"主播"}]}""")
    val page = DouyuParser.search(root, 1, 20)
    assertEquals("777", page.rooms.firstOrNull()?.roomId)
  }

  /**
   * A captured live `/betard` payload: `cate2_name` is present but empty, the viewer count
   * is `room_biz_all.hot` (a string), and the streamer name is `nickname`.
   *
   * Reading the documented keys only (`cate2_name`, `hotabit`/`hot_num`, `owner_name`)
   * left every room without a category and with a viewer count of 0.
   */
  @Test
  fun `betard reads the live payload fields the site actually sends`() {
    val root = obj(
      """
      {"game":{"tag_name":"英雄联盟"},
       "room":{"room_id":"8682569","room_name":"三团联动户外啵啵",
         "nickname":"冷少icon","owner_name":"冷少icon",
         "show_status":1,"videoLoop":0,"iol":0,
         "cate2_id":1,"cate2_name":"","second_lvl_name":"英雄联盟",
         "room_biz_all":{"hot":"2498932","isvertical":0},
         "room_pic":"https://rpic.douyucdn.cn/p.avif/dy4",
         "owner_avatar":"https://apic.douyucdn.cn/a.jpg"}}
      """,
    )
    val info = DouyuParser.betard(root)

    assertEquals("8682569", info.roomId)
    assertEquals("2498932", info.viewerCount.toString())
    assertEquals("英雄联盟", info.categoryName)
    assertEquals("冷少icon", info.streamerName)
    assertTrue(info.isLive)
  }

  @Test
  fun `betard falls back to the game tag when the room carries no category`() {
    val root = obj("""{"game":{"tag_name":"户外"},"room":{"room_id":"1","show_status":1}}""")
    assertEquals("户外", DouyuParser.betard(root).categoryName)
  }

  @Test
  fun `betard maps room metadata and the live flag`() {
    val root = obj(
      """
      {"room":{"room_id":"9999","room_name":"标题","owner_name":"主播",
       "show_status":1,"videoLoop":0,"hot_num":"12345",
       "cate2_name":"英雄联盟","room_pic":"https://p.jpg","owner_avatar":"https://a.jpg"}}
      """,
    )
    val info = DouyuParser.betard(root)

    assertEquals("9999", info.roomId)
    assertEquals("标题", info.title)
    assertEquals("主播", info.streamerName)
    assertTrue(info.isLive)
    assertEquals(12345L, info.viewerCount)
    assertEquals("英雄联盟", info.categoryName)
  }

  @Test
  fun `betard reports offline rooms`() {
    val offline = DouyuParser.betard(obj("""{"room":{"room_id":"1","show_status":0}}"""))
    assertTrue(!offline.isLive)

    val loop = DouyuParser.betard(obj("""{"room":{"room_id":"1","show_status":1,"videoLoop":1}}"""))
    assertTrue(!loop.isLive)
  }

  @Test
  fun `betard throws when the room payload is missing`() {
    assertThrows<IllegalArgumentException> { DouyuParser.betard(obj("""{"foo":1}""")) }
  }

  @Test
  fun `play builds the full url from rtmp pieces`() {
    val root = obj(
      """
      {"error":0,"data":{
        "rtmp_url":"https://cdn.douyu.com/live",
        "rtmp_live":"abc.flv?wsSecret=1&amp;wsTime=2",
        "cdnsWithName":[{"cdn":"ws-h5","name":"主线路"},{"cdn":"scdn","name":"备用"}],
        "multirates":[{"name":"原画","rate":0,"bit":4000},{"name":"高清","rate":2,"bit":800}]
      }}
      """,
    )
    val play = DouyuParser.play(root)

    assertEquals("https://cdn.douyu.com/live/abc.flv?wsSecret=1&wsTime=2", play.urlFor("ws-h5"))
    assertEquals(2, play.rates.size)
    assertEquals("原画", play.rates.first().name)
  }

  @Test
  fun `play surfaces the platform error`() {
    assertThrows<IllegalArgumentException> {
      DouyuParser.play(obj("""{"error":-5,"msg":"未开播"}"""))
    }
  }

  @Test
  fun `play falls back to the default cdn when none are listed`() {
    val play = DouyuParser.play(
      obj("""{"error":0,"data":{"rtmp_url":"https://h","rtmp_live":"s.flv","cdnsWithName":[]}}"""),
    )
    assertEquals(listOf(DouyuParser.DEFAULT_DOUYU_CDN), play.cdns)
  }
}

class DouyuQualityTest {

  private fun response(rates: List<DouyuRate>, cdns: List<String> = listOf("ws-h5", "scdn")) =
    DyPlayResponse(rtmpUrl = "https://h", rtmpLive = "s.flv", cdns = cdns, rates = rates)

  private val rates = listOf(
    DouyuRate("原画", 0, 8000),
    DouyuRate("蓝光4M", 4, 4000),
    DouyuRate("超清", 3, 2000),
    DouyuRate("高清", 2, 1000),
    DouyuRate("标清", 1, 500),
  )

  @Test
  fun `default selection prefers the source quality`() {
    val selection = DouyuQuality.select(response(rates), preferred = null)
    assertEquals(0, selection.rate)
    assertEquals("原画", selection.rateLabel)
  }

  @Test
  fun `an unavailable preferred rate falls back to source quality`() {
    val selection = DouyuQuality.select(response(rates), preferred = 99)
    assertEquals(0, selection.rate)
  }

  @Test
  fun `an available preferred rate is kept`() {
    val selection = DouyuQuality.select(response(rates), preferred = 2)
    assertEquals(2, selection.rate)
    assertEquals("高清", selection.rateLabel)
  }

  @Test
  fun `cdn selection avoids scdn and prefers alphabetical order`() {
    // "ali" sorts before "ws-h5", and both outrank the scdn fallback.
    assertEquals("ali", DouyuQuality.selectCdn(listOf("scdn", "ws-h5", "ali"), null))
    assertEquals("ws-h5", DouyuQuality.selectCdn(listOf("scdn", "ws-h5"), null))
    assertEquals("scdn", DouyuQuality.selectCdn(listOf("scdn"), null))
    assertEquals(DouyuParser.DEFAULT_DOUYU_CDN, DouyuQuality.selectCdn(emptyList(), null))
    assertEquals("ali", DouyuQuality.selectCdn(listOf("scdn", "ali"), "ali"))
    // An explicitly requested CDN wins even when it is the scdn fallback.
    assertEquals("scdn", DouyuQuality.selectCdn(listOf("ali", "scdn"), "scdn"))
  }

  @Test
  fun `resolveRate understands labels and numbers`() {
    assertEquals(2, DouyuQuality.resolveRate("2", rates))
    assertEquals(0, DouyuQuality.resolveRate("原画", rates))
    assertEquals(4, DouyuQuality.resolveRate("蓝光4M", rates))
    assertEquals(2, DouyuQuality.resolveRate("高清", rates))
    assertEquals(1, DouyuQuality.resolveRate("标清", rates))
    assertNull(DouyuQuality.resolveRate("", rates))
  }

  @Test
  fun `rank orders source quality first`() {
    assertEquals(0, DouyuQuality.rankOf(0))
    assertEquals(2, DouyuQuality.rankOf(2))
    assertEquals(0, DouyuQuality.rankOf(-1))
  }

  @Test
  fun `variants yield exactly one playable url per rate`() {
    val selection = DouyuQuality.Selection(rate = 0, rateLabel = "原画", cdn = "ws-h5")
    val variants = DouyuQuality.variants(response(rates), selection)

    // One getH5Play response is valid for one rate, so the selector must not pretend
    // other CDNs are separate playable streams pointing at the same URL.
    assertEquals(1, variants.size)
    assertEquals("ws-h5", variants.first().lineName)
    assertEquals("原画", variants.first().qualityLabel)
    assertEquals(0, variants.first().qualityCode)
    assertTrue(variants.first().url.isNotBlank())
  }

  @Test
  fun `variants are empty when the response carries no stream path`() {
    val empty = DyPlayResponse(rtmpUrl = "", rtmpLive = "", cdns = listOf("ws-h5"), rates = rates)
    val selection = DouyuQuality.Selection(rate = 0, rateLabel = "原画", cdn = "ws-h5")
    assertTrue(DouyuQuality.variants(empty, selection).isEmpty())
  }

  @Test
  fun `rate options are ordered with source quality first`() {
    val options = DouyuQuality.rateOptions(response(rates))
    assertEquals(listOf(0, 1, 2, 3, 4), options.map { it.rate })
  }

  @Test
  fun `labels cover the documented rate codes`() {
    assertEquals("原画", DouyuQuality.labelFor(0))
    assertEquals("标清", DouyuQuality.labelFor(1))
    assertEquals("高清", DouyuQuality.labelFor(2))
    assertEquals("超清", DouyuQuality.labelFor(4))
    assertNotNull(DouyuQuality.labelFor(99))
  }
}
