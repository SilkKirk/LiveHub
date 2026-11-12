# 迭代记录

> **记录格式**：从第十六轮起，每一轮都以「### 本轮验证」表格收尾（Lint / JVM 单元测试 /
> 仪器测试 / Release assembly）。第一到第五轮（含最上方的里程碑小节）写在约定形成之前，
> 没有这张表，**也不打算用现在编的数字补上**——那些 CI 产物早已不存在，补写就只能是编造。
> 需要这段历史的，看 git log 与当时提交信息。
>
> **新增一轮写在文件末尾**，不要插在中间，也不要写进 `docs/rounds/`（那是历史归档）。
> 主文件从第五十九轮起：这份记录在第六十三轮时已经 5919 行 / 340 KB，而每一轮开工都要读它，
> 「上一轮改了什么」要翻很久。旧轮次见下方「归档索引」。

## 里程碑：全链路 CI 绿灯（commit `b99da6a`）

GitHub Actions 运行 `41240663433` 全部通过：

| 步骤 | 结果 |
| --- | --- |
| Static checks (`:app:lintDebug`) | ✅ 0 error |
| JVM unit tests (全模块) | ✅ 通过 |
| Assemble debug | ✅ |
| Assemble release (minify + shrink) | ✅ |
| Upload debug APK | ✅ 28 MB artifact |
| Instrumented tests (API 34 emulator) | ✅ 4/4 通过 |

产物 `livehub-debug-apk` 已下载校验：包含 `AndroidManifest.xml`、`resources.arsc`、
22 个 `classes*.dex`，可安装。

---

## 已实现并验证的功能

### 平台接入（4 个）
- **斗鱼**：分区树、推荐流、房间搜索、`ub98484234` 原生签名、`getH5Play` 解析、
  清晰度选择、小端弹幕帧协议
- **虎牙**：`getLiveList` 分类流、搜索、HTML 内嵌流信息抓取、`wsSecret` 防盗链签名、
  Tars/JCE 二进制弹幕
- **抖音**：分区流、搜索、`room/web/enter` 解析、`X-Bogus`/`a_bogus` 签名、
  Protobuf 推送帧 + gzip/zlib 解压
- **哔哩哔哩**：分区流（含签名 `w_rid`）、热门流、搜索、`getRoomPlayInfo` 播放地址、
  WBI 签名弹幕信息、16 字节大端帧 + zlib 递归解析、QR 登录

### 跨设备同步
- HLC（混合逻辑时钟）排序
- 墓碑式删除。~~保留 30 天~~ —— 这条是错的：第三十二轮删掉了按时间清理，第三十八轮改成
  "不高于所有已配对设备的水位才回收"，见 `SyncMerge.prune`。按时间清会在离线最久的设备上
  把删除复活。
- 设备 ID 决胜，保证收敛
- `(deviceId, sequence)` 幂等去重
- 配对码 + 共享密钥鉴权（**只有 `/pair` 无凭据**；`/pull`、`/push` 必须验密钥。
  第五十九轮之前它们共用了一条"secret 为空即放行"的判据，是个可被同网段任意程序利用的洞）
- LAN HTTP 服务端 + 套接字客户端 + mDNS 发现
- 同步范围可单独开关

### UI
- 首页（平台切换 / 分区 / 瀑布流 / ~~无限加载~~）—— **滚动到底加载下一页在第五十九轮之前
  从未真正触发过**：`derivedStateOf` 在 `remember` 里捕获了首次组合的空 `UiState`，
  `hasMore` 恒为 false。同页与搜索页同一缺陷。见第五十九轮记录。
- 搜索（历史 / 结果网格）
- 关注（按开播状态排序）
- 播放器（Media3、清晰度切换、错误重试）
- 弹幕层（滚动、屏蔽词、透明度/字号/区域）
- 同步页（配对、设备列表、同步记录）
- 设置页（主题、弹幕、屏蔽词）

---

## 已修复的真实缺陷

这些不是测试写错，而是实现里的 bug，均由 CI 暴露并修复：

1. **`ViewerCountFormatter` 舍入错误**
   `kotlin.math.round` 是银行家舍入，`12500` 显示成 `1.2万` 而非 `1.3万`。
   改为整数运算 `(count * 10 + unit / 2) / unit`，同时避免 `String.format` 在
   某些语言环境下输出 `1,3`。

2. **`Crypto.wbiEncode` 百分号编码小写**
   `中` 被编码成 `%e4%b8%ad`，而 B 站服务端按大写比较，签名必然失败。
   改用独立的 `UPPER_HEX` 常量（`md5Hex` 仍需小写）。

3. **`DouyinProto.Field.asLong()` 对大 varint 解码错误**
   原先按大端字节拼接，`123456` 被解成 `12633095`。改为按 varint 7 位分组解析。

4. **`DouyinSigner.aBogus` 十六进制溢出**
   `salt.take(8).toInt(16)` 对任何大于 `0x7FFFFFFF` 的前缀抛
   `NumberFormatException`，即约 50% 的请求会直接崩溃。改为 `toLong(16).toInt()`。

5. **虎牙 `Tars.Reader.readStruct` 不消费 `STRUCT_END`**
   读完嵌套结构后指针停在终止符上，导致结构之后的字段全部读成 0。已补上终止符消费。

6. **虎牙 `tx` CDN 参数改写无效**
   原代码匹配 `&ctype=tars_mp`，但 `ctype` 是 `signQuery` 生成的**第一个**参数，
   前面没有 `&`，替换永远不生效。改为不带前导 `&` 的匹配。

7. **虎牙心跳帧长度错误且不可验证**
   硬编码的字节数组实际 107 字节（注释写 111），且无法校验。改为用 Tars 编码器
   现场构造，长度自洽且可被单元测试验证。

8. **斗鱼"多线路"其实是同一个流**
   `getH5Play` 的一次响应只对应一个 rate，`cdnsWithName` 只是可用 CDN 列表。
   原实现为每个 CDN 生成一个 variant，导致选择器里出现多条指向同一 URL 的重复项。
   改为每次解析返回一个可播放 variant，并额外提供 `rateOptions()` 供清晰度菜单使用。

---

## 测试覆盖

> 这张表是第一轮结束时写的**历史快照**，数字停在当时，没有随每轮更新——补数字容易，补得准
> 很难，而一个假的"最新"比一个标明的"当时"更糟。当前规模以 CI 为准：第六十四轮为
> **731 个 JVM 用例 + 43 张仪器截图**。各模块的测试文件名本身就是覆盖清单，比这张表更可靠。

| 模块 | 测试数（第一轮） | 覆盖内容 |
| --- | --- | --- |
| `core:common` | 16 | 退避策略、TTL 缓存（含并发单飞）、数值格式化 |
| `core:sync` | 51+ | HLC、合并算法、墓碑剪枝、同步引擎、协调器、LAN 服务端 |
| `platform:douyu` | 43 | 签名公式、弹幕编解码、JSON 映射、清晰度选择 |
| `platform:huya` | 34 | Tars 编解码往返、防盗链签名、心跳、GBK 回退 |
| `platform:bilibili` | 29 | 列表/WBI 签名、mixinKey 置换、帧编解码、Cookie |
| `platform:douyin` | 20 | Protobuf、推送帧、签名、URL 安全 |
| `:app` (instrumented) | 4 | 容器装配、DataStore 往返、关注列表、同步包生成 |

---

## 归档索引

本文件从第五十九轮起。更早的轮次原样搬进了 `docs/rounds/`，**没有改写、没有摘要**——引用的
每一轮正文都还在，只是换了个文件。这样做的理由很实际：这份记录在第六十三轮时已经 5919 行 /
340 KB，而每一轮开工都要读它，找一个「上一轮改了什么」要翻半天。

第五十九轮是一个自然的切点：它是"审计 + 修一批"这条线开始的地方，之前的轮次是一次次独立的
功能与缺陷处理。

