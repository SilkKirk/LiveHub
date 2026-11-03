# LiveHub

聚合直播 Android 客户端，使用 **Jetpack Compose** 与当前一代 Android 工具链构建。
支持 **斗鱼 / 虎牙 / 抖音 / 哔哩哔哩** 四个平台，并提供基于局域网的点对点
**跨设备同步**（关注列表、分区订阅、播放设置）。

> 仅用于学习与技术交流。所有直播内容与数据来自第三方平台接口，版权归第三方所有；
> 接口可能随平台变更而失效。

---

## 功能

| 模块 | 能力 |
| --- | --- |
| 首页 | 平台切换、分区选择、分区订阅（★ 固定入口）、直播间瀑布流、无限加载 |
| 搜索 | 按平台搜索主播/直播间、搜索历史 |
| 关注 | 一键关注/取消关注；长按拖动排序（顺序本身也会同步）；后台每 30 分钟刷新开播状态 |
| 播放 | Media3 (ExoPlayer) 播放 FLV/HLS，清晰度与线路切换，错误自动重试 |
| 弹幕 | 四平台弹幕接入，滚动弹幕渲染，字号/透明度/显示区域可调，屏蔽词过滤 |
| 同步 | 局域网内按六位匹配码配对（不必输 IP）+ mDNS/网段扫描发现，HLC + 墓碑式增量同步 |
| 设置 | 深色主题、弹幕开关、屏蔽词管理、哔哩哔哩扫码登录 |

---

## 技术栈

- **语言**：Kotlin 2.3.21，Java 17 目标字节码
- **构建**：AGP 9.5.0（内置 Kotlin 支持）、Gradle 9.8.2、Version Catalog
- **UI**：Jetpack Compose（Material 3）、Navigation Compose
- **播放**：AndroidX Media3 1.11.1（ExoPlayer + HLS + DASH）
- **网络**：OkHttp 5（HTTP + WebSocket）
- **存储**：DataStore Preferences
- **图片**：Coil 3
- **序列化**：kotlinx.serialization
- **编译目标**：compileSdk 37.2 / targetSdk 37 / minSdk 26

---

## 模块结构

```
:app                    单 Activity Compose 宿主、DI 容器、播放器与同步 UI
:core:common            退避策略、TTL 缓存、数值格式化（纯 JVM）
:core:model             跨模块共享的数据模型（Platform/Streamer/Category/…）
:core:network           OkHttp 封装、CookieJar、哈希与编码工具（纯 JVM）
:core:data              平台网关 + 重试/缓存策略（纯 JVM）
:core:sync              同步引擎、合并算法、HLC、LAN 服务端与传输层（纯 JVM）
:core:designsystem      Compose 主题与排版
:platform-api           平台适配器契约（LivePlatformAdapter）
:platform:douyu         斗鱼适配器
:platform:huya          虎牙适配器
:platform:douyin        抖音适配器
:platform:bilibili      哔哩哔哩适配器
```

`core:*` 与 `platform:*` 全部是**纯 JVM 模块**，因此签名算法、协议编解码与同步合并
逻辑都能在普通 JVM 单元测试中验证，不需要模拟器或 Robolectric。

---

## 跨设备同步设计

需求参考微信输入法的跨设备同步，实现上刻意避开了"上传覆盖"这种朴素做法：

1. **混合逻辑时钟（HLC）**
   每条记录带 `(physical, counter)` 时间戳。它像 Lamport 时钟一样保证全序，同时
   贴近墙上时钟，因此同步日志里的时间对人类仍然有意义。设备间时钟偏移不会导致
   旧数据覆盖新数据。

2. **墓碑（Tombstone）**
   取消关注不是删除记录，而是写入 `deleted = true` 的墓碑。墓碑保留 30 天，或在
   所有配对设备确认收到之后才回收。这样"在平板上取消关注"不会被"手机上还没同步的
   关注"复活。

3. **设备 ID 决胜**
   两个设备产生相同 HLC 时，用设备 ID 比较，保证任意两台设备独立得出相同的合并
   结果。

4. **幂等增量传输**
   每个同步包带 `(deviceId, sequence)`。接收方记录已应用的最大序号，重复请求直接
   拒绝——网络重试不会产生重复数据。

   推送只带**增量**：一次交换以 `pull` 的响应作为对端已有内容的证明，凡是双方版本
   相同的记录都不再发送，因此第二次同步的请求数减半。超大集合按
   `SyncPayload.MAX_RECORDS_PER_PAYLOAD`（500 条）分片，每片各自持有独立的
   `sequence`，所以部分传输失败后重试不会被当成重放而丢弃。

