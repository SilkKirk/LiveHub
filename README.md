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
| 同步 | 局域网 mDNS 发现 + 配对码配对，HLC + 墓碑式增量同步 |
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

5. **配对即信任**
   配对码 5 分钟有效；配对成功后双方各自持有一个随机共享密钥，之后所有请求都校验
   该密钥。同步只在同一局域网内进行，不依赖任何中心服务器。

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

### Release 签名

`app/build.gradle.kts` 会在仓库根目录存在 `keystore.properties` 且其中的
`storeFile` 指向的文件确实存在时启用 release 签名；否则 release 变体不签名，
CI 仍然可以完成 `assembleRelease` 的编译与混淆校验。

```properties
storeFile=release.jks
storePassword=******
keyAlias=livehub
keyPassword=******
```

---

## 已知限制

以下限制是**平台侧**的，不是实现缺陷。每一条都在代码注释中标注了原因，并且都做了
降级处理，不会让界面卡住或崩溃。

### 抖音：只有一页推荐流，且无法按分区筛选

- `webcast/web/partition/detail/room` 对匿名客户端返回 **HTTP 444**（抖音的反爬状态码），
  带上有效的 `ttwid` cookie 也一样。
- 仍在服务的 `webcast/feed` **忽略** `partition`、`partition_type`、`tag_id` 参数
  —— 任意取值返回同一批房间。
- `count` 参数被服务端硬性截断为 **6 条**，`offset` 也被忽略。

因此抖音只显示一个「推荐」分区，列表就是这 6 个房间，并且不提供翻页。
应用不会"假装"加载更多，而是明确显示"已经到底了"。

### 斗鱼：目录页只有一页

- 旧的 JSON 列表接口（`newRecList`、`mixListV1`）已下线，对所有分区返回
  `{"error":1,"msg":"系统异常"}` 或空数组。
- 目录页 `www.douyu.com/directory/game/<slug>` 仍服务端渲染 **40 个**房间，因此列表
  改为解析该页面。
- `?page=N` 参数可用但**不可靠**：第 2 页与第 1 页有 38/40 个房间重叠，第 3 页又会
  返回第 1 页内容。因此不提供翻页，避免列表里出现重复项。

### 其他

- 虎牙播放地址会被强制降级为 `http://`，因为其 CDN 主机名中的下划线会让 Android
  的 TLS SNI 校验失败。因此 manifest 中允许明文流量。
- 局域网同步要求两台设备处于同一网段，且路由器未开启 AP 隔离。
- 哔哩哔哩与虎牙的接口相对稳定；抖音与斗鱼的接口变更频繁，失效时适配器会返回
  可读错误而不是崩溃。