| 文件 | 覆盖范围 |
| --- | --- |
| [`rounds/02-20.md`](rounds/02-20.md) | 第二轮 – 第二十轮（平台接入、真实网络验证、弹幕层） |
| [`rounds/21-40.md`](rounds/21-40.md) | 第二十一轮 – 第四十轮（密度、四平台逐项审计、同步） |
| [`rounds/41-58.md`](rounds/41-58.md) | 第四十一轮 – 第五十八轮（抖音登录与搜索、聚合入口、特别关注） |
| 本文件 | 第五十九轮起 |

---

## 第五十九轮迭代：无限加载从未触发，以及一个能被同网段任意程序读走的洞

这一轮不是从待办里挑的，是对整个项目做了一次审计后按"用户每天都会碰到"排序得到的两件事。
审计读了 176 个 Kotlin 文件、CI 配置、39 张截图与全部迭代记录。

### 缺陷一：首页和搜索页的"加载更多"从来没被派发过

两处的写法一模一样：

```kotlin
val shouldLoadMore by remember {
  derivedStateOf {
    state.hasMore && last >= state.rooms.size - 4 && state.rooms.isNotEmpty()
  }
}
```

`UiState` 是**不可变 data class**，不是 snapshot state。`derivedStateOf` 只跟踪它内部
读到的 snapshot state（这里是 `gridState.layoutInfo`），重算时读 `state` 读到的是**闭包
捕获的那个实例**。而 `remember` 没有 key，捕获的是**首次组合**的实例。

冷启动那一刻：`rooms = emptyList()`、`hasNextPage = false`。于是 `state.hasMore` 恒为
`false`，`LaunchedEffect` 永远不派发 `LoadMore`。

**它为什么能活过五十八轮**：切到关注页再切回首页会销毁并重建这个 composable，`remember`
重新执行、捕获到一个已经有数据的 `state`，于是它"有时能用"。搜索页没有这个运气——它
**总是**在搜索之前被进入，捕获的空结果集是必然的，所以按关键词翻页**一次都没有工作过**。

修法是 `rememberUpdatedState(state)`，派生值改读 `current`。

**为什么现有测试抓不到**：`CategoryPaginationTest` 与 `SearchPagingTest` 都直接调
`viewModel.onAction(UiAction.LoadMore)`。它们测的是 ViewModel 的请求形状，没有一条测试
真的把列表滚到底。新增的 `InfiniteScrollTest` 三条：

| 用例 | 断言 |
| --- | --- |
| 首页滚动到底 | **先组合空态**、再推 12 条进去、滚到 index 11 → 必须派发 `LoadMore` |
| 搜索页滚动到底 | 同上，必须派发 `LoadMoreSearch` |
| 单页平台（`hasNextPage = false`） | 滚到底**不得**派发，否则修复会变成对抖音 feed 无限打请求 |

第一条和第二条的"先空后满"是**这个测试的全部价值**：直接组合一个已填充的 state，
在坏代码上也会通过。

本地用 Python 把两个判据等价跑了一遍（runbook 要求的做法，本机不跑 Gradle）：

```
broken -> home=False, search=False
fixed  -> home=True,  search=True
ALL CHECKS PASSED
```

### 缺陷二：未配对设备只要 `secret: ""` 就能拉走关注列表和登录 cookie

`SyncHttpServer` 的 `handlePull` / `handlePush` 判据是：

```kotlin
if (peer == null && !acceptsUnpaired(envelope)) return Response(403, ...)

private fun acceptsUnpaired(envelope: SyncEnvelope): Boolean =
  envelope.secret.isBlank() && handler.peerById(envelope.deviceId) == null
```

`acceptsUnpaired` 本来是给**配对**用的（配对时对方还没有密钥可出示，六位码就是凭据）。
把它用在数据路径上，等于：**同一 Wi-Fi 下任何程序，`deviceId` 随便填一个没配对过的值、
`secret` 留空，`GET /livehub/v1/pull?scope=credentials` 就能拿到 B 站和抖音的会话
cookie**，还能 push 覆盖关注列表。

这与 `SyncModels.kt` 的注释（"服务端在读到 payload 之前就用配对密钥校验过请求"）和
README 的"只发给已配对设备"直接冲突。

**为什么现有测试抓不到**：`pull rejects an unpaired peer` 用的是 `secret = "wrong"`——
非空，恰好绕开了唯一有洞的那条路径。

改法：`handlePull` / `handlePush` 一律 `authenticate(envelope) ?: 403`，删掉
`acceptsUnpaired`。新增两条测试用 `secret = ""`（正是漏掉的那个输入），并且不只断言
403，还断言 `pullCalls` / `pushCalls` 仍为 0——一个"先构造 payload 再回 403"的服务端
照样把数据泄进了响应体。

顺带删掉 `SyncHttpServer` 的 `localSecret` 构造参数：它**从头到尾没有被读过**，正是这个
洞的历史来源。

### 顺带清理

审计还查出七个声明了但零引用的依赖。全仓库 import 计数：

| 依赖 | 引用数 | 说明 |
| --- | --- | --- |
| `androidx.navigation:navigation-compose` | 0 | 导航是 `UiState.screen` 枚举，没有 NavHost |
| `media3-exoplayer-dash` | 0 | `LivePlayer` 只在 HLS 与 progressive 之间选 |
| `media3-datasource-okhttp` | 0 | 用的是 `DefaultHttpDataSource`（要自定义 Referer） |
| `org.bouncycastle:bcprov` | 0 | 签名全是手写 MD5/RC4/WBI/a_bogus，四个平台模块都声明了 |
| `org.jmdns:jmdns` | 0 | 发现走 Android `NsdManager`，`proguard-rules.pro` 里还留着它的 `-keep` |
| `io.mockk` | 0 | 测试一个都没用 |
| `app.cash.turbine` | 0 | 同上 |

另外 `core/data`、`core/sync` 与四个 `platform:*` 的 `build.gradle.kts` 里
`testImplementation(libs.kotlinx.coroutines.test)` 都写了两遍。

`AppContainer.mediaClient` 与它唯一的用途 `HttpClients.mediaBuilder()` 也是死的
（`LivePlayer` 自己建 `DefaultHttpDataSource`），一并删掉；顺手把 `webSocketClient` 那段
内联配置收成 `HttpClients.webSocketBuilder()`，让"为什么 readTimeout 是 0"有个能写注释的
地方。

文档纠偏三处：README 的 `compileSdk 37.2`（实际是 `37`）、"无限加载"（本轮之前是死的）、
ITERATIONS 的"墓碑保留 30 天"（第三十二轮就删了按时间清理）。

### 本轮踩的坑（都是我自己的错）

第一次推送 CI 红了，两条编译错误：

1. **断言参数顺序**。我按 runbook 里那条 Jupiter 规则把 `:app` 的测试"统一"成
   `assertTrue(cond, "msg")`。但 `:app` 用的是 `org.junit.Assert`（JUnit 4），
   message 在**第一位**——方向改反了。runbook 那条规则本身没错，漏掉的前提是
   **同一个仓库里两套 API 顺序相反**，判断依据只能是文件顶部的 import。已把这条补进
   runbook 第四节，并写明"不是看仓库里大多数人怎么写"。
2. **`"$prefix主播$index"`**。中文字符是合法的 Kotlin 标识符字符，模板把 `prefix主播`
   解析成一个标识符，报 `Unresolved reference`。改成 `"${prefix}主播$index"`。
   全仓库扫了一遍 `"$ident<CJK>` 这个形态，没有第二处。

两条都该在推送前用 `Select-String` 扫出来，而不是等 CI。runbook 的"改完一批断言后整体
扫一遍"那条我照做了，但扫描的正则只找"首参是字符串字面量"，而我把字面量写到了第二位，
于是扫不到。