5. **配对即信任**
   一台设备显示六位匹配码，另一台输入即可关联，不需要填 IP 或端口——与微信输入法的
   跨设备配对一致。匹配码 5 分钟有效、只可用一次，并按来源地址单独限制尝试次数（因为
   配对会扫描整个网段，其他设备也会收到这次请求，全局计数会误伤）。配对成功后双方各自
   持有对方签发的随机密钥，之后所有请求都校验该密钥。同步只在同一局域网内进行，不依赖
   任何中心服务器。

同步范围可单独开关：关注列表、分区订阅、播放设置、搜索历史。

---

## 平台协议实现

各平台的签名与协议细节集中实现，并有对应单元测试：

| 平台 | 关键实现 | 测试覆盖 |
| --- | --- | --- |
| 斗鱼 | `ub98484234` 签名（原生 Kotlin 实现，无需 JS 引擎）、`@=value/` 弹幕协议、小端帧 | 签名公式、弹幕编解码、JSON 映射、清晰度选择 |
| 虎牙 | Tars/JCE 二进制编解码、`wsSecret` 防盗链签名、HTML 内嵌流信息解析 | Tars 读写往返、签名参数顺序、弹幕解码、GBK 回退 |
| 抖音 | `X-Bogus`/`a_bogus` 签名、Protobuf 推送帧解码、gzip/zlib 解压 | Protobuf 编解码、弹幕字段提取、签名确定性 |
| 哔哩哔哩 | WBI mixinKey 签名、`w_rid` 列表签名、16 字节大端弹幕帧与 zlib 递归解析 | 签名公式、mixinKey 置换表、帧编解码、Cookie 合并 |

---

## 构建与验证

**本项目不在本地打包**：所有编译、单元测试、Lint 与 APK 产物都由 GitHub Actions 完成。

```
.github/workflows/ci.yml           构建 + 单元测试 + Lint + APK 产物 + 仪器测试
.github/workflows/diagnostics.yml  仅编译并上传完整编译日志（快速反馈）
```

CI 步骤：

1. JDK 17 (temurin) + Gradle 缓存
2. 安装 `platforms;android-37.2` 与 `build-tools;37.0.0`
3. `./gradlew :app:lintDebug`
4. `./gradlew test`（全模块 JVM 单元测试）
5. `./gradlew :app:assembleDebug` 与 `:app:assembleRelease`
6. 上传测试报告与 debug APK
7. 在 API 34 模拟器上运行 `connectedDebugAndroidTest`

产物在 Actions 的 **Artifacts → livehub-debug-apk** 下载。

### 发布正式版（Release）

推一个 `vMAJOR.MINOR.PATCH` 标签即可发布，版本号不需要手工改：

```bash
git tag -a v0.9.1 -m "LiveHub 0.9.1"
git push origin v0.9.1
```

`.github/workflows/release.yml` 会从标签推导 `versionName`（`v0.9.1` → `0.9.1`）与
`versionCode`（`MAJOR*10000 + MINOR*100 + PATCH`），构建签名 APK 并附到 GitHub Release。
标签格式不合法时任务会直接失败，而不是产出一个版本号错误的包。

**签名密钥**保存在仓库的 Actions secrets 中（`RELEASE_KEYSTORE_BASE64`、
`RELEASE_STORE_PASSWORD`、`RELEASE_KEY_ALIAS`、`RELEASE_KEY_PASSWORD`）。Android 要求
覆盖安装的包签名一致，所以**这把密钥不能换**：换了就必须先卸载再装，用户数据随之丢失。
`versionCode` 必须单调递增，否则新包会被当作降级包拒绝安装——上面那套推导保证它不会
倒退。

发布步骤会用 `apksigner` 打印证书，并在 release 包被 debug 密钥签名时让任务失败。

> 本次会话之前的 `v0.1.0`–`v0.8.0` 是没有密钥时构建的 **debug** 包
> （`applicationId` 带 `.debug` 后缀、`versionCode` 恒为 1），与正式包包名不同，
> 因此可以并存，互不干扰升级链。

### 本地构建（可选）

`app/build.gradle.kts` 在仓库根目录存在 `keystore.properties` 时启用 release 签名；
否则 release 变体不签名，CI 仍能完成 `assembleRelease` 的编译与混淆校验。

```properties
storeFile=../livehub-release.jks
storePassword=******
keyAlias=livehub
keyPassword=******
```

---

## 已知限制

以下限制是**平台侧**的，不是实现缺陷。每一条都在代码注释中标注了原因，并且都做了
降级处理，不会让界面卡住或崩溃。