### 本轮验证

| 检查项 | 结果 |
| --- | --- |
| `:app:lintDebug` | ✅ |
| JVM 单元测试 | ✅ failures=0 errors=0 |
| 仪器测试 | ✅ |
| Release assembly（R8） | ✅ |
| CI | ✅ `679d745` 三个 job 全绿 |

### 下一轮

审计里还有这些没做（按我判断的优先级）：

- **`MainViewModel` 从不 `onCleared`**：`MainActivity` 直接 `MainViewModel(container)` 而
  不是走 `ViewModelStore`，所以 `onCleared()` 里的 `syncController.stop()` 一次都不会执行。
  后果是 `SyncHttpServer` 从打开同步页起一直监听端口，且那个六位匹配码在界面上早已不可见
  之后仍然有效（`PairingWindow` 的注释明确说"an open code must not outlive the page"）。
- **主线程 `runBlocking` 读盘**：`UserPreferences.deviceId` 没有缓存，每点一次关注星标都是
  一次主线程 `dataStore.data.first()` 加一次主线程 `edit`。
- **弹幕连不上时永远显示"正在连接弹幕…"**：`MainViewModel.startDanmaku` 用
  `runCatching { collect { ... else -> Unit } }` 把 `Failed`/`Disconnected` 全吞了，
  四个适配器都发了这些事件，没有一处被消费。
- **`LivePlayer.retriedRef` / `hasFirstFrame` 不随 URL 重置**：换清晰度后新 URL 一次都不
  自动重试。
- **界面项**：搜索页没有平台切换器、平板只有两列、关注页筛选行在单平台时整行消失、
  卡片星标 32dp 小于 48dp 触控目标、设置页没有版本号、深色主题零截图覆盖。


---

## 第六十轮迭代：生命周期没接上、主线程读盘、以及被吞掉的弹幕失败

第五十九轮审计里剩下的三条 app 层问题，一次做完。三条都属于同一个家族：**逻辑写对了，
但没有人调用它**。

### 缺陷一：`MainViewModel.onCleared()` 一次都没执行过

`MainActivity` 直接构造：

```kotlin
val viewModel = MainViewModel(container)
```

`onCleared()` 只由 `ViewModelStore.clear()` 调用，而这个实例根本没进 store。于是它里面的
`syncController.stop()` 从来没跑过：

- `SyncHttpServer` 从第一次打开同步页起**一直监听端口**，直到进程被杀。`SyncController`
  自己的 KDoc 写着 "server runs only while the sync screen is open, which keeps the battery
  and the attack surface small"——实际不是。
- `stop()` 同时是 `PairingWindow.cancel()` 的唯一调用点，所以那个六位匹配码在界面上早已
  滚出视野之后**仍然有效**。`PairingWindow` 的注释明确写着 "an open code must not outlive
  the page it was shown on"。

改法两层：

1. `MainActivity` 改用 `ViewModelProvider(this, factory).get(...)`，真正进入 `ViewModelStore`。
   `onCleared` 从此会被调用，顺带补上漏掉的 `playbackJob` 与 `discoveryJob` 取消。
2. **离开同步页就停**，而不是等进程结束。`navigate()` 到别的 tab、或按返回键，都调
   `stopSyncServer()`，同时清空 `pairingCode` / `localAddress` / `discoveredPeers`。留着的话
   下次进页面会在 `beginPairing()` 之前先渲染上一轮的码，而"我的设备"卡片会显示一个没人
   监听的端口。

`SyncServerLifecycleTest` 四条：服务在页面打开时确实在监听、离开后停、匹配码失效、返回键
同样处理、再次进入能重新起来。

**这条测试我写错了两版，都被 CI 抓到，而且都撞在同一件事上。**

1. 断言"`stop()` 后能重新 bind 同一端口" → `BindException: Address already in use`。
   `ServerSocket.close()` 让端口在 Linux 上进入 `TIME_WAIT`，`SO_REUSEADDR` 对"曾经 accept
   过连接"的 socket 不覆盖这一点。
2. 改成"连一下、期望被拒"并放进仪器测试 → `nothing may accept a connection after stop()`
   失败。**listening socket 关了，但 backlog 里已排队的连接、以及探测与关闭之间的竞态，仍然
   会让 connect 完成。**

两次都不是 `stop()` 的 bug，是"端口层面"根本不是能用来证明"服务已停"的判据。`stop()` 真正
保证的是 accept 循环退出、transport 与 server 引用清空、配对码取消，所以测试改成断言这些
（`isRunning` 读的正是 accept 循环检查的那个 `AtomicBoolean`）。两版失败都写进了测试注释，
免得下一轮有人再试一遍。

### 缺陷二：`UserPreferences` 在 UI 线程上 `runBlocking` 读写盘

两处：

- `deviceId` 每次访问都 `runBlocking { dataStore.data.first() }`，**没有缓存**。而每一次
  关注、取关、拖拽排序、特别关注、清晰度记忆、同步交换都要读它——点一下星标就是一次主线程
  阻塞读盘。现在用双重检查锁定缓存，只在首次解析时读盘。
- `update()` 是 `runBlocking { dataStore.edit { ... } }`，调用方全是 Compose 点击回调：
  每点一次主题 chip、每拖一次弹幕滑块都阻塞主线程一次文件写。现在落盘挪到单线程
  `writeScope`，返回 `Job`；`cached` 在交出去**之前**就更新，所以进程内读取立即一致
  （`UiState` 是从 `preferences.settings` 同步构建的，这条不变量必须保住）。

**写入必须串行**：`update` 是对整条 settings 记录的读-改-写，两个写并发会乱序、让旧值覆盖
新值。单线程 dispatcher 保证按调用顺序落盘。

新增 `awaitWrites()` 作为测试缝：跨实例读回的用例（`PeerAddressPersistenceTest` 建了第二个
`UserPreferences` 读文件）必须先等写入落盘。生产不调用它——DataStore 自己会 flush。

顺带把 `setThemeMode` / `setLastPlatform` / `setDanmakuEnabled` / `setBlockedKeywords` /
`clearSearchHistory` / `setPreferredQuality` 从表达式体改成块体，否则它们会跟着把内部的
`Job` 泄漏到公开 API。

### 缺陷三：弹幕连不上时永远显示"正在连接弹幕…"

```kotlin
runCatching {
  ...collect { event ->
    when (event) {
      is DanmakuEvent.Message -> ...
      else -> Unit          // ← 其余三个事件全部丢掉
    }
  }
}
```

四个适配器都发了 `Failed` 与 `Disconnected`，还带了平台自己给的原因，**一个都没被消费**。
于是"握手被拒绝"和"房间里没人说话"在界面上完全一样：一个带 spinner 的"正在连接弹幕…"，
永不结束。

`runCatching` 还连 `CancellationException` 一起吞了，所以每次关闭房间取消 `danmakuJob` 都被
当成一次失败。

改法：新增 `UiState.danmakuStatus`（`Connecting` / `Connected` / `Reconnecting` / `Failed`，
sealed interface）。`startDanmaku` 处理全部四个事件；`collect` 正常返回（适配器放弃重试）
也记为失败，否则又是一个永久 spinner。面板按状态分支：

| 状态 | 文案 |
| --- | --- |
| `Failed(reason)` | 弹幕连接失败：`<平台给的原因>` |
| `Reconnecting(reason)` | 弹幕正在重连…（带 spinner，但不报错） |
| `Connected` 且无消息 | 还没有人说话 |
| `Connecting` | 正在连接弹幕…（带 spinner） |

已有弹幕时状态不参与渲染——连接中途断开不该把用户正在看的列表清空。`openRoom` /
`closePlayer` / `back` 都重置为 `Connecting`，否则下一个房间会先渲染上一个房间的失败横幅。

### 本地验证

本机不跑 Gradle（runbook 硬约束一），所以两处并发模型与弹幕状态机都用 Python 等价跑了一遍：

```
deviceId 竞态下只读盘一次          ok     六个事件变体在四种起始状态下全部被处理   ok
写入前新值已可见                   ok     终态失败后不再渲染 spinner               ok
写入按发出顺序落盘                 ok     握手失败的原因上屏                       ok
排队写入不丢更新（4 线程 x25 次）   ok     collect 正常结束也算失败                 ok
                                          已有弹幕优先于状态                       ok
                                          重连中不报"失败"                         ok
```

### 本轮验证

| 检查项 | 结果 |
| --- | --- |
| `:app:lintDebug` | ✅ |
| JVM 单元测试 | ✅ failures=0 errors=0 |
| 仪器测试 | ✅ |
| Release assembly（R8） | ✅ |
| CI | ✅ `8674ba9` 三个 job 全绿 |

五次推送。三处产品代码改动（生命周期、读盘、弹幕）都是一次过；红的两次**都是我自己写的
测试断言错**，不是产品缺陷——这轮的教训全在测试侧，已写进上面那一段和测试注释里。

### 下一轮

- **`LivePlayer.retriedRef` / `hasFirstFrame` 不随 URL 重置**：换清晰度后新 URL 一次都不会
  自动重试。改动很小，但需要一条能观察到"重试次数"的测试。
- **界面项**（审计里列的，都还没做）：搜索页没有平台切换器、平板只有两列（`GridCells.Fixed(2)`
  三处）、关注页筛选行在单平台时整行消失、卡片星标 32dp 小于 48dp 触控目标、设置页没有
  版本号、深色主题零截图覆盖、竖屏播放页状态栏压画面。
- **`docs/ITERATIONS.md` 已经 4300 行**，每轮还在末尾追加。建议按 20 轮一段拆成
  `docs/rounds/`，主文件只留目录与最新三轮——否则下一轮读文档就要花掉半个上下文。


---

## 第六十一轮迭代：换清晰度后新 URL 一次都不会重试，以及一次网格列数算错的自救

### 缺陷：三个状态没跟着 URL 重置

`LivePlayer` 里两个状态用了无 key 的 `remember`：

```kotlin
var hasFirstFrame by remember { mutableStateOf(false) }
var retriedRef  = remember { mutableStateOf(false) }
```

和第五十九轮那个分页 bug 同一个形状——值在首次组合时被捕获，之后永不重置。这里的后果更重：

- **`retriedRef` 不重置**：屏幕上第一次流重试一次之后，整个会话的重试额度就用光了。切清晰度
  或线路产生的新 URL **一次都不会自动重试**，一次例行的 CDN 抖动就变成永久的「无法播放」。
- **`hasFirstFrame` 不重置**：遮罩再也回不来，重新解析到新 URL 时看到的是纯黑方块，而不是
  「正在连接直播流…」。

第三个缺陷在同一个函数里：`Player.Listener` 注册在 `LaunchedEffect(player)` 中，那个 effect
只在 `player` 变化时重跑，所以 listener 闭包捕获的是**首次组合的 `url`**。IDN 降级和重试判断
都在拿旧 URL 做决定——换清晰度之后，降级会把用户送回上一个流。

### 改法：把规则抽成纯数据 + 纯函数

新增 `PlaybackAttempt.kt`：

- `PlaybackAttempt(url, retriesUsed, sawFirstFrame)`，`forUrl()` 在 URL 变化时整体重置。
- `decideRecovery()` 返回 `Retry` / `DowngradeToHttp` / `Fatal`。IDN 降级优先且**不消耗**
  重试额度，因为对一个名字过不了 SNI 校验的主机重试是必然失败的。
- listener 用 `rememberUpdatedState` 读当前 attempt，判断永远针对正在播的 URL。

抽成纯逻辑的理由和第三轮、第五十九轮一样：这两个缺陷都需要"先后两个 URL"才能观察到，
截图看不出来，模拟器上复现需要真解码器。`PlaybackAttemptTest` 十条直接在 JVM 上钉住。

### 一次差点推上去的错误

同一轮里把三处 `GridCells.Fixed(2)` 改成 `GridCells.Adaptive(minSize = CardMinWidth)`，
好让平板拿到四列而不是两个被拉满的巨块。我第一次写的是 `156.dp`——看起来合理，实际会让
**320dp 的手机掉到一列**。

`Adaptive` 取满足 `n * minSize + (n - 1) * spacing <= width - 2 * padding` 的最大 n。320dp
减去两侧各 12dp 内边距，可用 296dp；两列需要 `2 * minSize + 10 <= 296`，也就是
`minSize <= 143dp`。156dp 显然过不去。本地用 Python 把候选值全算了一遍：

| minSize | 320dp | 360dp | 412dp | 640dp | 800dp |
| --- | --- | --- | --- | --- | --- |
| 156 | **1** | 2 | 2 | 3 | 4 |
| 148 | **1** | 2 | 2 | 3 | 4 |
| **140** | 2 | 2 | 2 | 4 | 5 |
| 120 | 2 | 2 | 3 | 4 | 6 |

140dp 是既保住手机两列、又给平板四列的最大值。**这类"看起来合理"的尺寸如果直接推上去，
会让所有布局测试和 39 张截图一起变**，而且失败原因（列数）与改动意图（平板布局）看起来毫不
相关。

### 顺带两处界面

- **卡片星标**：`IconButton` 的 `size(32.dp)` 把触控区也缩到 32dp，低于 48dp 最低标准。在
  320dp 屏上很容易点到卡片本体（打开房间）而不是关注。改成 48dp 容器 + 8dp 内边距，视觉上的
  圆盘仍是 32dp，卡片看起来不变。
- **设置页版本号**：报 bug 时第一个被问的就是版本，而「关于」卡片原来只有产品名。加
  `BuildConfig.VERSION_NAME`。

审计里那条「竖屏状态栏压画面」**是我判断错了，没改**：普通竖屏布局有
`windowInsetsPadding(WindowInsets.statusBars)`，满幅竖屏视频有 `StreamerLine` 的渐变，
两条路径都覆盖了。

### 推送通道：代理与直连都失效，SSH 可用

这一轮 `git push` 连续失败，排查过程值得记进 runbook（已补）：

| 检测 | 结果 |
| --- | --- |
| `ProxyEnable` | `0`，代理已退出（`ProxyServer` 仍残留） |
| `Invoke-WebRequest https://api.github.com/zen`（直连） | **200** |
| `TcpClient.Connect(20.205.243.166, 443/80/22)` | **全部成功** |
| `git push`（`schannel` 与 `openssl` 都试过） | `Failed to connect ... after 21s` |

`GIT_TRACE=1` 给出决定性一句：git 的 libcurl 从 `0.0.0.0:65419` 发起连接并超时，而
PowerShell 的 `TcpClient` 从真实网卡地址发起。**同一目标端口，一个通一个不通——"端口能连"
不能证明 git 能推。** 最后改用 `git push git@github.com:SilkKirk/LiveHub.git main` 一次成功。

### 本轮验证

| 检查项 | 结果 |
| --- | --- |
| `:app:lintDebug` | ✅ |
| JVM 单元测试 | ✅ failures=0 errors=0 |
| 仪器测试 | ✅ |
| Release assembly（R8） | ✅ |
| CI | ✅ `b8770a2` 三个 job 全绿 |

一次推送一次过。

### 下一轮

- **界面项**（审计里剩下的）：搜索页没有平台切换器、关注页筛选行在单平台时整行消失、
  深色主题零截图覆盖、平板列数改动需要在 640dp 下补一张截图（当前 39 张全是 320dp）。
- **`docs/ITERATIONS.md` 已 4500 行**，建议按 20 轮拆到 `docs/rounds/`。
- 第五十九、六十轮修的三处（分页、同步认证、生命周期）都还没有真机验证，只在 CI 上跑过。