### 抖音：分区靠签名，推荐流只有一页

- 分区列表 `webcast/web/partition/detail/room/v2/` **需要 `a_bogus` 签名**：不带签名时
  `/room/` 直接返回 **HTTP 444**，`/room/v2/` 更隐蔽——HTTP 200 加一个**零长度 body**，
  看起来就像"这个分区没有房间"。带有效签名时返回 `status_code = 0` 和按分区过滤的真实
  房间（实测每个分区 15 个）。
- 分区表本身没有接口可取：`partition` 参数只**过滤**房间，不会枚举分区，所以 9 组 /
  306 个分区的目录随 APK 打包在 `assets/categories/douyin_categories.json` 里
  （与参考项目 `dtv_mobile` 的同名资源逐字节相同）。
- 仍在服务的 `webcast/feed` **忽略** `partition`、`partition_type`、`tag_id` 参数，
  `count` 被服务端截断为 **6 条**、`offset` 也被忽略。它是签名被拒时的降级路径：
  此时列表会显示推荐流而不是所选分区，属于真实降级而非正常路径。
- 搜索接口参考项目没有实现，因此无从对照；UI 已如实说明"只支持按房间号搜索"。

「已经到底了」的提示仍然保留：feed 只有一页，分区页在没有 `has_more` 时才判到底。

### 斗鱼：三级分类与翻页均已接入

早期版本把斗鱼的列表改成抓取 `www.douyu.com/directory/game/<slug>` 的 HTML，并据此
记下"只有一页、且没有三级分类"。这两条结论都是错的，原因只是 `mixListV1` 被用错了
分区 id（`2_86` 而不是 `2_1`）：

- `gapi/rkc/directory/mixListV1/2_<cate2Id>/<page>` 正常翻页，第 1、2、3 页的房间 id
  零重叠；`limit` 生效，返回条数不足即表示该分区到底。
- 三级分类来自 `capi/v1/getThreeCate?tag_id=<cate2Id>`，房间走
  `mixListV1/3_<cate3Id>/<page>`。

`DouyuMixListFixtureTest` 用真实抓包固化了这两条路径，字段改名会在这里失败。

### 哔哩哔哩：热门页只有一页，搜索的房间在另一个结果桶里

- **热门页没有第二页。** `index/getList` 无视 `page` 与 `page_size`：`page_size=5`、
  `=20`、`=200` 三个取值都返回同样的 11 个模块共 **110** 个房间，第 2 页返回的 110 个里
  有 **97** 个与第 1 页重复（第 5 页同样）。因此热门分区按"单页"处理，翻页按钮不再出现；
  旧实现按房间数判"还有下一页"，结果是网格无限追加自己的副本。
- **分区列表的页大小由服务端决定，且不固定。** 实测 `1:145` 每页 20 条、`2:249` 27 条、
  `2:86` 40 条；安静分区会给**1 条**房间并同时给出 `has_more = 1`（`2:1048`、`2:1096`），
  `2:575` 是 3 条。所以"这页满了没有"不能当成"还有没有下一页"，只能读 `data.has_more`。
- **搜索的直播间在 `result.live_room`，不在 `result.live_user`。** 参考项目
  `dtv_mobile` 只读 `live_user`，但抓包显示那个桶**每页固定 6 条且与页码无关**
  （第 1、2 页是同样 6 个 id），还没有 `title` 和 `online`，多数根本没开播。`live_room`
  每页 40 条，字段齐全。这里**有意偏离参考项目**，依据是抓到的响应而不是参考实现。
- **房间详情必须走 `getH5InfoByRoom`。** `room/v1/Room/get_info` 已经不带 `uname`、
  `anchor_info`、`watched_show`（仍带 `title`/`live_status`/`online`/`user_cover`），
  照它渲染会让播放页的主播名与头像为空、观众数恒为 0。关注列表的轮询仍用
  `Room/get_info`（便宜，参考项目也只在那里用它）。

上述四条都由 `BilibiliPaginationTest` 与 `BilibiliRoomDetailTest` 用真实抓包固定下来。

### 其他

- 虎牙播放地址会被强制降级为 `http://`，因为其 CDN 主机名中的下划线会让 Android
  的 TLS SNI 校验失败。因此 manifest 中允许明文流量。
- 局域网同步要求两台设备处于同一网段，且路由器未开启 AP 隔离。
- 抖音与斗鱼的接口变更频繁，失效时适配器会返回可读错误而不是崩溃。哔哩哔哩的接口
  同样会悄悄改字段（`Room/get_info` 就是丢了字段而没改版本号），所以它的房间详情
  由一个带抓包 fixture 的测试守着。