---

## 第六十二轮迭代：搜索页没有平台切换器

输入框的 label 写着「搜索 斗鱼 主播 / 直播间」——点名了一个用户在这个页面**无法更换**的平台。
聚合客户端里搜索的主要用途就是「同一个主播在别的平台有没有」，而这件事原来要四次点击、还得
把关键词重新打一遍。

### 为什么不能直接复用 `SelectPlatform`

那是首页的切换器，它做三件在搜索页上都是错的事：清空分类树与直播间网格（这两样在首页，不在
屏幕上）、把 `screen` 设成 `Screen.Home`、清空 `lastSearchQuery`。直接复用等于"点一下平台 chip
就被踢回首页，关键词也没了"——这正是这个功能一直缺着的根本原因：能做，但做了更糟。

所以新增 `UiAction.SelectSearchPlatform`：换平台、留在搜索页、把关键词在新平台上重搜。

### 本地推演抓到的缺陷

按惯例先用 Python 把分支逻辑等价跑一遍，结果第一版就红了：

```
清空输入框后再切平台 → keyword 从 lastSearchQuery 取到了，
但 submitSearch 读的是 searchQuery（空的）→ 不重搜，还弹「请输入要搜索的主播或直播间名称」
```

**点一个 chip 却收到一句数落。** 修法是把 keyword 写回 `searchQuery` 再调 `submitSearch`；
这同时保住了一条这个页面本来就该守住的不变量——输入框与结果描述的是同一次搜索。

### 但那条推演的前提本身是错的

我给推演建的第一个场景是"清空输入框后再切平台，应该重搜 lastSearchQuery"。落地成测试后 CI 报
`condition not met within 5000ms`。回头读代码才发现：`UpdateQuery("")` 会把 `lastSearchQuery`
和结果**一起**清掉（那是第五十八轮前后修"清空后仍宣称搜索过"时加的，`SearchPagingTest` 钉着
它）。所以清空后切平台**本来就没有关键词可重搜**，不请求才是对的。

推演本身没算错，是建模时把前提想当然了。这条测试改成断言真实行为（不请求、不弹提示、留在
搜索页、平台确实换了），另外补了一条真正存在的场景：输入了但没提交的关键词，切平台后要搜它。

### 顺带补的截图

  - `23-home-dark` / `23b-settings-dark` / `23c-sync-dark`：深色主题。此前 39 张截图**全是浅色**，
    而深色是设置页里的真实选项；一个只在深色下读不清的颜色在任何产物里都看不出来。
  - `09b-search-platforms`：新的 chip 行。四个平台名在 320dp 上折成两行（斗鱼/虎牙/抖音 一行、
    哔哩哔哩 一行），FlowRow 行为正确，没有出现第十三轮那种"被压成竖排单字"。

**播放页没有做深色截图**：`LiveHubTheme` 只在 `LiveHubRoot` 里应用，而截图走 `Screen.Player`
时是直接组合 `PlayerContent` 的，主题根本没套上。截出来的"深色播放页"其实是浅色主题加一个黑
视频框——看起来像覆盖，实际什么都没验证。这条写进了测试注释。

### 本轮验证

| 检查项 | 结果 |
| --- | --- |
| `:app:lintDebug` | ✅ |
| JVM 单元测试 | ✅ 722 个用例 |
| 仪器测试 | ✅ 43 张截图 |
| Release assembly（R8） | ✅ |
| CI | ✅ `07812e8` 三个 job 全绿 |

---

## 第六十三轮迭代：清掉九处死代码；关注页筛选行无故消失

审计列出的死代码，逐条核对调用方后删除。每一条都不是"看起来没用"，而是全仓库零引用：

| 符号 | 为什么是死的 |
| --- | --- |
| `Screen.Category` | 从未有任何地方设 `screen = Screen.Category`（分类树内联在首页），唯一引用是一个永远为假的分支 |
| `MainViewModel.categoryTitle()` | 零调用 |
| `SyncController.rememberPeer` / `onlinePeers` / `onPeerDiscovered` / `dispose()` | 零调用；`onPeerDiscovered` 只有自己的赋值与自调用，没有任何 observer 注册过 |
| `InMemoryCookieJar.headerFor()` | 零调用 |
| `UserSettings.dynamicColor` / `autoRefreshFollows` | 字段与默认值都在，没有任何读取方 |
| `UiState.darkTheme` | 只有两个测试读它，而且它会和 `themeMode` 互相矛盾（System 模式在深色系统上 `darkTheme` 仍是 false） |
| `ResilientPlatformRepository.maxAttempts` | 声明为 var 的"测试缝"，但 retry 循环用的是常量 `MAX_ATTEMPTS`，改这个 var 不生效 |
| `DanmakuMessage.userLevel` / `fansClubLevel` | 四个适配器一个都不写，也没有界面读 |

两处需要说明：

- `dynamicColor` 连同 `Theme.kt` 里那条从未被传过 `true` 的通道一起删掉了（`LiveHubTheme` 只传
  `mode`）。留着的是一个没人能拨的开关。
- `DanmakuMessage.color` **保留**：它确实被 `DanmakuOverlay` 读取，只是暂时没有适配器填它。
  写进注释说明"今天所有弹幕都是白的"，以及接上它需要先拿真实抓包验证各平台的颜色编码。
  `userLevel`/`fansClubLevel` 不同——参考项目也解了但不渲染（与第二十三轮 `col` 的结论一致），
  而每条弹幕都带两个恒为 0 的字段，弹幕又是全应用最热的路径。

### 关注页筛选行

条件是 `filter != ALL || platforms.size > 1`，于是在**最常见**的那种列表上——单平台、还没筛选
过——整行不渲染，直播中/未开播 两个 chip 也一起消失。功能是通的
（`SetFollowLiveFilter` → `UiState.followLiveFilter` → 网格），但用户看不到；唯一让它出现的
方法是无意中关注了第二个平台。

改成"有 chip 起作用时才显示"：

```
filter != ALL || platformFilter != null || platforms.size > 1 || (0 < liveCount < total)
```

本地 Python 把十种组合跑了一遍，**其中两种推翻了我自己的预期**：全部未开播时我以为该显示，
实际不该——直播中会筛成空、未开播等于全部，两个 chip 都是噪音。规则对，是期望写错了。

### 文档：`ITERATIONS.md` 拆开

5919 行 / 340 KB → 528 行 / 27 KB 主文件 + `docs/rounds/` 三个归档
（`02-20.md` 1567 行、`21-40.md` 2366 行、`41-58.md` 1494 行）。逐字节搬移 + 每个归档一行横幅，
没有改写、没有摘要、没有删任何一轮；切割前先断言四个切割点分别落在「第二轮 / 第二十一轮 /
第四十一轮 / 第五十九轮」的标题行上，否则不写文件。复核：二到六十一连续 60 轮全在。

做这件事的理由很实际：每一轮开工都要读它，「上一轮改了什么」在 5900 行里要翻很久。
runbook 第八步补了说明——新轮次写主文件末尾，第 X 轮（X ≤ 58）的旧引用去对应归档找。

### 两次被 CI 抓到的测试错误

1. `未开播` 同时匹配筛选 chip 和离线房间卡片上的角标 → `found 2 nodes`。改成按
   `SemanticsProperties.Selected` 区分（chip 可选中，角标不可）。
2. 清空输入框后再切平台的断言（见上一轮记录）。

### 本轮验证

| 检查项 | 结果 |
| --- | --- |
| `:app:lintDebug` | ✅ |
| JVM 单元测试 | ✅ 731 个用例 |
| 仪器测试 | ✅ |
| Release assembly（R8） | ✅ |
| CI | ✅ `25038e7` 三个 job 全绿 |

---

## 第六十四轮迭代：三个够不着的按钮，一个没有意义的数字

审计里剩下的界面项。

### 播放页右侧三个圆形按钮只有 38dp 可点

它们浮在画面上，点空的后果不是"没反应"——下面那层「单击切换控件」的手势层会接住这一下，
于是整排按钮消失。**用户看到的是控件坏了，不是自己点偏了。** 改成 48dp 可点容器 + 38dp 圆盘，
外观不变。

### 首页分类行的折叠箭头 36dp

`IconButton` 只在没有被显式指定尺寸时才应用 Material 的最小触控尺寸，所以 `size(36.dp)` 把可点
区域也压到了 36dp——在一个以密集 chip 为设计目标的页面上，唯一能展开分类树的控件反而最难按。
改成 48dp。这一改让分类行高了 12dp，`HomeCategoryDensityTest` 的两条预算（三级行起始 < 190dp、
展开后分区行 < 220dp）仍有余量，CI 确认通过。

### 「弹幕 37」里的数字没有意义

它是 `state.danmaku.size`，也就是**渲染缓冲**的大小（上限 200）。随着观看时间增长、卡在 200、
关闭播放器就清零——一个没人问的问题的答案（不是"累计条数"，不是"在线人数"，也不是"未读数"）。
改成状态标签：弹幕开着显示「弹幕」，关掉显示「弹幕已关闭」。连接状态由下面那块面板负责，
那里已经有 `Connecting`/`Connected`/`Reconnecting`/`Failed` 四态。

### 平板列数终于有了断言

第六十一轮把 `GridCells.Fixed(2)` 换成 `Adaptive(140.dp)`，而 140 是算出来的：320dp 屏可用
296dp，两列要求 `2 * minSize + 10 <= 296`，即 `minSize <= 143`。我第一次写的 156dp 会让手机
掉到**一列**。

那一轮的证据只有"本地 Python 推演算过"，推演不是回归测试。`GridColumnCountTest` 在
320 / 360 / 640 三个宽度上分别断言 2 / 2 / 4 列，正是那个错误会被抓到的地方。宽屏没有做截图：
`ScreenshotTest` 跑在 320×640 的模拟器上，改显示尺寸是设备级全局操作，第三十五轮已经确认会把
其余 39 张渲染一起弄坏。列数是几何，用断言而不是照片固定。

### 本轮验证

| 检查项 | 结果 |
| --- | --- |
| `:app:lintDebug` | ✅ |
| JVM 单元测试 | ✅ 731 个用例 |
| 仪器测试 | ✅ 43 张截图，逐张看过（新增四张：深色三张 + 搜索页平台行） |
| Release assembly（R8） | ✅ |
| CI | ✅ `20f3eb9` 三个 job 全绿 |


## 发版：v0.18.0

第六十四轮之后发了一版（`aa60d10`）。Release workflow 一次通过，产物
`LiveHub-v0.18.0-release.apk` 4.69 MB，release 签名（不是回退的 debug 包），带 `*.sha256.txt`，
已标记为 latest。体积从 v0.17.4 的 4.78 MB 降到 4.69 MB，和删掉七个零引用依赖对得上。

发版前重新看了一遍 `v0.17.4..HEAD` 的全部改动（16 个提交），没有夹带无关修改。

`versionCode` 由标签推导（`MAJOR*10000 + MINOR*100 + PATCH`），0.18.0 → 1800，高于 0.17.4 的
1704，覆盖安装不会被当成降级包。

### 审计清单的完成情况

第五十九轮开头那份审计列的 15 项，现在全部处理完：

| 审计项 | 结果 |
| --- | --- |
| 首页/搜索页无限加载 | ✅ 第五十九轮 |
| 未配对空 secret 可拉取 | ✅ 第五十九轮 |
| 七个零引用依赖 | ✅ 第五十九轮 |
| `MainViewModel` 生命周期 | ✅ 第六十轮 |
| 主线程 `runBlocking` 读写盘 | ✅ 第六十轮 |
| 弹幕失败被吞 | ✅ 第六十轮 |
| `LivePlayer` 重试状态不随 URL 重置 | ✅ 第六十一轮 |
| 卡片星标 32dp | ✅ 第六十一轮 |
| 平板只有两列 | ✅ 第六十一轮（含第六十四轮补的列数断言） |
| 设置页无版本号 | ✅ 第六十一轮 |
| 搜索页无平台切换器 | ✅ 第六十二轮 |
| 深色主题零截图 | ✅ 第六十二轮 |
| 九处死代码 | ✅ 第六十三轮 |
| 关注页筛选行无故消失 | ✅ 第六十三轮 |
| 播放页侧边按钮/分类箭头触控目标、弹幕计数文案 | ✅ 第六十四轮 |
| 竖屏状态栏压画面 | ⛔ **判断错了**：普通竖屏有 `windowInsetsPadding(statusBars)`，满幅竖屏有渐变，两条路径都覆盖，不需要改 |

### 下一轮

- **真机验证**。这是最大的一处空白：第五十九到六十四轮修的东西**全部只在 CI 上验证过**。
  具体要验证的：局域网同步在真实 Wi-Fi 下的配对与增量、mDNS 被路由器拦掉时的网段扫描兜底、
  四个平台的弹幕在真机上能不能连上、播放器换清晰度后的自动重试、平板四列的实际观感、
  深色主题在真实屏幕上是否够对比度。
- **抖音已登录的关键词搜索**仍无真实响应验证（没有账号），第五十八轮就记着这条。
- **`feed` / `partition` 端点的签名要求**没有各自单独验证过，只有 `enter` 确认了。
- **虎牙弹幕**仍有一类线上帧解不出来（第九轮记录的 `@Disabled` 用例），命中率 5/51。
- 宽屏（平板）没有截图覆盖。要做得在 CI 矩阵里加一台 640×320 的模拟器，而不是让测试改全局
  显示参数（第三十五轮的结论）。

---

## 第六十五轮迭代：刷新丢掉返回来源、展开分类溢出、列表回顶部、主线程写盘

先做了一遍完整审查（读全部 UI 代码、`MainViewModel`、`core:data` 与两个 cookie store、43 张
r64 截图、`docs/` 与 README），把结论分成「读码可确认的缺陷 / 界面 / 功能 / 工程」四组。
本轮只做第一组，八项一次做完。其中两项是**同一类问题在别处的另一半**，一项是**前一轮的修法
本身没生效**——后者比新发现的缺陷更值得记下来。

### 缺陷一：播放页「刷新/重试」会把返回来源重置成首页

`openRoom` 同时干两件事：打开房间、记录「关掉后回哪一页」。而播放页右侧的「刷新」和错误态的
「重试」都是**对已经打开的房间**再调一次 `openRoom`，此时 `screen` 已经是 `Player`：

```kotlin
playerReturnScreen = _state.value.screen.takeIf { it != Screen.Player } ?: Screen.Home
```

`takeIf` 返回 null，兜底写进 `Home`。复现：关注页 → 进直播间 → 点刷新 → 返回 → **落在首页**，
关注页的筛选和位置一起丢。原来的用例只钉了「从关注页打开、直接关闭」，没钉「刷新后再关闭」。

改成只在真正换房间时记录。`MainViewModelTest` 补了一条。

### 缺陷二：展开分类 + 选「全部」是一面被裁掉的芯片墙

展开态用 `FlowRow` 直出 `levels.visible`，而这个 `Column` 不是滚动容器。选中分组行首的「全部」
时 `visible` 是**所有分组的分区**：B 站 456 个、虎牙约 200 个。`FlowRow` 拿到超过窗口的内容不会
滚动，只会被约束裁掉：超出部分**不可达**，同时下面的房间网格被量成 **0 高**——用户点一下「全部」，
看起来像整个页面被清空了。

改成 `heightIn(max = 220.dp) + verticalScroll`。上限是「屏幕的一份额度」而不是「剩下的全给它」，
因为网格才是这个页面存在的理由。第六十三轮修的是折叠态不要换行，这条是展开态的另一半。

`HomeCategoryDensityTest` 补了一条用 100 个分区的用例：展开后第一张卡片仍可见，且分区块自己
是纵向滚动容器。

### 缺陷三：48dp 触控目标还差三处，而且第六十一轮的修法无效

三处不达标的：搜索结果行的星标 36dp、播放页 `PillButton`（关注/重试/返回）约 34dp、全屏栏的
返回与星标 34dp。

查这三处时发现更麻烦的一件事：**第六十一轮给卡片星标的修法没有生效**。当时把
`size(32.dp)` 改成 `size(48.dp).padding(8.dp)`，语义节点确实变成 48dp，但
`IconButton` 的最小触控尺寸是**在调用方 modifier 内层**实现的：

```kotlin
Box(modifier = modifier.minimumInteractiveComponentSize().size(40.dp)…clickable(…))
```

调用方的 `padding(8.dp)` 先把可用空间压到 32dp，内层 `size(40.dp)` 被强制成 32dp，
`clickable` 的可点区域就是 32dp。只有 `size(48.dp)` **不加 padding** 时，内层那个 40dp 才会被
约束撑到 48dp。也就是说卡片的星标从第六十一轮起一直是 32dp——语义树上看不出来，点偏了也只是
打开了直播间。

三处统一成 `size(48.dp)`（全屏栏那两个用 `size(48.dp)…clickable(…).padding(12.dp)`，可点在外、
内边距在内），图标尺寸与位置不变。用例：搜索行星标高度、关注胶囊高度、两个「退出全屏」按钮
高度，都断言 ≥ 48dp。

### 缺陷四：列表滚动位置在离开页面后归零

`rememberLazyGridState` / `rememberLazyListState` 都是 `rememberSaveable`，但
`rememberSaveable` 只在有 `SaveableStateHolder` 时才把值留到组合之外。三个列表页都直接长在
`when (state.screen)` 里，**离开就销毁**——而播放页是把整个 Scaffold 换掉的，所以进一次直播间，
首页的滚动位置就没了；从首页切到关注再回来同样回到顶部。

`LiveHubRoot` 加一个 `rememberSaveableStateHolder()`，用 `state.screen.name` 做 key 包住五个
dock 页面。holder 必须放在 player 分支**之上**：放在 `else` 里的话它自己会随分支一起被销毁，
正好在需要它的那一刻变空。

用例（`ScreenScrollRetentionTest`）用真实 `LiveHubRoot`：`ScrollToIndex(20)` → 断言第一张卡
不在树里、第 21 号在屏幕上 → 切到关注页 → 切回来 → 第 21 号仍在屏幕上。

### 缺陷五：关注页下拉刷新多打一次首页请求，指示器还会提前消失

`refresh()` 不分辨页面：在关注页下拉时先 `loadRooms(...)`（首页那套请求，用户看不到），
再 `refreshFollowStatus(pullToRefresh = true)`。两个协程**共写** `refreshing`，房间那路先结束
就把指示器关掉，而此时几十个主播的开播状态还在查。

改成按页面分派；刷新本身也一起收了：单个 job（再进关注页会取消上一次）、token 守卫
（被取消的那个协程仍会跑 `finally`，不能让它关掉新一次的指示器）、结果**按 key 合并进当前列表**
而不是整份替换（刷新途中关注/取关不会被旧快照撤销）。

顺带修掉一处吞异常：原来是 `runCatching { liveStatus(...) }`，它连 `CancellationException`
一起吃掉，被取消的刷新会继续走完整份列表再写入结果。现在显式重抛。

用例（`FollowRefreshTest`）用记录 URL 的假 HTTP 客户端 + 真 `DouyuAdapter`：下拉后不得出现
新的 `mixListV1` 请求，但必须出现 `/betard/`，且 `refreshing` 最终为 false。

### 缺陷六：主线程磁盘 I/O 还留在 cookie 与启动路径上

第六十轮把 `UserPreferences` 的写改成了单线程队列，两个 cookie store 没有跟着改：
`save`/`clear` 是 `runBlocking { dataStore.edit { … } }`，而调用方是「登录/退出」按钮、
以及收到配对设备推来的登录态——都是主线程。`MainViewModel` 构造里还有两次
`isLoggedIn()` 同步读盘（各自一个 DataStore 文件）。

- 新增 `SerialWriteQueue`：一条线程一支队列，**按文件共享**（同文件的写入顺序在全进程内保持，
  否则测试里每个用例新建容器的写法会让上一个用例的写落在下一个用例的写之后）。
  `save`/`clear` 先把新值发布到内存缓存再交出去，所以同一帧发出的请求已经带上新 cookie。
- `MainViewModel` 的登录态改在 `Dispatchers.IO` 上读，读到之后再更新两个标志。这两个字段只有
  设置页和搜索提示会用到，晚一帧看不见；卡住第一帧是看得见的。

`CookieStoreWriteTest`：写入后立即能读到（同实例）、`awaitWrites()` 之后新实例也能读到（落盘）。

### 缺陷七：搜索框有文字后，屏幕上没有可点的「搜索」

`trailingIcon` 是个二选一：有文字就只剩「清空」，提交按钮消失——只能靠软键盘的搜索键，而几种
输入法把它藏在一层菜单里。改成「清空 + 提交」并列常驻，提交按钮没有文字时禁用。

代价是文字区少 48dp。`11-search-results.png` 里能看到代价：「一条小团团」被截成「一条小…」。
48dp 的星标也要吃掉 12dp，两者叠加。可以接受（星标和提交都变成了真的能按到的东西），但若想
要回那 12dp，从封面宽度（104dp）或行的内边距里还。

### 缺陷八：键盘没有让位

`Scaffold` 的默认 `contentWindowInsets` 只含 system bars，全工程没有一处 `imePadding()`。
targetSdk 37（Android 15 起）边到边应用**不再因键盘收缩窗口**，得自己让。给 `Scaffold` 加
`Modifier.imePadding()`：键盘弹起时整页（含底部导航）上移，搜索结果最后一条可以滚到可视区。

### 本轮踩的坑（都是我自己的错）

滚动位置那条用例第一次是红的——**失败在我的选择器，不在被测行为**：我用
`(VerticalScrollAxisRange is defined) and hasAnyDescendant(hasText("主播1"))` 定位网格，而
`performSemanticsAction` / `performScrollToNode` 每执行一步都会**用原选择器重新取节点**。
滚过一屏后「主播1」那张卡片离开组合，选择器不再匹配，报 `The node is no longer in the tree`，
附带 `maxValue=100.0` 这种一眼看上去像布局坏了的语义快照（其实是取节点那一刻的旧值）。

改成只按网格自己的 `VerticalScrollAxisRange` 定位（本页唯一的纵向滚动容器），一步
`ScrollToIndex(20)`，不再依赖任何会滚走的子节点。

### 本轮验证

| 检查项 | 结果 |
| --- | --- |
| `:app:lintDebug` | ✅ 0 error（告警仍是既有的依赖版本 / 图标形状那几条） |
| JVM 单元测试 | ✅ 743 个用例（新增 12 个） |
| 仪器测试 | ✅ 43 张截图，逐张看过 |
| Release assembly（R8） | ✅ |
| CI | ✅ `7e7424b` 三个 job 全绿 |

第一次推送 `63fc7e2` 是「743 个用例 1 失败」，红的就是上面那个新用例；修好选择器后
`7e7424b` 全绿。截图比对：`01b-home-category-expanded` 与上一轮逐字节相同（fixture 只有 4 个
分区，封顶与滚动都不触发），`17b-player-fullscreen` 只高了 14dp（48dp 可点区 + 24dp 图标不变），
`11-search-results` 是唯一有明显视觉代价的一张。

### 下一轮

- **备份规则**：`allowBackup="true"` 且没有 `dataExtractionRules` / `fullBackupContent`，
  B 站/抖音 cookie、同步 `selfSecret`、已配对设备密钥都在默认备份范围里（云备份与换机迁移）。
  第五十九轮堵的是凭据外泄的另一半，这一半还开着。
- 搜索行文字列被两个 48dp 控件吃掉 60dp：要么接受，要么从封面（104dp）里还。
- 设置页「主题」的说明文字还写着「打开深浅色开关」——界面上早就没有开关了，是三个 chip。
- 抖音账号卡按钮写「扫码登录」，实际打开的是 WebView 登录页。
- 「展开分类」在「全部」下的折叠箭头会被 `FlowRow` 推到单独一行，看起来像分隔条。
- 仍是那句：**真机验证**是最大空白，第五十九到六十五轮的改动全部只在 CI 上验证过。

---

## 第六十六轮迭代：凭据不进云备份；关注刷新并发 + 节流；三处文案

审查里排到这一轮的三块。互不相干，但都属于「路早就铺好、只差最后一步」：备份规则是
manifest 上一个属性的事，并发是刷新实现里一个 `map` 的事，文案是界面改过而说明没跟上。

### 缺陷一：`allowBackup=true` 且没有任何规则，cookie 与同步密钥在云备份里

第五十九轮堵的是「同网段任意程序发一个空 secret 就能读走关注列表和登录 cookie」。这一条
是同一个洞的另一半：数据没有被别人拿走，而是被**备份进用户自己的云盘**。

`files/datastore/` 下三个文件都不是「偏好设置」那么轻：

| 文件 | 里面有什么 |
| --- | --- |
| `livehub_settings` | 同步 `selfSecret`、每台已配对设备的 secret、`loginRecords`（两个平台的 cookie 明文）、关注列表与全部设置 |
| `livehub_bilibili` | B 站会话 cookie |
| `livehub_douyin` | 抖音会话 cookie 与协商出来的 `ttwid` |

Auto Backup 默认把 `files/` 整个带走。于是用户的 Google 账号里有了一份 cookie：平台的
「退出所有设备」够不到那里，换机、恢复备份、几台机器共用同一账号时它会再出现一次。

- `data_extraction_rules.xml`（API 31+）：`cloud-backup` 排除 `datastore/`，
  `device-transfer` **保留**（写 `include`，不是什么都不写）。换机是用户主动走的一步，而且
  这个应用本来就有意把登录态同步给已配对设备——静默的那一半才是要拦的。
- `backup_rules.xml`（API 26–30）：那些版本只有 Auto Backup，所以「不备份凭据」等于「整个
  datastore 都不备份」，关注列表与设置一起留在旧机器上。这是明确的取舍：关注列表和 cookie
  在同一个 JSON 里、分不开，而关注列表本来就能通过局域网同步过去。

`BackupRulesTest` 钉四件事：两个文件的 `exclude` 都盖住 `datastore`；API 31+ 的
`device-transfer` 仍然包含它；`allowBackup` 还是 true（否则规则就成了摆设）；以及两个文件的
**根元素**分别是 `data-extraction-rules` 与 `full-backup-content`——平台对根元素不认识的规则
文件是**静默忽略**的，把 A 的内容粘进 B（两个文件都还在、都还有 `exclude`）会让凭据悄悄回到
云备份里。

### 缺陷二：关注刷新是串行 `map`，而且每次进 tab 都全量重查

`refreshFollowStatus` 对关注列表走 `map { liveStatus(...) }`：四十个关注是四十次往返依次等待，
一百个就长到列表看起来冻住了（每项还带最多三次重试）。而每次 `Navigate(Follow)` 都会启动
一轮——包括点已经在的那个 tab——连点几次就排队几轮，最后写入的还可能是较旧的一份。

- `Semaphore(6)` 并发；每条结果**按 key 合并进当前列表**，行随答案陆续点亮，而不是最后一次性
  出现。
- 普通访问 30 秒内复用上一次结果（`FOLLOW_REFRESH_MIN_INTERVAL_MILLIS`）；下拉刷新不受这个
  窗口限制。
- 取消语义不变：单 job + token 守卫，被取消的那一轮既不写结果、也不关别人的指示器。

用例两条。第一条用假 HTTP 客户端在 `yield()` 上**挂起**（不是阻塞）来测并发峰值：串行实现的
峰值恒为 1，并发实现会 ≥ 2，且完全不依赖墙钟时间（墙钟断言在 CI 上必然抖）。第二条钉住节流：
第一次进 关注 打一次 `/betard/`，切走再切回来不得再打。

### 三处文案

- 设置页主题说明写「打开深浅色开关由系统决定」，而界面上没有开关，是三个 chip。
- 抖音账号卡按钮写「扫码登录」，打开的是 WebView 登录页，扫码与验证码两种都支持
  （`DouyinWebLoginSheet` 自己的说明就是这么写的）。按钮改成「登录抖音」，卡内说明、搜索页
  提示与 2483 那条错误文案统一成「登录后」。B 站保留「扫码登录」——那条路径确实是二维码。

### 决定：竖屏播放页不加返回按钮

审查里我把它列成了「零个可见退出入口」。这一版明确**不加**：手势优先，左侧边缘滑动与系统
返回已经覆盖，画面上再摆一个箭头只是把导航做成两套，还和第六十四轮「删掉竖屏头部重复的
返回/关闭」互相抵消。记在这里是为了以后不要每轮重新提一遍。

### 本轮踩的坑

`ApplicationInfo.dataExtractionRulesRes` 在公开 SDK 里是 `@hide`（AOSP 里就是隐藏字段），
仪器测试里 `Unresolved reference` 直接编译失败。而且它红的是**仪器**那一格：`Build & test`
一直是绿的，因为 `assembleDebugAndroidTest` 只在仪器 job 里跑——第一眼看「Build & test 绿了」
会以为整体没事。

撤掉那条断言。接线本身不需要运行时检查：`android:dataExtractionRules="@xml/…"` 指向不存在的
资源在 aapt 阶段就会失败，manifest merge 也不会静默丢掉它。留下的是规则内容的断言，那才是
会被人改坏的地方。

工具经验一条：`ci.py logs --tail` 只打印末尾，完整日志已经落在 `dist-ci/job-*.log` 里；
在文件里 grep `e: ` 比读尾巴快得多，这也是这次找到编译错误的方式。

### 本轮验证

| 检查项 | 结果 |
| --- | --- |
| `:app:lintDebug` | ✅ 0 error（manifest 的两个新属性没有引入 error） |
| JVM 单元测试 | ✅ 750 个用例（新增 7 个） |
| 仪器测试 | ✅ 43 张截图，看过 `15/15b/16-settings`（两处文案） |
| Release assembly（R8） | ✅ |
| CI | ✅ `b34d0fa` 三个 job 全绿 |

本轮红过一次：`f5abaa5`，原因是 `dataExtractionRulesRes` 是 `@hide`。撤掉那条断言之后
`dda0139` 与 `b34d0fa` 连续全绿。第六十五轮的 `63fc7e2` 也是同一性质——红在测试自身，
不在实现。

### 下一轮

- 搜索行的文字列被两个 48dp 控件吃掉 60dp（第六十五轮记的），要回 12dp 得从封面（104dp）
  或行的内边距里拿。
- 「展开分类」在「全部」下的折叠箭头被 `FlowRow` 推到单独一行，看起来像分隔条。
- 功能面上投入产出最高的两条仍是：**开播提醒**（worker 每 30 分钟已经在查开播状态，只差
  一条通知）与 **PiP / 后台播放**（现在离开应用声音继续放，但没有通知也没有控制入口，
  还会和别的音频叠在一起）。
- 仍是那句：**真机验证**是最大空白，第五十九到六十六轮的改动全部只在 CI 上验证过。


