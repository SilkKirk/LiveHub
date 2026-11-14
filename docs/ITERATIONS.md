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

---

## 第六十七轮迭代：画中画；搜索结果行的宽度还回来；折叠箭头不再单独一行

「开播提醒」按用户要求跳过，这一轮做的是剩下的两条界面欠账，加上一条一直空着的功能：
离开应用时声音还在放，却没有任何窗口。

### 画中画：离开应用时缩窗，而不是把声音留在后台

在此之前，按住 Home 之后直播的声音继续放，屏幕上什么都不剩——既没有通知可以停，也没有
窗口可以看。现在正在播的直播间会缩成画中画；没有在播的房间照常退出，不留一个空窗口。

- `android:supportsPictureInPicture="true"`。`configChanges` 早在第一版就包含
  `screenSize|smallestScreenSize|screenLayout|orientation`，正是 PiP 要求的那组，所以
  manifest 只加了一个属性。
- **没有按钮**。触发点是 `Activity.onUserLeaveHint()`：Home、最近任务、切换到别的应用时
  系统会调用它，来通知、来电时不会——这正是「用户自己想走」的语义。这一轮的定位也是
  「手势优先」：入口不新增，把已有的手势必有的结果做对。
- `PictureInPicture.shouldEnter(state)` 是纯函数：必须是播放页、已解析出播放地址、解码器
  没有报错。三条各有反例用例——没解析就缩窗，窗口里是个邮票大的转圈。
  不加 `Build.VERSION.SDK_INT` 判断：`minSdk` 就是 26，PiP 也是 26 进来的，那种判断只是
  看起来谨慎的死代码。
- `isInPictureInPictureMode` 用 `MutableStateFlow` 送给 Compose：组合函数拿不到那个回调，
  而 `Configuration` 也不反映它。
- `PlayerContent(pip = true)` 只留画面：侧边按钮、全屏栏、动作行、弹幕列表、双击暂停层
  全部让位。缩窗里每一样都需要「能瞄准的平面」，而展开/关闭是系统画在那个窗口上的。
- 竖屏流在缩窗里 letterbox 而不是裁切：`verticalFullBleed` 只管「填满手机竖屏」，16:9 的
  缩窗里填满等于把竖屏裁成中间一条。
- 顺带补上**音频焦点**：`setAudioAttributes(..., handleAudioFocus = true)`。原来玩家从不申请
  焦点，别的音乐 App 会和直播叠着放，来电也不躲。

**没有做的**（写在这里以免当成已完成）：`setAutoEnterEnabled`（Android 12 起手势进入 PiP 的
无缝动画）需要状态一变就刷新 `PictureInPictureParams`，留到有真机可验证的时候一起做；
媒体通知与前台服务也没做，所以**关掉 PiP 窗口之后音频仍会继续**——这一条和 PiP 之前的行为
一样（以前是按住 Home 之后继续放），不是这一轮引入的。

### 搜索结果行：把星标吃掉的 12dp 还回来

第六十五轮把行尾星标从 36dp 改到 48dp（这是对的），代价是文字列少了 12dp，
`11-search-results.png` 里「一条小团团」被截成「一条小…」。这一轮从行的密度里拿回来 14dp：
内边距 `10/10` → `8/6`、间距 `10` → `8`、封面 `104` → `100`。骨架屏同步改，否则首屏到达时
整行会横跳一下。

### 展开分类：折叠箭头不再是最后一个 chip

展开态里箭头原本是 `FlowRow` 的最后一项，于是它跟着最后一组走——斗鱼十二组会推到单独一行、
贴在左边距，看起来像分隔条。改成箭头是 `FlowRow` 的**兄弟**：固定在右边缘、与第一行齐平，
和折叠态（箭头在一行 chip 的末尾）在视觉上落回同一个位置。

### 本轮踩的坑

- **`Modifier.padding` 没有 `(start, end, vertical)` 这个重载**。我把它和
  `(start, top, end, bottom)` 混着写，编译报「None of the following candidates is applicable」，
  三个 job 一起红——因为它们都要编译。这是这一轮唯一一次三个 job 同时失败。
- **`asStateFlow()` 不能写在组合里**。`setContent { LiveHubApp(viewModel, flow.asStateFlow()) }`
  被 lint 的 `FlowOperatorInvokedInComposition` 判成 error，`Build & test` 红而另外两个 job
  绿——这也说明三个 job 不是冗余的：lint 只在一个里跑。
- **HTTPS 到 github.com 中途不通了**。这一轮开始时 `git push` 一直 `Could not connect to
  server`，但 `github.com:22`（SSH）与 `api.github.com:443` 都正常，于是改用
  `git push git@github.com:SilkKirk/LiveHub.git main`。记在这里：`ci.py` 走 api.github.com，
  和 git 的 HTTPS 通道不是同一条，一个不通不代表另一个也不通；排查顺序应该是
  「DNS → TCP 22/443 分别测 → SSH 兜底」，不要一直重试同一个通道。

### 本轮验证

| 检查项 | 结果 |
| --- | --- |
| `:app:lintDebug` | ✅ 0 error（23 条告警，仍是既有的依赖版本与图标形状那几类） |
| JVM 单元测试 | ✅ 756 个用例（新增 6 个） |
| 仪器测试 | ✅ 43 张截图，看过 `11-search-results`（名字不再截断）与 `01b-home-category-expanded`（箭头到右上） |
| Release assembly（R8） | ✅ |
| CI | ✅ `a8e6490` 三个 job 全绿 |

红过两次：`e28c636`（padding 重载，三个 job 全红）、`aa99bb2`（lint，只有 `Build & test` 红）。

### 下一轮

- 媒体通知 + 前台服务：关掉 PiP 之后音频还在继续，需要一个能停的入口。
- `setAutoEnterEnabled`：手势进入 PiP 的无缝动画，要有真机才验证得了。
- 「开播提醒」按用户要求搁置（worker 每 30 分钟已经在查开播状态，真要做得只差一条通知）。
- 真机验证：这一轮尤其需要——画中画的进入/退出/关闭三条路径在 CI 上一条都跑不到。

---

## 第六十八轮迭代：应用被隐藏时暂停，而不是留一段没人能停的声音

上一轮记下的洞：关掉画中画窗口之后，声音继续放，屏幕上什么都没有，也没有通知可以停。

**完整的解法是 `MediaSessionService` + 前台服务 + Android 13 的通知权限**，这样做还能把
锁屏/蓝牙/媒体面板一起接上。这一轮没有做它，理由写在下面「为什么停在这一半」；做的是另一半：
让那个状态**不存在**。

### 暂停从组合里搬进状态

`paused` 原本是播放页里的 `remember`。它工作得很好，也确实"随页面销毁而复位"，但它对页面
之外的一切不可见——包括 activity，而这一轮正是 activity 要暂停。

- `UiState.playerPaused` + `UiAction.SetPlayerPaused(paused)`：双击和 activity 走同一个入口。
- `openRoom` / `closePlayer` / `back` 都清掉它。不这么做，上一个房间（或上一次熄屏）的暂停
  会被下一个房间继承——用户点开直播间，画面是冻的，还找不到原因。
- 播放页的双击现在读 `state.playerPaused`。**这里有个坑**：`pointerInput(Unit)` 的闭包只在
  首次组合时创建，直接读参数会一直读到那一刻的 `state`，双击就成了"永远把首次的值取反"。
  用 `rememberUpdatedState` 包一层再读。

### `onStop` 就是那条退出路径

`MainActivity.onStop()`：没有缩窗却被隐藏时暂停。有 PiP 窗口时 activity 只是 paused、不会
stop，所以这条路径不会误伤正在缩窗播放的流；它接住的是三种情况——用户关掉了缩窗、系统拒绝了
开窗、熄屏。

不自动恢复：回来时停在那一帧，「已暂停 · 双击继续」的角标和手动暂停长得一模一样，
不需要再记一个"这次是自动暂停的"标记。

### 为什么停在这一半

`MediaSessionService` 里的 `startForeground` / 通知渠道 / `POST_NOTIFICATIONS` 运行时权限，
以及"服务没起来"（`ForegroundServiceDidNotStartInTimeException`）和"通知挂不上"这两类失败，
**在 CI 上一条都跑不到**：单元测试碰不到服务生命周期，仪器测试里那 43 张截图也碰不到。
这一轮之前的所有改动都能被 CI 判个对错，这一块不能——而它的失败形态是崩溃或一条停不掉的通知，
比"没有后台播放"糟糕得多。所以先把「无人能停的声音」消掉，后台播放留到有真机在环的时候做。

### 本轮踩的坑

`PlayerChromeTest` 新加的角标用例用了 `assertIsDisplayed`，而这个文件此前只用过 `assertTrue`
和 `count`，import 里没有它——**只有单元测试那个 job 红**，另外两个绿。这已经是第三次
"红在一个 job 上"了（lint 只在 `Build & test` 里跑、`assembleDebugAndroidTest` 只在仪器 job
里跑），值得记住：单看某一个 job 的绿不能推整体。

### 本轮验证

| 检查项 | 结果 |
| --- | --- |
| `:app:lintDebug` | ✅ 0 error |
| JVM 单元测试 | ✅ 758 个用例（新增 2 个） |
| 仪器测试 | ✅ 43 张截图 |
| Release assembly（R8） | ✅ |
| CI | ✅ `efe0b89` 三个 job 全绿 |

`0d7d295` 红过一次（缺 import，只有 `Build & test`）。

### 下一轮

- **媒体通知 + 前台服务**：`MediaSessionService` 接管播放器，UI 换成 `MediaController`，
  锁屏/蓝牙/通知一起来。要有真机在环——这也意味着这一条不适合在没有设备的时候做。
- `setAutoEnterEnabled`（第六十七轮记的）。
- 真机验证清单已经很长了：PiP 三条路径、`onStop` 暂停、音频焦点、局域网配对、四平台弹幕。

---

## 发版：v0.19.0

第六十五到第六十八轮之后发版（`v0.18.0..v0.19.0`，16 个提交）。Release workflow 一次通过：

- 产物 `LiveHub-v0.19.0-release.apk`，4.69 MB（4914456 字节），
  sha256 `6f0f1f8829c0fad3eac5f810f4ff50c651287b56c7c184b44821de23a3bd251b`，随包附
  `*.sha256.txt`。
- **release 签名**，不是回退的 debug 包：证书 `CN=LiveHub, OU=Personal, O=SilkKirk, C=CN`，
  证书 SHA-256 `2e45831c…bda33c`。workflow 里那条「release 包被 debug 密钥签名就让任务失败」
  的检查没有触发。
- 已标记为 latest，`prerelease = false`。
- `versionCode` 由标签推导（0.19.0 → 1900），高于 0.18.0 的 1800，覆盖安装不会被判成降级包。

体积与 v0.18.0 持平（都是 4.69 MB）。这一轮最重的新东西是画中画，而它只用框架 API
（`onUserLeaveHint`、`PictureInPictureParams`、`onPictureInPictureModeChanged`），没有引入
任何依赖。后台播放那一轮如果做 `MediaSessionService`，会带进 `media3-session`——体积和
风险都会变，这是把它单独留出来的另一个理由。

标签信息里是完整的变更说明（第六十五到六十八轮的修复与新能力）；Release 正文由
`generate_release_notes` 生成，附 `compare/v0.18.0...v0.19.0` 链接。

### 下一轮

- 媒体通知 + 前台服务（要真机在环）。
- `setAutoEnterEnabled`（要真机）。
- 不依赖真机的候选：关注页搜索/分组、四平台聚合搜索、把 `TouchTargetSizeCheck` 开成 error。
- 真机验证清单见上。

---

## 第六十九轮迭代：退出播放页缩成小窗

在这一轮之前，back 出播放页就是整段结束：解析出来的地址、弹幕 socket、画面全销毁，再进来
从解析开始重来一遍。现在退出去，右下角留一个小窗继续播。

### 一个组合，三种尺寸

这一轮的关键不是小窗本身，是**它和整屏播放是同一个组合**。

播放层在 `LiveHubRoot` 里只有一个调用点：整屏、小窗、画中画三种状态只改外层 `Box` 的
大小与位置。原因是换调用点会销毁持有 `SurfaceView` 的那个 `AndroidView` —— 也就是销毁
`ExoPlayer`、重新 `setMediaSource`+`prepare`。那样每次退出播放页都要黑一秒再重新连上 CDN，
小窗就成了个"看起来在播、其实重启了"的东西。同一调用点 + 改尺寸，`PlayerView` 只是重新
布局，音频连一帧都不会断。

代价是 `LiveHubRoot` 的结构变了：以前 `screen == Player` 时整棵树换成播放页、不套 `Scaffold`；
现在永远是一个 `Scaffold`，播放层是它的内容里最后一块，`shellHidden`（播放页或画中画）时
把底部导航、各个页面和窗口内边距全部让开。`contentWindowInsets` 在这一态要清零 —— 不清理的话
播放页会被 `Scaffold` 和自己的 `windowInsetsPadding(statusBars)` 各内缩一次，画面顶上多一条。

### 状态

- `UiAction.MinimizePlayer` / `ExpandPlayer`：back 与左滑（`BackSwipeContainer` 从根布局搬进
  `PlayerScreen`，这样它在小窗里也还在树上）改成最小化；小窗的 × 才是 `ClosePlayer`。
- `UiState.miniPlayer` 是派生的：`room != null && screen != Screen.Player`。不新增字段 ——
  "有房间"和"不在播放页"本来就在状态里，第三个字段只是多一个要维护的一致性。
- `ClosePlayer` 在小窗里关闭时**不换页**：用户本来就在浏览。只有从播放页关闭时才回到
  `playerReturnScreen`。
- Home 上按 back 先关小窗（栈的顺序），再按才是退出应用。

### 小窗自己的一层

`MiniPlayerChrome`：整窗的点击层（展开）+ 右上角的 ×（关闭）。× 声明在点击层**之后** ——
在 `Box` 里最后一个孩子才拿得到指针事件，反过来会被整窗的点击吞掉（`PlayerContent` 的
手势层踩过一次同样的坑）。小窗里没有其它控件：没有展开箭头（点画面就是展开），也没有拖拽。

### 画中画与暂停跟着改

- `PictureInPicture.shouldEnter` 从"在播放页"改成"有房间"：小窗里也是在看，离开应用不该
  把正在角落里播的流暂停掉。
- `MainActivity.onStop` 同样按房间判断：小窗会随应用一起消失，所以它也要暂停。
- `PlayerContent` 的参数 `pip` 改名 `compact`：小窗与画中画共用「只留画面」这一支，
  叫 `pip` 已经名不副实。

### 已知取舍

- 小窗没有拖拽关闭、也没有吸附到边缘：× 和 Home 上的 back 都能停，够用；拖拽要一整套
  手势与动画，留到有真机可看的时候。
- 小窗里的失败状态（解析失败、解码失败）会被 168dp 裁掉一部分，点开才看得全。这是有意的：
  小窗的职责是"还在播"，不是"解释为什么没播"。
- 换房间、切平台、切 tab 都不影响小窗；只有 ×、back、和打开另一个房间会动它。

### 本轮验证

| 检查项 | 结果 |
| --- | --- |
| `:app:lintDebug` | ✅ 0 error |
| JVM 单元测试 | ✅ 763 个用例（新增 5 个） |
| 仪器测试 | ✅ 44 张截图，新增 `06b-home-mini-player` 并逐张看过 |
| Release assembly（R8） | ✅ |
| CI | ✅ `c46d397`、`0a44d47` 两个提交全绿，一次通过 |

`06b-home-mini-player` 是这一轮唯一新增的视觉证据：其余 43 张里 `room` 都是 null，播放层
根本不出现。画面里是"正在解析播放地址"的转圈 —— `capture` 对这个状态走 `LiveHubRoot`，
接不上播放页那边注入的 `videoSurface` 桩，而真实 `SurfaceView` 拍出来是一块黑，反而看不出
窗口形状。

### 下一轮

- **真机第一条要看的就是这一轮**：从播放页退出到小窗，画面是否真的一帧不断（没有任何地方
  能证明"没重建解码器"，除了设备）。
- 小窗拖拽关闭 / 吸附。
- 媒体通知 + 前台服务（要真机在环）。
- 真机验证清单：小窗、PiP 三条路径、`onStop` 暂停、音频焦点、局域网配对、四平台弹幕。

---

## 第七十轮迭代：四平台聚合搜索

一个聚合客户端最该有、却一直没有的能力：同一个主播在四个平台各搜一遍，过去要切四次 chip、
打四次关键词。README 里"对比同一个主播在斗鱼和虎牙"就是搜索存在的理由，而实现上是四次独立
搜索。

### 「全部」放在平台行首位

`UiState.platform` 一直是"浏览的那个平台"——搜索页此前**借用**它做自己的选择，所以"全部平台"
在状态里无处安放。这一轮加一个 `searchAllPlatforms`，`SelectSearchPlatform(null)` 表示全部：

- 选「全部」**不改动** `platform`：首页仍在那个平台，这个 chip 说的是搜索看哪里。
- 平台的选中态跟着模式走：全部选中时四个 chip 都不选中；再点某个平台退回单平台（并清掉失败表）。
- 已经选中时再点一次不重搜（和单平台 chip 的行为一致）。

### 四个请求一起发，结果按平台分组

`searchEverywhere`：`coroutineScope + async` 四个平台并行，`Platform.entries` 顺序拼接，
界面在 `LazyColumn` 里先放分组标题再放该平台的行。卡片本来就没有平台标签（第二十九轮删的，
理由是搜索结果永远只有一个平台），所以这个模式下平台名只能来自分组标题——否则四个来源混成
一列，看不出哪行是哪家的。

### 一个平台失败不算搜索失败

抖音匿名关键词搜索必然返回 2483（README 里记了三轮）。如果沿用单平台那套"失败就清空并报错"，
跨平台搜索在未登录时会变成一个空页面，而另外三个平台其实好好答了。

于是多了一个 `searchFailures: Map<Platform, String>`：失败平台逐条点名，作为列表里的独立一行
（`抖音：需要登录后按昵称搜索`）；四个平台全失败时这句话进空状态描述里。**不**再借用 `error`
—— 那个字段是"整个搜索失败了"的意思，语义不同。

### 这个模式没有翻页

每个平台各有游标，"加载更多"需要四个游标加一套"哪个先空"的说法。跨平台是**每平台一页**，
单平台保留原有翻页；`loadMoreSearch` 在这个模式下直接返回。

### 本轮验证

| 检查项 | 结果 |
| --- | --- |
| `:app:lintDebug` | ✅ 0 error |
| JVM 单元测试 | ✅ 767 个用例（新增 4 个） |
| 仪器测试 | ✅ 45 张截图，新增 `11e-search-all-platforms` 并看过 |
| Release assembly（R8） | ✅ |
| CI | ✅ `c296f13` 三个 job 全绿，一次通过 |

新增截图里能读到的形状：字段标签是「在斗鱼、虎牙、抖音、哔哩哔哩里搜索」，chip 行是
全部/斗鱼/虎牙/抖音 + 第二行的哔哩哔哩，然后是「斗鱼 1 个结果」→ 卡片、「虎牙 1 个结果」→ 卡片、
`抖音：需要登录后按昵称搜索`（灰）、「哔哩哔哩 1 个结果」→ 卡片。

### 下一轮

- 跨平台搜索的翻页（四个游标）。
- 关注页搜索/分组（几百个关注时找人有意义）。
- 把 `TouchTargetSizeCheck` 开成 error，把 48dp 这类问题钉死。
- 真机验证清单不变：小窗、PiP 三条路径、`onStop` 暂停、音频焦点、局域网配对、四平台弹幕。

---

## 第七十一轮迭代：关注页按名字过滤

关注列表按「开播优先 + 手动顺序」排，一百个关注时找一个人只能靠滚。这一轮加一个名字过滤框。

### 框只在它帮得上忙的时候出现

和上方 chip 行同一条规则（那一行的条件是"能收窄才画"）：`FOLLOW_SEARCH_THRESHOLD = 12`
——约六行、两个滑屏。以下时一滑就到，头顶 56dp 输入框是没人用的装饰；以上时它就是最直接的入口。

有一个例外：**已有查询时无视阈值**。否则结果收窄到 3 个的那一刻，输入框会从手指底下消失。

### 命中名字或标题，忽略大小写

用户记住的是两个字段中的一个：列表里认人（主播名），标题里认节目。四个平台的昵称都混着拉丁
和 CJK（`DNF` / `dnf` / `PDD`），所以 `ignoreCase`。

### 空状态的歧义

原来所有空结果都是「关注的主播都没有在播」——有查询时那是句假话。现在有查询时换成
「没有找到「x」」+「清空搜索」，没有查询时才按 `直播中 / 未开播 / 全部` 分三种说法。

**特别关注头像行不受查询影响**：它自己的契约就是"不被下方那一行筛选影响"（第六十五轮写的），
查询也是下方那一行。

### 本轮验证

| 检查项 | 结果 |
| --- | --- |
| `:app:lintDebug` | ✅ 0 error |
| JVM 单元测试 | ✅ 772 个用例（新增 5 个） |
| 仪器测试 | ✅ 46 张截图，新增 `07c-follow-search` 并看过 |
| Release assembly（R8） | ✅ |
| CI | ✅ `fe47b11` 三个 job 全绿，一次通过 |

新增截图（14 个关注）里能读到：标题行「关注 7/14 正在直播」、搜索框、只有 ＋ 的特别关注行、
三个 chip、以及两行卡片——也就是这个框在最小屏上要花掉的高度，`07-follow-list`（5 个关注）
不受影响，是这一轮没有动到短列表的证据。

### 下一轮

- 跨平台搜索的翻页（四个游标）。
- 关注分组/标签（比过滤更进一步，要给关注打标签并同步）。
- 把 `TouchTargetSizeCheck` 开成 error。
- 真机验证清单不变：小窗、PiP 三条路径、`onStop` 暂停、音频焦点、局域网配对、四平台弹幕。

---

## 第七十二轮迭代：跨平台搜索翻页

第七十轮的跨平台搜索只取每个平台一页，理由写在那一轮里："加载更多"要四个游标。这一轮把四个
游标补上，那条限制随之作废（第七十轮那段记录是当时的事实，不改它）。

### 一个游标变成一张表

`searchCursor: String?` 换成 `searchCursors: MutableMap<Platform, String?>`。单平台模式就是这张表
里只有一项——不是两套机制，是一个能装一到四项的表。提交、切平台、清空输入都改成清表。

### 一次触底，每个还有游标的平台各拉一页

`loadMoreEverywhere`：从表里取出**当前**的游标（不是进协程再读——请求要带的是做决定那一刻的
游标），并行请求，结果按 key 去重后追加。追加在列表尾部不影响分组：界面是按 `Platform` 顺序
过滤渲染的，所以新一页自然回到它自己的分区里。

`searchHasMore` 在跨平台模式下表示"至少还有一个平台有下一页"。

### 失败的那一页：丢游标 + 点名

某个平台的第二页失败时，重试同一个游标只会重复失败，所以**丢掉它的游标**（这个分区到此为止），
并把它记进 `searchFailures`。

界面因此改了一处：失败行原来只在该平台**没有结果**时画，现在放在该分区之后 —— 一个平台完全
可能第一页答了、第二页挂了，"这一段不再增长"和"这个平台一个字没说"一样需要解释。

### 一个没有被测试直接覆盖、但值得记的边界

没有游标的平台不会被问第二次。如果被问，`cursor = null` 会再拿一次第一页，而 `distinctBy` 会
把重复的房间悄悄丢掉——结果看起来完全正常，只是白打了一次请求。所以用例里给假适配器加了
调用日志，断言 B 站只被问过一次。

### 本轮验证

| 检查项 | 结果 |
| --- | --- |
| `:app:lintDebug` | ✅ 0 error |
| JVM 单元测试 | ✅ 773 个用例（新增 1 个） |
| 仪器测试 | ✅ 46 张截图（这一轮不改任何渲染） |
| Release assembly（R8） | ✅ |
| CI | ✅ `d047cd6` 三个 job 全绿，一次通过 |

### 下一轮

- 关注分组/标签（比过滤更进一步，要给关注打标签并走同步——会动到 `FollowRecordSet` 的记录格式，
  得单独一轮）。
- 把 `TouchTargetSizeCheck` 开成 error。
- 小窗拖拽关闭 / 吸附。
- 真机验证清单不变：小窗、PiP 三条路径、`onStop` 暂停、音频焦点、局域网配对、四平台弹幕。

---

## 第七十三轮迭代：把「48dp」变成 CI 能抓的东西

这一轮没有功能，只有两件防回归的事。起因很具体：48dp 这条线在本项目被破过两次，而**两次都是
读代码发现的，当时测试全绿**。

### 静态那一半：`TouchTargetSizeCheck` 开成 error

`app/build.gradle.kts` 里加了 `lint { error += "TouchTargetSizeCheck" }`。Compose 自带这条
检查，本来就是 warning；提成 error 之后，第三次"`Modifier.size(32.dp)` 加在 `IconButton` 上"
这类写法会在 `:app:lintDebug` 阶段就红。

第一次跑就通过——说明现在没有静态可判的违规，它防的是下一次。

### 动态那一半：真的点一下边缘

`TouchTargetTest` 直接对卡片星标做触摸注入，而不是量语义边界：

```kotlin
onNodeWithContentDescription("关注").performTouchInput { click(Offset(6f, 24f)) }
```

6dp 在 40dp 之内、32dp 之外，正好把"padding 版本"和"现在"分开。

**这条用例第一次是红的，而它红出来的事实比用例本身重要**：第一版点的是节点自框的 (4,4)，
结果落到了卡片上——说明 Material3 的 `IconButton` 是 **48dp 节点 + 40dp state layer**，
最外那圈**不属于指针区域**。

所以第五十九/六十五轮那句「`size(48.dp)` 会让内层那个 40dp 被约束撑到 48dp」是错的：48 是
**节点**尺寸，不是手指能到的地方。`size(48.dp)` 的真实收益是 40dp（Material 自己的 state
layer），比 padding 版本的 32dp 好，也已经是这个组件能给的上限。代码注释和这条记录都按事实改了
——留着一句听起来更漂亮的错话，下一次就会有人照着它推理。

顺带核对另外三处触控修正：`PillButton`、全屏栏两个图标、小窗的 × 之外的两处，`clickable`
都直接加在 48dp 的盒子上（全屏栏那个是 `size(48).clickable().padding(12)`，可点在外、内边距在
内），所以它们是真的 48dp；只有 `IconButton` 受 40dp 上限约束。

### 本轮验证

| 检查项 | 结果 |
| --- | --- |
| `:app:lintDebug` | ✅ 0 error（含新提级的 `TouchTargetSizeCheck`） |
| JVM 单元测试 | ✅ 775 个用例（新增 2 个） |
| 仪器测试 | ✅ 46 张截图（这一轮不改渲染） |
| Release assembly（R8） | ✅ |
| CI | ✅ `712451c` 三个 job 全绿 |

`33b6107` 红过一次，红的就是上面那条新用例——它是这一轮唯一一次"测试抓到了实现之外的真相"。

### 下一轮

- 关注分组/标签（会动 `FollowRecordSet` 的记录格式，得单独一轮）。
- 小窗拖拽关闭 / 吸附。
- 媒体通知 + 前台服务（要真机在环）。
- 真机验证清单不变：小窗、PiP 三条路径、`onStop` 暂停、音频焦点、局域网配对、四平台弹幕。

---

## 第七十四轮迭代：关注标签；小窗拖出去关闭

发版前的最后两条欠账。

### 关注标签

给关注的直播间加自由标签，关注页按标签筛选，标签随关注记录同步。

**它存在房间记录里**，和 `order`、`special` 并列的一个可选字段 —— 不新增记录类型、不升
`RECORDS_VERSION`。上一轮我说"这会动到同步记录格式，得单独一轮"，看过 `FollowCodec` 之后那个
判断可以收回：记录的值本来就是一段 JSON，加一个**只在非空时写出**的字段，老版本读到就是
"没有标签"，不需要迁移。

代价写在注释里：旧版本重写记录（换序、特别关注、30 分钟快照）会把它不认识的 `tags` 丢掉。
两台设备都跑这个版本时不会发生；这也正是没有把标签做成独立记录类型的原因 —— 那需要升版本
和一次迁移。

三条会重写记录的路径都显式带上标签：**特别关注 toggle、30 分钟快照、标签写入本身**。`special`
当年就是这么丢的，所以 `FollowTagStoreTest` 专门钉了前两条，而不是只测"写了能读回来"。

界面：标签 chip 进筛选行（带 `#` 前缀，免得和平台 chip 撞名）；**长按卡片**打开编辑面板，
面板列出所有已用标签，第二个房间基本是点一下而不是再打一遍 —— 卡片上已经有单击（打开）和
星标（特别关注），第三个常驻控件不值得占 140dp 的角。

空状态：标签筛选无匹配时是「没有带「x」的主播」，按钮改成「清空筛选」（它现在同时清掉名字
查询与标签）。

### 小窗拖出去关闭

小窗可以横向拖走：拖过宽度的 40% 关闭，否则用 `animateFloatAsState` 弹回。

阈值取 40% 而不是"任何拖动"：小窗正下方就是列表，横着划一下是滚动列表的起手式，顺手关掉
正在播的直播间太狠。× 保留 —— 没人发现过的手势不能当唯一出口。

### 本轮验证

| 检查项 | 结果 |
| --- | --- |
| `:app:lintDebug` | ✅ 0 error |
| JVM 单元测试 | ✅ 785 个用例（新增 10 个） |
| 仪器测试 | ✅ 46 张截图（没有 fixture 带标签，也没有拖拽的静止态） |
| Release assembly（R8） | ✅ |
| CI | ✅ `9b263de` 三个 job 全绿，一次通过 |

没有给标签面板加截图：它只能靠长按打开，而截图 harness 目前只会点（`clickDescription`）。
这一块由 `FollowTagsTest` 覆盖，视觉上留给下一轮补 —— 记在这里而不是假装它被看过。

### 下一轮

- 标签面板的截图（需要给 harness 加一个"长按某段文字"的参数）。
- 媒体通知 + 前台服务（要真机在环）。
- 真机验证清单不变：小窗、PiP 三条路径、`onStop` 暂停、音频焦点、局域网配对、四平台弹幕。

---

## 发版：v0.20.0

第六十九到第七十四轮之后发版（`v0.19.0..v0.20.0`）。Release workflow 一次通过：

- 产物 `LiveHub-v0.20.0-release.apk`，4.70 MB（4930840 字节），
  sha256 `87e86348017e6f5a9c9821ade275672f4cfaeb78822be30093f016fd30d1dd42`，随包附
  `*.sha256.txt`。
- **release 签名**：证书 `CN=LiveHub, OU=Personal, O=SilkKirk, C=CN`，workflow 里那条
  「release 包被 debug 密钥签名就失败」的检查没有触发。
- 已标记为 latest，`prerelease = false`。
- `versionCode` 由标签推导（0.20.0 → 2000），高于 0.19.0 的 1900。

体积比 v0.19.0 涨了 0.01 MB（4.69 → 4.70）。这一轮的代码不少（小窗、聚合搜索、标签、
翻页），但没有新增依赖——画中画用框架 API，标签用现有 JSON、搜索复用已有适配器。

标签信息里是分项说明；Release 正文由 `generate_release_notes` 生成，附
`compare/v0.19.0...v0.20.0` 链接。

### 这一版之后仍空着的

- **媒体通知 + 前台服务**：关掉小窗之后音频仍会继续（和 0.19.0 之前一样），需要真机在环。
- **真机验证**整张清单：小窗切换是否真的不重建解码器、PiP 三条路径、`onStop` 暂停、
  音频焦点、局域网配对、四平台弹幕、平板列数。这是从第五十九轮起就存在的问题，这一版又
  多了三项只在 CI 上验证过的东西。
- 标签面板没有截图（harness 只会点，不会长按）。

---

## 第七十五轮迭代：标签的两张截图，以及截图自己抓到的排序问题

上一轮结尾留了一句「标签面板没有截图，因为 harness 只会点、不会长按」。这一轮补上，
顺带给标签 chip 行也拍了一张 —— 而那张图暴露了一个真问题。

### harness 加了「长按某段文字」

`capture(longClickText = …)`：长按匹配该文字的节点，再推进一帧。标签面板只能靠长按打开，
没有任何 `UiState` 能表达「面板开着」，所以除了真的按一下没有别的办法拍到它。

### 第一张图什么都没证明

`07d-follow-tags` 的第一版拍出来是一行**没有任何标签**的 chip 行：标签排在
全部/直播中/未开播/斗鱼/虎牙 之后，被横向裁在屏幕外。

这不是截图的问题，是排序的问题 —— 那一行是横向滚动的，**位置就是优先级**。标签是用户
自己建的、来这里就是要用的；平台 chip 只在跨平台关注时出现，而且一滑就到。所以把标签挪到
平台之前，并把 fixture 换成单平台三张卡：`#常看` 现在落在可见区里，第二个标签仍在一滑之外
（滚动行的常态）。

如果只是「补一张图」，这件事会被写成"补了截图"就过去了；是**看图才发现图里没有要拍的东西**，
接着才发现排序不对。

### 面板那张

`07e-follow-tag-editor`：长按第一张卡打开面板，`expectText = "完成"` —— 没有它，一次什么都
没打开的长按也会拍出一张"看起来合理"的网格图。

### 本轮验证

| 检查项 | 结果 |
| --- | --- |
| `:app:lintDebug` | ✅ 0 error |
| JVM 单元测试 | ✅ 785 个用例（这一轮不改逻辑，只改一个 chip 的顺序） |
| 仪器测试 | ✅ 48 张截图，新增 `07d-follow-tags`、`07e-follow-tag-editor` 并逐张看过 |
| Release assembly（R8） | ✅ |
| CI | ✅ `0729a11` 三个 job 全绿 |

### 下一轮

- 媒体通知 + 前台服务（要真机在环）。
- 真机验证清单：小窗、PiP 三条路径、`onStop` 暂停、音频焦点、局域网配对、四平台弹幕。
- 若标签多到一行放不下，考虑单独一行而不是继续挤同一行 —— 现在只有第一两个标签保证可见。

---

## 发版：v0.20.1

v0.20.0 之后紧接着的一次补丁发版。原因是 v0.20.0 的 tag 打在第七十四轮上，而标签 chip 的
可见性问题是**第七十五轮看图时才发现**的：加了标签却看不到，等于没加。这种"发出去之后
才发现"的东西不该等到下一个功能版。

- 产物 `LiveHub-v0.20.1-release.apk`，4.70 MB（4930840 字节，与 0.20.0 相同 —— 这一版
  只改了一个 chip 的先后顺序），sha256
  `526f671d561e091fa2f7bb5e5b8cdbe07aaf8d32ebcd69780ecf9a25b1bd7168`。
- release 签名、latest、`versionCode` 2001。

从这一版起，"截图"这件事有了一个明确的用途：不只是留档，**看图本身就是一次验证**。
第七十五轮那张什么都没拍到的图，抓到了一个读代码和跑测试都抓不到的排序问题。

---

## 第七十六轮迭代：标签单独一行

第七十五轮把标签挪到平台 chip 之前，只解决了**第一个**标签的可见性：一行横滚、前面还有三个
状态 chip 时，第三个标签照样在屏幕外。标签是**自己会长**的一类，本来就不该和固定的小集合
挤在同一行。

- 标签挪到自己一行，有标签或正在按标签筛选时出现，从左边开始排。
- 上面那行的可见条件回到原来的判断（不再因为"有标签"而出现）—— 三个状态 chip 在没有任何
  标签时仍然是"能收窄才画"。
- 代价：有标签时多 40dp。640dp 窗口上（头像行 + 两行 chip）网格还剩约两行卡片，
  与第七十一轮量过的头部高度是同一量级。

用例断言的是**位置**而不是存在性：`#常看` 的顶边必须在「全部」之下。存在性一直是真的 ——
第七十五轮的问题恰恰是"东西存在，但在屏幕外"。fixture 同时改成 1 直播 + 1 未开播，否则
状态行根本不该出现，断言会拿一个不存在的节点做比较（这种"测试因为错误的理由通过或失败"
是这个仓库最常记的一类坑）。

### 本轮验证

| 检查项 | 结果 |
| --- | --- |
| `:app:lintDebug` | ✅ 0 error |
| JVM 单元测试 | ✅ 786 个用例（新增 1 个） |
| 仪器测试 | ✅ 48 张截图，`07d-follow-tags` 重新渲染后三个标签都在一行里 |
| Release assembly（R8） | ✅ |
| CI | ✅ `a8ceba0` 三个 job 全绿 |

### 下一轮

- 媒体通知 + 前台服务（要真机在环）。
- 真机验证清单：小窗、PiP 三条路径、`onStop` 暂停、音频焦点、局域网配对、四平台弹幕。
- 这一轮的改动还没有进过发版包：v0.20.1 里是"标签排在平台之前"，不是独立一行。

---

## 发版：v0.20.2

第七十六轮的补丁发版。v0.20.1 里的标签排序只解决了**第一个**标签的可见性，这一版才是独立一行。

- 产物 `LiveHub-v0.20.2-release.apk`，4.70 MB（4930840 字节，与 0.20.0/0.20.1 相同 ——
  三次发版都没有新增依赖，这一版只挪了一行 chip），sha256
  `de43fbffd5d06954e9e4e2fcc82f2b731d6ae5f9d9ff8c5e5290f52b02f9a383`。
- release 签名、latest、`versionCode` 2002。

连续三个补丁版（0.20.0 → 0.20.1 → 0.20.2）都是同一个功能线的收尾：标签先做出来、再被发现
"看不见"、再被发现"只看见一个"。前两次都是**看图**发现的，没有任何测试红过。这一版之后，
"标签"这条线才算真正做完。

### 之后

- 媒体通知 + 前台服务（要真机在环）：关掉小窗后音频仍会继续。
- 真机验证清单：小窗切换是否一帧不断、PiP 三条路径、`onStop` 暂停、音频焦点、局域网配对、
  四平台弹幕、平板列数。
- 在没有真机之前，可以做的只剩"再加一个功能"这类事，不再是"还欠着什么"。







---

## 第七十七轮迭代：播放页的三种形态收敛成一套 chrome

本轮不是从待办里挑的，是一次界面审计的结果（第七十六轮结束时待办只剩"媒体通知"这条要真机的，
但审计逐张核对 48 张截图 + 参考项目截图 + UI 源码后，发现播放页还有三个"进得去、出不来、
关注不了"的形态）。三条同源：**chrome 只承认按钮，不承认窗口与平台。**

### 缺陷是什么

1. **横屏播放页只剩一块视频。** 360dp 高的横屏窗口里，16:9 画面
   （`fillMaxWidth().aspectRatio(16f/9f)`）正好吃满高度，`RoomActionRow` 和弹幕列表被挤到
   窗口外；弹幕层要求 `fullscreen || verticalFullBleed`，而 `fullscreen` 只由按钮置位，
   所以横屏下**没有弹幕、没有关注、没有退出全屏**，屏幕上只剩视频 + 右侧三个圆钮，也不能滚动。
2. **竖屏直播铺满时没有关注按钮。** `verticalFullBleed` 分支只画主播名一行，`RoomActionRow`
   被 `if (!compact && !fullscreen && !verticalFullBleed)` 整个排除，而关注按钮只存在于那一行。
   抖音的直播基本都是竖屏，等于"进去之后关注不了"。
3. **竖屏播放页没有任何可见的返回。** `PlayerScreen.kt` 的原注释写得很清楚：
   "closing the player is the left-edge swipe or the system back gesture"。而同一个仓库给迷你窗
   加 × 的理由正是「a gesture nobody has discovered is not a control」（`LiveHubRoot.kt`）。

### 为什么会这样

三处都不是笔误，是三个各自合理、合起来不成立的决定：

- 第 1 条：`fullscreen` 是 `remember` 的一个布尔，只由按钮写。布局按"竖屏 + 按钮"设计，
  没人把"窗口变矮"当成一次输入 —— 而横屏正是唯一会让竖屏布局放不下的窗口形态。
- 第 2 条：全宽分支是为了"竖屏直播填满屏幕"加的，加的时候把操作行连同它下面的弹幕列表一起
  排除了。操作行里除了关注还有标题与分区，被排除是合理的；**唯一没被搬到别处的是关注**。
- 第 3 条：删掉旧胶囊头时，"返回和关闭都调 `ClosePlayer`，而且系统返回与左边缘滑动已经到处都能用"
  这个判断本身没错，但它被当成了"不需要控件"的理由。第 2 条与第 3 条叠在一起，就是竖屏直播态
  既不能关注也不能退。

而且**这三条都看不见**：`ScreenshotTest` 固定 320×640（第三十五轮明确决定不加横屏截图），
竖屏分支需要真解码器报告宽高比（`videoSurface` 一被替换就永远拿不到），所以它们一次都没进过
任何截图或断言。

### 怎么修的

- **横屏 = 全屏。** 新增 `playerFullscreen(landscapeWindow, manualFullscreen)`：窗口是横的
  即进入全屏，`fullscreen` 由它派生，不再需要 `LaunchedEffect` 去翻标志（也就没有"转回竖屏时
  忘记复位"这个状态）。横屏下：侧栏不画「退出全屏」（没有竖屏布局可退，按钮点了也不会变），
  全屏栏的返回改为「返回」= 缩小（与系统返回一致），`BackHandler` 在横屏下让位给
  `LiveHubRoot` 的返回。
- **`PortraitTopBar`。** 16:9 时只有左上角一个 48dp 返回；全宽时是 `[返回][主播名][关注星]`
  一行，带渐变遮罩。关注星**只在全宽时出现**：那时操作行不存在，它是唯一入口；
  16:9 时操作行里那颗药丸就在两行之下，再画一个只是重复。
- **错误/离线态不画返回。** 那两种状态自带居中的「返回」，同一屏两个同一动作是旧胶囊头被删的
  原因。判据按 `VideoSurface` 的 `when` 顺序重算：有画面、`loading`、或"既没 loading 也没
  error"的解析中态才画。
- **两个浮层顶栏补 `WindowInsets.statusBars`。** 画面在横屏/全宽下是 edge-to-edge 的，
  此前顶栏压在状态栏下面（真机上被时钟压住）。截图里 inset 为 0，所以这条**只有真机才验证得到**。
- **`PlayerContent` 新增两个参数。** `landscapeWindow`（横屏语义，决定返回键去哪）与
  `videoAspectHint`（解码器报宽高比之前先假定一个）。后者是这一轮能拍到竖屏分支的唯一办法，
  和已有的 `videoSurface` 参数同一类：harness 需要。

### 踩了什么坑

- **`videoAspect` 只能由真解码器教会。** `PlayerContent` 的 stub surface 不走
  `onVideoAspectRatioChanged`，所以 `verticalFullBleed` 在测试与截图里永远为 false —— 这才是
  这条分支七十六轮零覆盖的机械原因，不是"没人想到要测"。解法是加一个只作为初值的 hint，
  真解码器一报就覆盖。
- **第一版判据写漏了一种状态。** 先写的是 `effectiveVariant != null || state.loading`，漏掉了
  "既不 loading 也没有 error"的解析中态（那个分支显示的是同一个 spinner）——一个永远转圈、
  没有出口的屏幕。按 `VideoSurface` 的 `when` 逐支重算后才对。
- **`17b` / `17c` 逐字节未变**，说明截图环境里 `WindowInsets.statusBars` 是 0：状态栏 inset
  这一条在 CI 上证明不了，只能记下来留给真机。

### 本轮验证

| 检查项 | 结果 |
| --- | --- |
| `:app:lintDebug` | ✅ 0 error（Build & test job） |
| JVM 单元测试 | ✅ **790** 个用例（上轮 786，本轮新增 4 条），0 失败 |
| 仪器测试 | ✅ **49** 张截图（新增 `17e-player-vertical`），逐张看过 |
| Release assembly（R8） | ✅ |
| CI | ✅ `938ce47` 三个 job 全绿 |

新增/变化的截图：

| 图 | 变化 | 原因 |
| --- | --- | --- |
| `17e-player-vertical` | 新增 | 竖屏直播全宽分支此前零截图；这张同时证明「返回 + 主播名 + 关注」一行 |
| `17-player`、`17d-player-bilibili` | 变了 | 16:9 画面左上角多了一个 48dp 返回 |
| `18-player-loading` | 变了 | 转圈时也有出口了 |
| `19-` / `19b-` / `19c-` | 逐字节未变 | 错误与离线态自带「返回」，故意不画第二个 |
| 其余 43 张 | 逐字节未变 | 本轮只动播放页 |

`PlayerChromeTest` 新增 4 条，全部断言**位置或动作**而不是存在性：

- `the portrait player shows a way out over the picture`：返回按钮的 `boundsInRoot` 必须在
  操作行之上（探针：停在画面外的按钮在语义树里照样存在，第七十五轮那张图就是这么漏的），
  点击必须发出 `MinimizePlayer`；
- `a landscape window puts the way out on the bar`：横屏下恰好一个「返回」、零个「退出全屏」，
  点击 = 缩小；
- `a vertical stream keeps follow on the bar`：全宽时操作行不画（文本「关注」0 个）、
  星形按钮 1 个、点击发出 `ToggleFollow`；
- `rotation decides full screen`：纯函数三条，横屏 / 竖屏 / 手动。

### 没做到的

- **横屏的真实观感**仍然没有截图。Robolectric 里 `w640dp-h360dp` 只能断言控件在不在，看不到
  16:9 画面在 640×360 里怎么排、弹幕层叠在什么位置。真要做视觉回归，还得按第三十五轮说的
  在 CI 矩阵里加一台横屏模拟器。
- **状态栏 inset 只有真机能验**（见上）。
- **平板横屏**：`playerFullscreen` 用的是 `orientation`，所以 1024×768 的平板一进播放页就是全屏。
  它仍有返回/关注/弹幕层，只少了标题与分区那两行（在操作行里）。若以后要区分"手机转屏"与
  "平板本就横着"，判据应改成窗口高度而不是方向。

### 下一轮

审计里剩下这些（按我判断的优先级）：

- **首页平台 tab 在 320dp 上把「哔哩哔哩」裁成「哔哩」**（`01-home.png` 可复核）：4 个 tab
  平分 264dp，`Text(maxLines = 1)` 默认 `Clip`。参考项目底部导航写的是「B站」——平台短名有现成先例。
- **搜索页平台 chip 折成 4+1 两行**：`SearchScreen` 用 `FlowRow`，而首页/关注页的同类行都是
  `LazyRow`。同一类控件两种行为才是根因。
- **关注页不能取关**：星标在那一页是「特别关注」，长按是标签，没有取关入口。
- chip 选中态是 `secondaryContainer`（青），与品牌红并存；`labelSmall` 10sp 偏小；
  卡片上每张都画 LIVE 角标（首页全是直播中）。
- 功能面：播放历史、分享直播间链接、后台刷新开关、自动同步。
- 媒体通知 + 前台服务仍然要真机在环。


---

## 第七十八轮迭代：平台名在窄容器里的两种失真

从第七十七轮审计清单的第 1、2 条开工。两条同一个根因：**控件里塞了本该给句子用的全名。**

### 缺陷是什么

1. **首页平台 tab 把「哔哩哔哩」画成「哔哩」**（`01-home.png` 顶部可复核）。四个 tab 平分
   `320 - 8 - 48 = 264dp`，每个 66dp，扣掉 `Tab` 自己的内边距只剩两个字的位置；单行 `Text`
   默认 `TextOverflow.Clip`，于是既不省略也不换行，直接切掉——不报错，测试也不会红。
2. **搜索页平台 chip 折成 4 + 1**（`09-search-idle.png`）：「哔哩哔哩」独占第二行。
   那一行用 `FlowRow`，而首页的分组行、关注页的筛选行都是 `LazyRow`——**同一类控件两种行为**，
   这才是根因，不是"字太长"。

### 为什么会这样

- `Platform` 只有 `displayName` 一个名字。第二十二轮把平台切换器从一排可滚的 `FilterChip`
  改成四等分的 `PrimaryTabRow`（当时是为了把头部从 41% 屏高降到约 100dp），宽度从"内容决定"
  变成了"四等分"——名字长度**第一次成为约束**，而模型里没有第二个可选的名字。
- 搜索页那一行是另一种偷懒：想要"排不下就换行"，用了 `FlowRow`，没注意到 App 里其它同类行
  都靠横滚。当时五个 chip 只在哔哩哔哩这一档溢出，于是最后一个芯片掉到第二行、独占一行。

### 怎么修的

- `Platform` 新增 `shortName`（斗鱼 / 虎牙 / 抖音 / **B站**），规则写进注释：**控件用短名，
  句子用全名**。tab 与 chip 改用它；`displayName` 留给正文（搜索失败行、平台分组标题、
  空态描述、播放页副标题、设置页关于卡）。枚举按名字序列化，加一个构造参数不影响已存数据。
  「B站」不是新造的叫法：参考项目自己的底部导航就写 B站（`screenshots/douyu.jpeg`）。
- 搜索页平台行改 `LazyRow`（与其它 chip 行一致），并在选中项**真的不在可见范围**时
  `scrollToItem` 把它带进来。判据必须等第一次布局：`layoutInfo` 在首帧是空的，第一版把它当成
  "什么都不可见"，于是默认选中的斗鱼被顶到最左、把「全部」切掉一半——`09-search-idle` 那张图
  当场抓到了（见"踩了什么坑"）。
- 关注页的平台 chip 一并改短名：同一个控件，同一条规则。

### 踩了什么坑

1. **Robolectric 的字体度量是桩，这条断言写不出来。** 第一版断言"每个 tab 的
   `TextLayoutResult.didOverflowWidth == false`"，CI 报：
   `B站 was clipped: it wants 2.5px and was given 34.0px`——两字标签的内在宽度只有 2.5px，
   `didOverflowWidth` 对肉眼完整的标签照样返回 true。节点 bounds 同样无用：被裁的标签报告的是
   "允许的宽度"，两种情况下完全相同。这正是仓库里所有密度类测试断言**位置**而不是**宽度**的
   原因。最终落成模型规则（`shortName` ≤ 2 字符，就是 tab 的预算），并在注释里写明渲染侧的
   证据是 `01-home.png`——真实字体只存在于仪器测试环境。
2. **滚动要下一帧才应用，截图只推进一帧。** `11c-search-bilibili` 的第一版拍到的是滚动**前**的
   状态：选中的 B站 在右边缘被切一半，看图会以为没修。试过给这一格补 `advanceMillis = 16`
   （一帧），**实测无效**——两次渲染逐字节相同。那条改动已撤回，记在这里：harness 冻结时钟，
   而 `scrollToItem` 需要 frames，推进一帧不够。行为本身由下面的用例守。
3. **那条用例第一版靠"几像素"通过。** `the restored platform is scrolled into view` 原来只
   `assertIsDisplayed`：右边缘露几像素也算 displayed，**滚动没生效时它照样绿**。改成对
   `boundsInRoot.right` 与 320dp 窗口比较——只有整块可见才通过。改完 CI 仍绿，说明滚动确实生效
   （否则这条会红），也说明原来那条断言是假的。

### 本轮验证

| 检查项 | 结果 |
| --- | --- |
| `:app:lintDebug` | ✅ 0 error（Build & test job） |
| JVM 单元测试 | ✅ **792** 个用例（上轮 790，本轮新增 2 条），0 失败 |
| 仪器测试 | ✅ **49** 张截图，逐张看过 |
| Release assembly（R8） | ✅ |
| CI | ✅ `186044d` 三个 job 全绿 |

截图：**25 张变化 / 24 张逐字节未变**。

| 分组 | 张数 | 为什么变 |
| --- | --- | --- |
| 首页（含平板/深色/迷你窗） | 12 | tab 第四个字变成「B站」 |
| 搜索（含四种状态与三个平台） | 11 | 平台行从两行变一行、chip 短名、选中项滚动 |
| 关注 | 2 | 平台 chip 短名（`07b` 是弹层背后的 chip 行，`07c` 是搜索框上方那行） |
| 其余（同步 / 设置 / 播放 / 空态） | 24（未变） | 本轮不动这些页 |

新增 2 条用例：`the restored platform is scrolled into view`（整块可见 + 选中态）、
`every platform tab label fits two characters`（模型规则，附 Robolectric 为什么测不了宽度的
注释）。`SearchPlatformSwitchTest.all four platforms are offered` 改成"滚动到每个平台再断言
可见"——原来那条在 320dp 上把第五个 chip 当成了不存在。

### 没做到的

- **五个 chip 一共需要约 364dp，320dp 的手机只有 296dp。** 本轮选了"横滚 + 选中项自动带入"，
  代价是默认位置下第五个 chip 只露出一点边（标准横滚提示，但与第七十六轮给标签单独开一行的
  判据不完全一致）。真想根治要做**更紧凑的 chip 变体**（8dp 内边距即可省下 80dp），但那会引入
  一个手写控件——触控目标、水波纹、`TouchTargetSizeCheck` 都得自己保证，值得单独一轮。
- **大字号下 tab 仍会截断**：「B站」两个字符在 `fontScale = 1.5` 时也要 48dp，66dp 的 tab
  扣掉内边距放不下。这条应该在字号那一轮一起解决（届时要么再缩内边距，要么让 tab 行可滚）。
- `11c` 拍不到滚动态（见坑 2）。若以后要视觉证明，正确做法是让 harness 能推进多帧，而不是
  在这一格加特例。

### 下一轮

审计清单还剩（`loop-runbook.md` 第五节）：

- **关注页不能取关**：星标在那一页是「特别关注」，长按是标签，没有取关入口。
- **视觉一致性一轮**：chip 选中态取 `secondaryContainer`（青）与品牌红并存；`labelSmall`
  10sp 偏小；首页每张卡都画 LIVE 角标（首页列出的全是直播中）。
- 功能面：播放历史、分享直播间链接、后台刷新开关、自动同步。
- 媒体通知 + 前台服务仍然要真机在环。


---

## 第七十九～八十一轮迭代：三条界面/功能项并行做（首次换成智能体分工）

从这两轮起循环的执行方式变了：把三条**写作用域互不重叠**的缺陷拆成三个任务，三个 agent
并行做，Lead 负责 review、分轮提交、跑 CI 与写记录。三条改动没有共享文件，所以一次推送
在 HEAD 上验证；若红，可按 commit 二分。集成时逐条核对过 diff 只落在各自作用域内。

提交：`598f08c`（79）、`5e1efbe`（80）、`71922bf`（81）、`b38aa2d`（截图 harness）。

---

## 第七十九轮迭代：关注页能取关了，但要两步

### 缺陷是什么

关注页没有任何取关入口。卡片上的点击是进直播间，星标在那一页被重新定义成「特别关注」，
长按打开的是标签面板——于是"清理二十个不看了的主播"要开二十个直播间再退出来。

### 为什么会这样

两个各自正确的决定叠出来的空洞：星标改成「特别关注」是对的（这一页每张卡都已关注，
一个只会移除的星标就是删按钮）；面板只为标签而写。没人回头问"那取关在哪"。

### 怎么修的

- `TagEditorSheet` 底部加一行：未确认态是 `取消关注`（error 色 TextButton），确认态换成
  `确认取消关注`（error 容器 Button）+ `保留关注`。**左对齐且两步都锚在同一位置**——第二下
  不该追着按钮跑。
- 取关走已有的 `UiAction.ToggleFollow`，页面不自己删任何东西：卡片因为 `followed` 不再包含它
  而消失，面板随后关闭。
- 面板高度 0.55 → 0.6：底下多了固定高的危险行与警告行，0.55 时 chip 区（面板本体）只剩两行。
- 说明文字加一句"取消关注会把主播从列表里移除……"，**作为独立 Text 节点**：原来那句被
  `FollowTagsTest` 逐字钉着，拼进去会让它红。

### 本轮验证

`FollowUnfollowTest` 四条，全部断言在 action 列表与面板/网格的真实状态上：

| 用例 | 断言 | 去掉改动会怎样 |
| --- | --- | --- |
| `the long-press panel offers unfollow and says what it costs` | 面板有「取消关注」与警告文案，且 `actions` 为空 | 找不到节点，红 |
| `the first tap only asks for confirmation` | 点击后 `filterIsInstance<ToggleFollow>()` 为空，且「确认取消关注」出现 | 做成一点就取关 → 第一条断言看到 1 条 → 红 |
| `the confirming tap unfollows ... exactly once` | 恰好一条 `ToggleFollow`、key 是被打开面板的那间、「标题 1」消失而「标题 2」还在 | 页面自己缓存列表靠隐藏卡片 → 红 |
| `backing out of the confirmation keeps the follow` | 「保留关注」后 0 条 action、按钮回到未确认态、`followed` 仍有该房间 | 红 |

---

## 第八十轮迭代：LIVE 角标按列表传；chip 选中色收回品牌红

### 缺陷是什么

1. **每张卡片都画 LIVE 角标**。首页/分区页/搜索结果列出的**全部**是在播房间——那正是平台
   列表的定义——于是每张卡在重复页面的前提。参考项目的首页一个都不画，只留人气。
2. **chip 选中态是青色**。M3 `FilterChip` 的选中容器取 `colorScheme.secondaryContainer`，
   而主题里那一对是青色；于是选中 chip 青、tab 指示条/LIVE 角标/关注星/底部导航红。

### 为什么会这样

- 角标是按"房间属性"写的（`isLive`），但"要不要显示"其实是**列表**的属性。
- `secondaryContainer` 被当成"调色板里的第二个色"填了青色，而它真正的消费者只有
  `FilterChip` —— 全仓 grep 过：源码里没有任何一处显式读它，底部导航的指示器是显式
  `primaryContainer`。

### 怎么修的

- `StreamerCard` / `StreamerSearchCard` 加 `showLiveBadge: Boolean = true`，首页与搜索传
  `false`，关注页不传（那是唯一混合在播/未开播的列表）。**「未开播」角标不动**：它说的是
  列表本身不携带的信息，哪个页面都该说。
- `Theme.kt` 的 `secondaryContainer` / `onSecondaryContainer` 改成 `primaryContainer` 同族
  （浅 `0xFFFFDDE3`/`0xFF41000D`，深 `0xFF8A0022`/`0xFFFFDDE3`）。`secondary` 本身不动，
  也不改任何 chip 调用点——一处主题改动，全仓 chip 同时回品牌色。

### 顺带修掉的一处验证盲区（两处，都是看图看出来的）

**① 播放页截图从来不是应用配色。** 三张设置面板套的是裸 `MaterialTheme`，`capture` 对
`Screen.Player` 更是直接 compose `PlayerContent`（`LiveHubTheme` 只在 `LiveHubRoot` 里套）。
结果是 12 张播放页产物里的「已关注」是灰紫、弹幕用户名是 Material 默认紫——"看起来有覆盖，
其实没有"。改成 `LiveHubTheme(mode = state.themeMode)` 并新增 `23d-player-dark`。

**② 深色那张第一版里房间标题几乎看不见。** `PlayerContent` 的 `Text` 不指定颜色时取
`LocalContentColor`，而它由外层 `Surface` 提供（应用里是 `LiveHubRoot` 那一层）。harness 只
套了主题没套 `Surface`，于是回落到黑色——浅色图里看不出来，深色图里就是一个"像是应用对比度
bug"的假象。补一层 `Surface(color = background)` 复制真实层级后，`23d` 才是可读的。

### 本轮验证

`CardInformationDensityTest` 追加三条：首页网格无 LIVE、关注页仍有 LIVE（证明是按场景传而不是
把功能删了）、搜索结果无 LIVE；前两条先断言卡片标题存在，免得"什么都没画"也算通过。

---

## 第八十一轮迭代：分享直播间链接

### 缺陷是什么

全仓没有任何分享路径（`ACTION_SEND` / `ClipData` 都 grep 不到）。一个聚合直播客户端，
看到好东西只能靠别人自己搜。

### 为什么会这样

自己能用之后没人回头做"给别人"。而链接其实不需要抓接口：`Platform.homePage` 早就在模型里，
房间 id 就是各平台直播页 URL 的路径末段。

### 怎么修的

- `internal fun shareUrl(room)` = `homePage + "/" + roomId`。抽成纯函数是因为 host/id 配错是
  静默错：链接看着像链接，收到的人打开是 404（抖音直播页在 `live.douyin.com`，
  `www.douyin.com/<id>` 是主站）。
- 操作行在「关注」右侧加「分享」药丸（复用 `PillButton`，顺手得到 48dp 目标与水波纹），发
  `ACTION_SEND` + `text/plain` 并套 `createChooser`：正文是「主播 正在直播：标题 + 链接」，
  subject 是标题（邮件/笔记才有标题）。`text/plain` 是因为正文是一句话加一个 URL，聊天/笔记/
  邮件都注册 text，`text/x-uri` 只到极少数。
- 缩略窗与全屏不画：控件在 `RoomActionRow` 内，而那一行本来就不在这两种形态里 compose。

### 本轮验证

`PlayerShareTest` 五条：四平台 URL 比**整串**（出错的一半是 host）、点击后
`nextStartedActivity` 是 `ACTION_CHOOSER` 且内层 `ACTION_SEND` / `text/plain` / 正文 / subject
全对、48dp、「缩略窗与全屏里没有分享」。

---

## 三条一起的验证

| 检查项 | 结果 |
| --- | --- |
| `:app:lintDebug` | ✅ 0 error（Build & test job） |
| JVM 单元测试 | ✅ **804** 个用例（第八十轮 792 → 本轮 +12：4 取关 + 3 角标 + 5 分享），0 失败 |
| 仪器测试 | ✅ **50** 张截图（新增 `23d-player-dark`），逐张看过 |
| Release assembly（R8） | ✅ |
| CI | ✅ `b38aa2d` 三个 job 全绿（四条提交一次推送） |

截图对照（以第七十八轮为基线）：**43 张变化/新增，7 张逐字节未变**。

| 分组 | 张数 | 为什么变 |
| --- | --- | --- |
| 首页（含平板/深色/迷你窗/空态/错误态） | 12 | 角标消失 + chip 选中色 |
| 搜索（四种状态 × 三个平台） | 11 | 同上（搜索行走的是 `StreamerSearchCard`） |
| 关注（含标签、面板、特别关注选择器） | 5 | chip 色 + `07e` 多了取消关注行 |
| 设置 | 2 | 主题/开关 chip 的选中色 |
| 播放页（含三张设置面板、竖屏、深色） | 13 | 分享药丸 + 首次以真实配色渲染 + 新增 `23d` |
| 未变 | 7 | 同步页三张、关注空态、设置关于/已登录、同步深色 |

### 没做到的

- **取关面板的面板高度是手算的**（0.55→0.6），320×640 上 chip 区只剩两三行；真机上大字号会
  更紧，需要一次 fontScale 的实测。
- **字号那一半没做**：`labelSmall` 10sp 偏小这件事仍在（审计清单第 4 条的另一半），它要动
  `Typography.kt` 与一批密度断言，适合单独一轮。
- **分享只做了"发出去"**：没有"复制链接"，也没有平台分享卡片（要各家 SDK，不做）。
- 三条改动仍然只在 CI 与截图里验证过，没有真机。

### 下一轮

`loop-runbook.md` 第五节现在剩：字号那一轮、播放历史/最近观看、后台刷新开关、自动同步；
媒体通知 + 前台服务仍要真机在环。播放历史与自动同步都要动 `MainViewModel`，不能再并行，
要分开排。


---

## 第八十二轮迭代：字号下限（labelSmall 11sp、bodySmall 13sp）

第二批并行三件事之一（另两件是第八十三、八十四轮）。提交：
`192f007`（82）、`0ab05d4`（83）、`4d7c131`（84），另有一条补两张截图的提交（`git log` 可见）。

### 缺陷是什么

`LiveHubTypography` 把 `labelSmall` 定成 **10sp**（M3 自己对这个 style 的下限是 11sp），
`bodySmall` 干脆没有覆盖、继承 M3 的 12sp。这两级不是装饰：卡片副信息（未开播 / 人气 /
分区角标）、同步页说明、以及**竖屏弹幕正文**都吃它们。320dp 上 10sp 中文是糊的。

### 为什么会这样

10sp 是"看起来能塞下更多卡片副信息"的选择，而 `bodySmall` 没被想起过——它一直在用默认值，
没人写下来就没人看见。参考项目的弹幕正文明显更大（约 16–17sp）。

### 怎么修的

- `labelSmall` 10sp/14sp → **11sp/16sp**：行盒跟着字号一起长。只改字号不动行盒会让 11sp 挤在
  14sp 的行里，更难看。
- 新增 `bodySmall = 13sp / 18sp`。弹幕列表读的还是 `bodySmall`，**没有在 `PlayerScreen` 里二次
  放大**——一处来源。
- `TypographyFloorTest` 三条把下限钉住（floor，不是等值：以后可以继续调大，回退才会红）：
  `labelSmall ≥ 11sp`、`bodySmall ≥ 13sp`、两者行盒 `≥ 16/18sp`。

### 顺手查清的一件事：密度测试量的不是应用的字阶

那批 320dp 密度测试（`HomeCategoryDensityTest`、`CardInformationDensityTest`、
`FollowTagsTest`、`PlayerChromeTest`、`DanmakuListOrderTest`）**没有一个装了 `LiveHubTheme`**：
多数连 `MaterialTheme` 都没包（沿用默认 `Typography()`），包了的也只是裸 M3。所以它们：

- 不会因为这次改动变红（同理，也不会因为任何字号改动变红）；
- **也保护不了这类改动**——它们量的是 M3 的 11/12sp，不是应用的 11/13sp。

这是继第八十轮"播放页截图不是应用配色"之后的第二处验证盲区，同一类：**fixture 没有复制真实
层级，产物/断言就只在测量它自己**。修法要单独一轮：把 fixture 包进 `LiveHubTheme`，然后按 CI
报出的实际 dp 重标阈值（`HomeCategoryDensityTest` 的 190f/220f/260f 首当其冲）。写进 runbook。

### 本轮验证

| 检查项 | 结果 |
| --- | --- |
| `:app:lintDebug` | ✅ 0 error |
| JVM 单元测试 | ✅ **823** 个用例（本轮 +19：字号 3 + 历史 4 + 历史行 UI 4 + 缓存 8），0 失败 |
| 仪器测试 | ✅ **52** 张截图，逐张看过 |
| Release assembly（R8） | ✅ |
| CI | ✅ `4d7c131` 三个 job 全绿（三件功能与补图在同一次推送里） |

字号变大后 45 张截图变化、5 张逐字节未变（`17b`/`17c` 全屏播放页与三张播放设置面板——它们
可见的文字都不吃这两级字号）。另外两张是本批新增（`09c`、`15c`），不是字号带来的。

---

## 第八十三轮迭代：最近看过（最近 12 个房间）

### 缺陷是什么

应用不记得任何"看过什么"。关注列表回答的是"我想长期跟谁"，"我刚才在看的那个"没有地方回答：
只能重新搜，或者回分区里翻。

### 为什么会这样

关注是唯一"有记录"的概念，而它从一开始就带着更强的语义（还要同步、还要墓碑）。"随手看一眼"
这件事没人替它建模。

### 怎么修的

- `UserSettings` 新增 `recentRooms: List<Streamer> = emptyList()`。**默认值是硬要求**：这条记录
  在旧设备里不存在，而 `decode` 读不出来的整条记录会回落成 `UserSettings()`——没有默认值等于
  让升级把这台机器的主题、关注、登录记录一起清掉。
- `addRecentRoom`：提到最前、按 `key` 去重、截断 **12**。截断是因为 `update` 每次重编码整条
  settings，而这是唯一随使用增长且无上限的字段。
- `MainViewModel.openRoom` 的**第一句**就记录：用户按下的是这间房；弱网下"解析失败"恰恰是大多
  数访问，而那些正是最想找回来的。
- 搜索页空闲态新增「最近看过」横条（120dp 卡、16:9 封面 + 主播名、整卡可点）。没有历史不画。
- 本机数据，`SyncScope` 一行未动。

### 我补的两处（原任务没做）

1. `RecentRoomsRowTest` 四条：条带出现在空闲态、点卡片发出**正确 key** 的 `OpenRoom`、
   没历史时连标题都不画、有结果时不压在结果上。
2. 截图 `09c-search-recent-rooms`。**在此之前这行 UI 零测试零截图**：`baseState()` 没有历史，
   所以它在任何产物里都不会出现——功能做了但没人看得见，正是第七十五轮的教训。

### 本轮验证

`PlayerHistoryTest` 四条：后开的在前、重开是"移动"不是新增（顺序断言）、上限 12 且挤掉最旧的、
**新建第二个 `AppContainer` + `MainViewModel` 仍在**（真的落盘了，不是内存缓存）。

---

## 第八十四轮迭代：图片缓存占用 + 清除

### 缺陷是什么

Coil 3 默认拿可用空间的 2% 做磁盘缓存，而应用既不显示它、也不提供清理入口。装机只会越用越大，
而设置页没有任何"存储"相关的东西。

### 怎么修的

- 设置页「弹幕屏蔽词」与「账号」之间新增「存储 → 图片缓存」：当前占用 + 「清除缓存」。
- 读不到时显示 **`—`** 而不是 `0 B`：`0 B` 是"读成功了且缓存是空的"，和"读失败"是两件事。
- 清理在 IO 上（要遍历磁盘目录），**清完重读再发 `ShowMessage`**——snackbar 不是乐观提示。
- `formatCacheSize` 用 `Locale.US`：默认 locale 在逗号小数点的语言里会把 `12.4 MB` 写成
  `12,4 MB`，看起来像十六倍大。
- Coil 的 5 个符号集中在两个私有 suspend 函数里（`readImageCacheBytes` / `clearImageCaches`），
  失败只 `Log.w` 并让卡片显示 `—`，绝不让设置页崩。

### 本轮验证

`CacheSettingsTest` 八条：格式化五个边界（`0 B` / `999 B` / `1.0 KB` 与 `1.5 KB` 的进位 /
`12.4 MB` / `3.0 GB`）、卡片可滚到、按钮实测 ≥48dp、点一次恰好一条 `ShowMessage`。

### 没做到的

- `heightIn(min = 48.dp)` 那行**没有被测试守住**（M3 的 `TextButton` 本来就有 48dp）：它是
  "CompositionLocal 关掉强制最小尺寸"时的下限，注释里已说明，不算被断言保护。
- 缓存读数在 Robolectric 下不参与断言（取决于测试环境），只有格式化函数与"点了会发消息"被测。
- 卡片没有"自动刷新"：占用只在进入设置页与清除后各读一次。

---

## 第二批一起的验证

| 检查项 | 结果 |
| --- | --- |
| `:app:lintDebug` | ✅ 0 error（Build & test job） |
| JVM 单元测试 | ✅ **823** 个用例（+19），0 失败 |
| 仪器测试 | ✅ **52** 张截图（新增 `09c-search-recent-rooms`、`15c-settings-cache`），逐张看过 |
| Release assembly（R8） | ✅ |
| CI | ✅ `4d7c131` 三个 job 全绿（三件功能与补图在同一次推送里） |

新增的两张图都是为了补"功能做了但没人看得见"：`09c` 是历史行（`baseState()` 没有历史，
不补就永远不出现），`15c` 是缓存卡（`15-settings` 停在屏蔽词卡里、`15b` 只拍到这卡的最后一行）。

### 下一轮

- **密度测试进主题**：把 320dp 那批 fixture 包进 `LiveHubTheme`，按 CI 报出的实测值重标阈值。
  这是"断言在量它自己"的第二处，值得单独一轮。
- 后台刷新开关（固定 30 分钟、无开关、无 Wi-Fi 限制）与自动同步：都要动 `MainViewModel`，
  顺序做，不能并行。
- 媒体通知 + 前台服务仍然要真机在环。


---

## 第八十五轮迭代：密度/位置断言改在应用主题下量

第三批并行两件事之一（另一件是第八十六轮）。提交：`2dd65d7`（85）、`043563f`（86）、
`57d4c73`（修 CI 首红）。

### 缺陷是什么

第八十二轮查出来的盲区：那批 320dp"密度/位置"测试**没有一个装了 `LiveHubTheme`**——9 个里
7 个连 `MaterialTheme` 都没包（沿用 Compose 默认 `Typography()`），2 个包的是裸 M3。于是它们
量的是 M3 的 `bodySmall` 12sp，而应用是 13sp：阈值描述的是 fixture，不是产品。字号改动既不会
让它们红，它们也拦不住字号改动。

### 怎么修的

- 9 个文件统一成 `setContent { LiveHubTheme { Box(size(...)) { Screen(...) } } }`（与
  `LiveHubRoot` 同形）：`HomeCategoryDensityTest`（两个 setContent 入口都套）、
  `CardInformationDensityTest`、`FollowTagsTest`、`PlayerChromeTest`、`DanmakuListOrderTest`、
  `LongListReachabilityTest`、`SyncDialogSpaceTest`、`FollowFilterReachabilityTest`、
  `AggregateCategoryChipTest`。
- 每个文件写一句"为什么进主题"的注释（位置只有在该字阶下才有意义）。
- **阈值一条没动、断言一条没删**（逐文件计数前后一致）。理由有几何依据：这两套字阶在
  `labelLarge`(14/20)、`titleMedium`(16/24)、`labelMedium`、`labelSmall`、`bodyMedium` 上
  逐字段相同，只有 `bodySmall`（12/16 → 13/18）会推进布局，而它只出现在
  `SyncDialogSpaceTest` 的对话框注释与 `DanmakuListOrderTest` 的弹幕行里。

### 本轮验证

CI 一次通过（这两处都没有被 13sp 顶破）：

| 检查项 | 结果 |
| --- | --- |
| `:app:lintDebug` | ✅ 0 error |
| JVM 单元测试 | ✅ 836 个用例（+13，全部来自第八十六轮），0 失败 |
| 仪器测试 | ✅ 52 张截图 |
| Release assembly（R8） | ✅ |

### 留下的

- 同类盲区还剩三处（都是新加的测试，只断言存在性/顺序，不量位置）：
  `RecentRoomsRowTest`、`CacheSettingsTest`、`BackgroundRefreshTest` 仍用裸 `MaterialTheme`。
  它们不受字阶影响，收进主题是"统一"而不是"修缺陷"，留到下一次碰它们时顺手做。
- 深色的 `themeMode` 路径仍没有 JVM 覆盖（这 9 个都用默认参数）。

---

## 第八十六轮迭代：后台刷新开关 + 仅 Wi-Fi

### 缺陷是什么

`FollowRefreshWorker` 固定每 30 分钟把每个关注房间的状态拉一遍：**没有开关、没有 Wi-Fi 限制**。
100 个关注 = 每 30 分钟 100 个请求；移动数据下照跑；用户既不知道、也关不掉。设置页没有任何
后台相关的东西。

### 怎么修的

- `UserSettings` 加 `backgroundRefresh = true` / `refreshOnWifiOnly = false`。两个默认值都是
  硬要求——旧记录缺字段时 `decode` 会整条回落成 `UserSettings()`，默认写错等于让升级重置设置。
- 判据抽成 `shouldRefresh(backgroundRefresh, wifiOnly, onWifi: Boolean?)`：`onWifi = null`
  （无活动网络 / VPN 隧道 / 查询抛异常）**放行**。刷新本来就在所有网络上跑过，把"判断不出来"
  读成"不是 Wi-Fi"会静默关掉这些用户的功能——失败要开不要闭。
- `doWork()` 里两种拒绝都返回 **`Result.success()`**：用户关掉、或用户要 Wi-Fi 但在移动数据，
  等下去不会变成相反答案；`retry` 只会按 backoff 反复重问还费电，而且 WorkManager 把 retry
  记成失败，一次"故意的跳过"会显示成坏掉的任务。
- 设置页新卡「后台刷新」（在「存储」之前）：两个 Switch，第一行关掉时第二行 **disabled 而不是
  隐藏**（选择被记住，重新打开就生效）。副标题里的 "每 30 分钟" 直接读
  `FollowRefreshWorker.INTERVAL_MINUTES`，避免说的和排的不一样。
- 关掉时同时 `cancel` 已入队的任务：只写偏好会让开关看着是关的、任务却还在跑。
- `LiveHubApplication` 只在开启时 `schedule`：否则用户关掉后重启，任务又回来了——开关会以
  唯一要紧的方式失效（电池与唤醒）。失败一律 `runCatching` + 默认开，读不到设置不该拦住启动。

### 两处新约定（值得沿用）

1. **`testTag` 首次进入产品代码**（`BACKGROUND_REFRESH_SWITCH_TAG` / `WIFI_ONLY_SWITCH_TAG`）：
   标签文字与 Switch 是两个不同的语义节点，`onNodeWithText` 打不到控件，而"关掉第一行时第二行
   是否 disabled"只能问控件本身。常量 `internal`，测试复用同一个而不是复制字符串。
2. **`UserPreferences.appContext`（internal）**：`AppContainer` 的 `applicationContext` 是
   private 且无 accessor，而开关要传 context 给 `WorkManager`。否决了
   `LiveHubApplication.instance` 那种进程级静态——Robolectric 里每个测试都会继承上一个测试的
   Application。

### 本轮验证

`BackgroundRefreshTest` 13 条：`shouldRefresh` 真值表（含 null 放行）、旧记录解码后两个新字段
取默认且 `themeMode` 没被顺带重置、设置页两个 Switch 与 disabled / enabled 两态、
「后台刷新卡在存储卡之前」、ViewModel 写偏好并**新建 `AppContainer` 后仍为 false**（真的落盘）。

### CI 首红：错在测试，不在代码

第一次推送 `Build & test` 红在：

```
both background refresh switches are on the settings page
The component ... contains '每 30 分钟检查一次关注列表里谁开播了' is not displayed!
```

`performScrollToNode(标题)` 一看到标题就不再滚，副标题还在折叠线下；`assertIsDisplayed` 对
"被折叠线裁掉"的节点就是失败。改法是**滚到副标题本身再断言**（与标题同一行的 Switch 改成在
标题可见时先断言）。这条与第七十五/七十六轮那条教训同类：**断言什么就先滚到什么**。
`57d4c73` 全绿。

### 没做到的（agent 主动列出的三处未测）

- `doWork()` 里那道 gate、`schedule/cancel` 的调用、`LiveHubApplication` 的启动开关都没有
  端到端测试：`WorkerParameters` 是 `@RestrictTo`（用它会撞 lint `RestrictedApi`），而
  `work-testing`（`WorkManagerTestInitHelper`）不在依赖里。可测的接缝（`shouldRefresh`）已全部
  钉住，`doWork` 里只剩"查网络 + 一行 if + return"。
- `ConnectivityManager` 的查询依赖平台，只在真机上才有真实取值。
- 后台刷新的 **30 分钟真机上是否按预期跑**，仍然要真机验证。

### 下一轮

- **自动同步**（必须两台设备都停在同步页）是待办清单最后一条功能项；它和后台刷新一样要动
  `MainViewModel`，所以只能单独一轮。
- 媒体通知 + 前台服务仍然要真机在环。
- 顺带记一条测量口径的问题：本轮三张关注页截图（`07b`/`07c`/`07d`）比上一轮多了 1–10 字节，
  内容看不出差别——**这几张渲染不是逐字节稳定的**，以后拿"逐字节未变"当证据时要避开它们。


---

## 第八十七轮迭代：自动同步（最后一条功能项）

第四批并行两件事之一（另一件是第八十八轮）。提交：`f6e9c01`（87）、`3d283c3`（88）。

### 缺陷是什么

两台设备**必须同时停在「同步」页**才可能同步：`SyncController` 的局域网服务端只在那一页开着时
bind 端口（`navigate()` 里 start、离开时 stop）。于是"再加一台设备"这件事要么两边约好一起开，
要么就永远同步不上——第四十轮修完"同步很慢"之后，剩下的就是这个使用前提。

### 为什么会这样

服务端生命周期当初是按"电池与攻击面"这条理由定的（那条理由本身没错），但实现上等于把
**什么时候该跑**写死在了**页面导航**里。没有地方表达"应用在前台、有已配对的设备、那就该跑"。

### 怎么修的

- **生命周期**：服务端现在在「应用前台 + 至少一个已配对设备 + 自动同步开着」时运行
  （`autoSyncWanted()`）。`MainActivity.onStart/onStop` 挂到
  `viewModel.onAppForeground()/onAppBackground()`。停服务放在 PiP 早退**之前**：缩略图是有人
  在看的画面，而"没人在看的端口"没有同样的辩护理由。
- **离页不再是停服务的同义词**：`stopSyncServer()`（关服务 + 收回服务拥有的一切）与
  `leaveSyncScreen()`（收回**页面**拥有的东西：配对码 + 发现）拆成两件事，服务端生死交给策略。
  配对码仍然只能在同步页签发（`PairingWindow` 的语义不变），`SyncController` 因此只多了
  `cancelPairing()`——凭据该由展示它的页面收回，而不是由服务端的生死顺带决定。
- **安静的一次交换**：`onAppForeground()` 里若 `shouldAutoSync` 允许就跑一次 `syncNow(silent)`。
  `silent` 只影响 `message`（弹 snackbar 的那条通道）：`syncing` 标志、设备列表、交换日志都一样，
  因为本来就是同一次交换。用户没点任何东西，不该有 toast。
- **节流**：`shouldAutoSync(enabled, peerCount, nowMillis, lastAutoSyncAtMillis)` 纯函数，
  5 分钟。时钟倒退按"刚刚同步过"处理（fail-safe：漏一次只是手点一下，信坏时间戳可能变成循环）。
  时间戳只放内存：这是节流不是记录，重启后多跑一次无害。
- **开关**：`UserSettings.autoSync = true` + `UiState.autoSyncEnabled` + `UiAction.SetAutoSync`，
  同步页新卡「自动同步」+ 副标题。**改了行为就改说法**：`SyncController`、`navigate()`、测试类
  三处 KDoc 里"只在同步页跑"的旧结论全部改写。
- **配对前的攻击面为零**：没有已配对设备时前台也不开端口；配对本身仍然是同步页显式 start。

### 本轮验证

`AutoSyncTest` 新增 12 条：`shouldAutoSync` 真值表 5 条（关掉 / 没设备 / 4:59 内 /
本进程首次 / 正好 5:00）、端口开合 3 条（前台开后台关 / 没配对不开 / 开关起落）、
页面交互 2 条（同步页在开关关掉时仍然监听——配对需要它；回到前台时重新签发配对码）、
**安静那次真的跑了一次交换**（有对端地址但连不上，等 `syncing` 落回 false 后断言
`message == null` 且 `syncLog` 非空——两条一起才有意义）、端到端节流 1 条（证明时间戳真被打了，
纯函数测不到这半）。

`SyncServerLifecycleTest` 重写：`leaving the sync screen stops the server` 拆成"有配对→不停 /
无配对→停"，并在类注释里写明**这是本轮有意反转的旧结论**；另加"离页仍然作废配对码"。

### 没做到的

- **UI 层没有测试**：新卡没有 compose 测试，也没加 testTag。它落在同步页折叠线附近，要
  `performScrollToNode` 才看全——这也是它放在 `ScopeCard` **之后**的原因：`SyncScopesTest`
  断言 `ScopeCard` 必须被组合，而 `LazyColumn` 不组合屏外 item。代价是要滑一下才看得到。
- **没有真对端**：整条链路测到"跑了一次交换、失败在连不上"，没测成功合并（要第二台设备）。
  两台真机同时在前台的收敛只能人工验。
- **`onAppForeground` 里 `start()` 仍在主线程 bind `ServerSocket`**（同步页路径本来也这样，
  只是现在更频繁）。要干净得把它挪到 IO，但那会改变 `enterSyncScreen` 的同步语义
  （`isRunning` 立刻为真被测试依赖），超出本轮范围。
- 两个我（agent）自己定的行为都可撤回：① 回到前台且停在同步页时**重新签发**配对码（因为
  退到后台已经撤了码，不重签页面会显示"点刷新匹配码"）；副作用是切出去再回来会换码，
  正好在换码瞬间在另一台设备输码会失败。② 把开关打开**不会**立刻同步一次，只有下次进前台
  或点「立即同步」。

- **新卡没有专门的截图，两次尝试都白拍了**：scrollToText = "自动同步" 得到的图与 13-sync
  **逐字节相同**（那个标题本来就在折叠线上，performScrollToNode 认为它已可见、没有滚动）；
  换成下方的「我的设备」又得到与 13b **逐字节相同**的图（ScrollToIndex 把目标顶到视口顶端，
  页尾一点就夹到最大滚动量）。两次的产物都是重复帧，撤掉了。结论：这张卡在 14-sync-empty
  （无配对设备、页面更短）里本就是完整的，够用作证据；真要单拍需要 harness 支持"滚到某处而不是
  滚到某个节点"。### 测试成本

每条"有配对设备的前台"用例都会真跑一次交换、失败在 `PeerLocator` 的 3 秒预算上，
`AutoSyncTest` 5 条 + `SyncServerLifecycleTest` 1 条 ≈ CI 多 18 秒。两个类的 `@After` 会同步
清空**配对列表**并 `awaitWrites()`——`UserSettings` 的重置清不掉 `keyPeers`，不清就会让
`MainViewModelTest` 里"peers 为空"的断言变成顺序相关的 flaky。

---

## 第八十八轮迭代：最后三个渲染测试收进应用主题

第八十五轮收了 9 个，剩下三个是后来新增的：`RecentRoomsRowTest`（83）、`CacheSettingsTest`
（84）、`BackgroundRefreshTest`（86）。它们只断言存在性与顺序，不受字阶影响——所以这**不是
修缺陷，是把口径统一**：让"每个渲染测试都在应用主题下跑"成为全仓一致的规则，以后新加的位置
断言自然量在真实字阶上。

断言数前后一致（7 / 12 / 29），阈值与文案一字未动。

---

## 第四批一起的验证

| 检查项 | 结果 |
| --- | --- |
| `:app:lintDebug` | ✅ 0 error（Build & test job） |
| JVM 单元测试 | ✅ **849** 个用例（+13：自动同步 12 + 生命周期反转 1），0 失败 |
| 仪器测试 | ✅ **52** 张截图（无新增；自动同步卡在 `14-sync-empty` 里完整可见），逐张看过 |
| Release assembly（R8） | ✅ |
| CI | ✅ `3d283c3` 三个 job 全绿（87+88 一次推送） |

截图：7 张变化（`13-sync`、`13b-sync-log`、`14-sync-empty`、`23c-sync-dark` 是因为新卡；
`07b`/`07c`/`07d` 是第八十六轮记过的**逐字节不稳定**那三张，与本轮无关）。

### 剩下的

待办清单里**功能项已经清空**。剩下三类：

1. **真机验证**（最大的一块）：自动同步要两台设备同时前台、后台刷新的 30 分钟节奏、PiP 三条
   路径、小窗切换、音频焦点、四局域网配对、四平台弹幕、平板列数。
2. **媒体通知 + 前台服务**（要真机在环；开播提醒按用户要求搁置）。
3. 两处可选的小尾巴：同步页那张新卡没有 UI 测试（见第八十七轮"没做到的"）；
   `onAppForeground` 的 `start()` 还在主线程。


---

## 第八十九轮迭代：同步收尾（bind 出主线程 + 自动同步卡的 UI 测试）

### 缺陷是什么

第八十七轮留下的两处：`SyncController.start()` 在**主线程**同步 bind `ServerSocket`，而它现在
每次回前台都会调；同步页那张新的「自动同步」卡零测试。

### 怎么修的

- **`startAsync()`**：bind 在 `Dispatchers.IO`，**地址在同一次跳里读回**（端口可能是临时端口，
  出了那一跳就过时）。`startMutex` 串行化 bind；`stopGeneration` 在跳**之前**捕获、在发布前的
  临界区校验——"前台 start 紧接着退后台"不会复活一个没人持有的 listener，而是把新 socket 关掉。
- **`MainViewModel`**：单飞 job + `awaitSyncServer()` 表达"什么时候可以读地址"；
  `UiState.localAddress` 成为唯一可信来源；`autoSyncIfDue` 先等服务器起来再交换（否则自动那次会以
  "同步服务未启动"空跑，而它没有 snackbar 可以解释）。
- **同步版 `start()` 保留**：instrumented 的 `SyncOffMainThreadTest` 依赖"调完就真"的语义，注释
  写明它为什么还在，免得下一个人以为是漏改。
- **卡片 UI 测试**（`SyncAutoCardTest` 3 条）：加 `internal` testTag，断言卡片与副标题够得着且
  bounds 在 320×640 内、Switch 跟随 `autoSyncEnabled`、点一下发出 `SetAutoSync(false)`（反向发 true）。

`AutoSyncTest` 里 5 处"调完立刻读 `isRunning`"改成 `awaitCondition`，**断言一条没删**。

### 没做到的

- **"bind 真的不在主线程"没有测试**。Robolectric 不开 StrictMode，而"调用后立刻
  `isRunning == false`"本身是竞态的。正解是在 instrumented 的 `SyncOffMainThreadTest` 里加一条
  （在 `runOnMainSync` 里调 `onAppForeground()`，断言不抛 `NetworkOnMainThreadException`），
  但那个文件不在本轮写作用域里——留给下一轮或真机。
- `UiState.localAddress` 的时序没有断言（需要容忍"没有局域网接口"这一情形，CI 上不稳）。

---

## 第九十轮迭代：大字号可见性（harness 加 fontScale）

审计里一直挂着"fontScale 从没验证过"，而第八十二轮刚把字阶调大。**JVM 测试量不了这件事**
（Robolectric 字宽是桩：两字标签的内在宽度 2.5px），真实字体只存在于仪器测试，所以入口是截图。

- `capture()` 新增 `fontScale: Float = 1f`，用
  `CompositionLocalProvider(LocalDensity provides Density(density, fontScale))` **局部**覆盖——
  不改模拟器全局显示设置（第三十五轮：改了它会让 38 张竖屏渲染全变）。
- **1f 时不提供任何 local**，所以既有 52 张图逐字节不变——这一条当轮就验证了：r90 与 r88 相比，
  52 张里只有 3 张变，而它们是第八十六轮记过的"逐字节不稳定"那三张（差 1–10 字节）。
- 四张 1.5×：`24-home`、`25-search`、`26-follow`、`27-settings`。1.5 是系统辅助功能里 stock UI
  的 large 档。

### 当轮就抓到两个缺陷

1. **`25-search-large-font` 直接让 instrumented job 红了**：
   `IllegalArgumentException: performMeasureAndLayout called during measure layout`。根因是第七十八
   轮写的平台行自动滚动——`snapshotFlow{layoutInfo}` 会在**产出快照的那次布局里**恢复，
   `scrollToItem` 于是请求"在测量中再测量"。**为什么现在才炸**：1.0× 下被选中的 chip 大多已在屏内，
   滚动是空操作；1.5× 下第五个 chip 才真的在屏外。也就是说**真机大字用户点开搜索页会崩**。
2. **`24-home-large-font` 里四个平台 tab 每个只剩一个字**（斗 / 虎 / 抖 / B）——第七十八轮同一个
   截断在放大字号后重现（那次是四个字的「哔哩哔哩」，这次是两个字的短名）。

---

## 第九十一轮迭代：修大字号下的两处（崩溃 + 截断）

### 崩溃：自动滚动不能在布局里请求测量

改成 `withFrameNanos { }` 之后再读 `layoutInfo`——帧回调跑在组合与测量之前，之后的
`scrollToItem` 是合法的测量请求。注释里写了这条因果，免得以后有人"优化"掉那一帧。
修完 `25-search-large-font` 通过，同时 `the restored platform is scrolled into view`（第七十八轮
那条 JVM 用例）仍然绿——滚动照旧发生，只是晚一帧。

### 截断：tab 标签用 `autoSize`

两个汉字在 24sp（1.5× 下的 `titleMedium`）要 48dp，而每个 tab 的内容宽度只有约 34dp。
改成 `autoSize = TextAutoSize.StepBased(minFontSize = 11.sp, maxFontSize = 标题字阶)`：**放不下就缩，
缩到放得下为止**，下限是应用里最小的一级字号。取舍写在代码里：导航标签切掉一半比小一号难用得多。
修完 `24-home-large-font` 四个标签完整可读。

### 再看两处静态排查里最可疑的

- **`28-player-large-font`（操作行 关注/分享/弹幕）**：静态排查算出"距溢出约 24dp"，**实测没溢出**。
  标题按预期折两行、弹幕行正常。这条预测是错的，记下来。
- **`29-follow-special-large-font`（特别关注头像行）**：名字被省略号截成「一条…」「旭旭…」。
  这是全仓唯一一处"固定槽位里的名字"（头像 56dp、整行 86dp，1.5× 下文字行 24dp 正好顶格）。
  **接受**，理由：这一行是快捷入口，头像才是标识，名字只是辅助；要看全名可以点首页的「添加」
  打开选择面板（那里是全宽行）。若哪天要改，办法是给名字两行而不是加宽槽位。

### 本轮验证

| 检查项 | 结果 |
| --- | --- |
| `:app:lintDebug` | ✅ 0 error |
| JVM 单元测试 | ✅ **852** 个用例（+3，全部来自第八十九轮的卡片 UI 测试），0 失败 |
| 仪器测试 | ✅ **58** 张截图（52 → +4 张 1.5× → +2 张），逐张看过 |
| Release assembly（R8） | ✅ |
| CI | ✅ `d5987ac` 三个 job 全绿 |

### 留下的

- **1.0× 的 52 张图里，只有那三张"逐字节不稳定"的在变**，其余 49 张在 harness 改造后逐字节不变——
  `capture()` 的改动是安全的。
- 大字号只覆盖了 6 个屏幕（4 + 玩家 + 特别关注行），**弹幕列表、标签编辑面板、同步页、搜索空态**
  还没在 1.5× 下拍过；`fontScale` 参数已经就位，以后加一行即可。
- 2.0×（系统最大档）没拍。静态排查认为 `PrimaryTabRow` 的 48dp 与底部导航的 80dp 会在 2.0×
  顶满，但没有图。


---

## 第九十二轮迭代：同步加固（主线程调用 + 地址一致性）

第八十九轮把 bind 挪到 IO，并自己点名"线程归属没有测试"。这一轮补那一层，以及一条地址时序断言。

- **instrumented**（`SyncOffMainThreadTest`）：先 `stop()`（承重的一步——`setUp` 已经起了一个服务端，
  不先停的话 `startAsync` 会 early-return 返回旧地址，什么都证明不了），再从主线程调
  `startAsync()`，断言没抛异常、`isRunning` 为真、返回地址非空。
  **边界写进 KDoc**：Android 的主线程网络守卫覆盖 `connect`/`accept`，**未必覆盖 `ServerSocket.bind`**，
  所以这条绿了只证明"从主线程调用它、它成功了"；它**不是**线程归属的回归网（把 `withContext` 删掉
  这条照样绿）。线程归属仍只由结构保证。
- **JVM**（`AutoSyncTest`）：有配对设备 + 前台之后，先等 `isRunning`，再等
  `state.localAddress == controller.serverAddress.orEmpty()`。第二个等待不是放松断言：`startAsync`
  在 IO 跳里发布服务端、ViewModel 在**续体**里才写 state，中间隔着一次 dispatch；末尾仍是严格相等。

---

## 第九十三轮迭代：大字号续拍（首张 2.0×）

补四张：`30-search-idle-large-font`（带「最近看过」）、`31-sync-large-font`、
`32-follow-tag-editor-large-font`（用 `longClickText` 打开面板）、`33-home-huge-font`（**2.0×**）。

四张里三张都有东西：

1. **`31-sync`（1.5×）：同步动作行塌了** ——「关联设备」标签竖排成一字一行、刷新图标整个消失。
2. **`32-follow-tag-editor`（1.5×）：面板塌了** —— 固定 0.6 屏高的面板，头部两段说明在 1.5× 下
   变成 6 行，把 chip 列表压到 0 高度、把「取消关注」挤出面板——第七十九轮那个取关入口在大字号
   下不可达。
3. **`33-home-huge-font`（2.0×）：平台 tab 又只剩一个字**（斗/虎/抖/B）。第九十一轮的
   `autoSize` 下限是 11sp，2.0× 下就是 22dp 一个字，两个仍要 44dp 而 tab 只有约 36dp。
   **本轮接受**：四个字缩写成一个字仍能认出是哪个平台，且这是系统滑杆顶格。真要修得让 tab 行
   可滚（改设计），记在待办里。
4. `30-search-idle`（1.5×）没问题；卡片名字 7 个字以内不截断（`douyinRooms` 前四间最多 5 个字）。

---

## 第九十四轮迭代：同步动作行改 FlowRow

截图里那个塌掉的行追下去，发现**不只是大字号**：裸 `Row` + 三个定宽子项，按 Material token 约需
332dp，而 320dp 的渲染宽度减掉 `LazyColumn` 边距只剩 288dp —— **1.0× 就已经超 44dp**。
复核 `13-sync`：刷新图标从头到尾**不在屏上**，只是没人注意到（那个按钮一直点不到）。

改成 `FlowRow`：放不下就换行，每个动作保住自己的宽度与 48dp 目标。注释否决了两个替代方案——
横向滚动（动作被推到屏外 = 找不到）、缩字（正是"用户调大字号"的反面）。
新增两条 compose 测试（1.0× 与 1.5× 各一）：按钮在屏、标签用未合并树取文本节点比高度（竖排时
是四行高）、刷新按钮 ≥ **40dp**（`IconButton` 的 state layer；被压扁时正好是它变成 4dp）且点击
发出 `RefreshPeers`。

---

## 第九十五轮迭代：标签面板三段式

改成"固定标题行 / 可滚中段 / 固定底部"：中段装 chips 与两段说明，底部装「新标签」输入行与
「取消关注」行——**能滚的是说明与标签，不能滚的是输入与取关**（需要滚才能发现的危险按钮，比
需要滚才能翻的标签列表更糟）。chips 从 `LazyColumn` 换成可滚 `Column` 里的 `FlowRow`（可滚容器
里给不定高子项会抛）；面板 0.6f → 0.7f，注释给的是算式而不是感觉。

重拍之后发现**chips 仍不在首屏**（两段说明占满了中段），于是把 chips 挪到说明**之前**：面板存在
的理由是标签，说明是参考资料，该排在可选阅读的位置。1.0× 下两者都还在首屏，只是顺序变了。

四条取关测试一个字没改（都落在固定段）；另加一条「chips 在面板首屏」的断言——它在改动前也是
绿的，钉的是"以后别再加第三段说明把它顶下去"，真正的证据是重拍的那张图。

---

## 这一批的验证

| 检查项 | 结果 |
| --- | --- |
| `:app:lintDebug` | ✅ 0 error |
| JVM 单元测试 | ✅ **856** 个用例（+4：卡片行 2 + 面板 chips 1 + 地址一致性 1），0 失败 |
| 仪器测试 | ✅ **62** 张截图（+4 张：30/31/32/33），逐张看过 |
| Release assembly（R8） | ✅ |
| CI | ✅ `9ca5d65` 三个 job 全绿 |

### 值得单独记的三件事

1. **大字号这条路线的产出比预期高**：三个真缺陷（搜索页崩溃、同步动作行在 1.0× 下就点不到刷新、
   标签面板在大字号下丢掉取关入口），全部是"读代码看不出来、只有渲染才知道"的类型。
2. **1.0× 也可能藏着缺陷**：同步动作行的溢出在 1.0× 就存在（44dp），一直没人发现，因为那行看起来
   "就是两个按钮"。这是"用 1.5× 拍图"倒过来修好了 1.0×。
3. **`autoSize` 有下限，2.0× 顶不住**：首页 tab 在 2.0× 仍会被切成一个字。接受（仍可辨认），
   真正的修法是让 tab 行可滚——那是设计改动，记在待办。

### 待办（这一批之后）

- 2.0× 下首页 tab 切成一个字：接受还是改成可滚 tab 行，未定。
- `SyncScreen` 的 2.0× 没拍；`FollowScreen` 面板确认态那一行（`确认取消关注` + `保留关注`）在
  2.0× 会溢出（agent 的算术，没拍图）。
- 真机清单不变（自动同步两台设备、后台刷新节奏、PiP、小窗、音频焦点、局域网配对、四平台弹幕）。


---

## 第九十六～九十七轮迭代：视觉一轮（卡片、区块标题、同步页层级）

用户提的是"页面要简洁好看一点、要有设计感一点"。这一轮挑的是三处**结构**上的问题，不是调色：

### 缺陷是什么

1. **卡片不是卡片。** 设置与同步两页的 `Card` 用的是 Material 的 filled Card，而它会去读
   `surfaceContainerHighest` —— 我们的 `ColorScheme` **从来没定义过这个 token**，于是回落到 M3
   基线 `#E6E0E9`：一个不属于任何品牌色的紫灰，并且与页面背景只差一档。结果是一列卡片看起来
   像"一张平铺的纸 + 浮在上面的字"。
2. **区块标题比它引出的卡片标题还小。** 设置页的「外观 / 弹幕 / 弹幕屏蔽词」是 `titleSmall`，
   而卡片里的「主题 / 显示弹幕」是 `titleMedium`：一个页面的目录比条目更轻，读起来像目录是条目
   的脚注。
3. **同步页三个按钮同权，还摆不平。** 立即同步（唯一会改数据的动作）、关联设备（设置）、刷新
   （杂务）排成一行；上一轮改成 `FlowRow` 之后不再挤坏，但留下了"48dp 图标单独一行"的孤儿。

### 怎么修的

- **两个容器 token 定成卡片底色**：`surfaceContainerLow`（`ElevatedCard` / `ModalBottomSheet`）与
  `surfaceContainerHighest`（filled `Card`）在浅色下都是纯白、深色下都沿 `surface` 抬一档。
  页面是冷白、卡片是白，卡片于是"坐在"页面上——和首页的房间卡片（`surface` over `background`）
  是同一套关系。**一处主题改动，全仓 Card 一起生效**。
- **抽出共用 `SectionHeader`**：12sp、`letterSpacing 0.8sp`、次要色、上 12 下 8。它故意比卡片标题
  更轻——眼睛先落在卡片标题上，只用 eyebrow 划组。设置与同步都改用它（这两页原本各写了一份，
  一个 `titleSmall` 一个 `titleMedium`，同样的活两种样子）。
- **同步页改成主次分明**：立即同步占满宽度（唯一的主操作），关联设备 `weight(1f)` 与刷新图标
  共享下一行。行内不再有"能被压到 0"的定宽子项，1.5× 下仍然是两行。

### 顺带修的一处连带

改主按钮为整宽之后，`SyncDialogSpaceTest` 的横屏用例红了：在 640×360 的窗口里，那个按钮被推到
折叠线下——节点被裁，`performClick` 注入的坐标落在窗口外，于是对话框没打开，失败显示成"找不到
dialog"而不是"点错了地方"。改成先 `performScrollToNode` 再点（这个文件量的是对话框几何，不是
按钮位置）。

### 本轮验证

| 检查项 | 结果 |
| --- | --- |
| `:app:lintDebug` | ✅ 0 error |
| JVM 单元测试 | ✅ **856** 个用例，0 失败 |
| 仪器测试 | ✅ **62** 张截图；本轮改动只影响设置/同步两页共 11 张（含深色两张），首页/搜索/关注/播放逐字节不变 |
| Release assembly（R8） | ✅ |
| CI | ✅ `21a5f88` 三个 job 全绿 |

### 没做的 / 下一步（需要拍板的设计题）

- **首页头部还是两行一模一样的 chip**（分组「全部/网游/手游/娱乐」+ 分区「全部直播/英雄联盟/…」）。
  两行长得一样，看不出层级。方向是：分组改成"文字 + 下划线"的 tab（无填充底色），分区保留 chip。
  代价是分组行要从 32dp 变成 48dp（触控目标下限），头部高 +16dp——这正是第二十二轮花力气压下去
  的东西，所以需要先定"层级优先还是密度优先"。密度测试的三条阈值（190/220/260）有约 30dp 余量，
  大概率仍绿，但这是要用 CI 的实测值确认的。
- 卡片没有阴影（纯白平铺）。加 1dp 阴影会更"立"，但也会让列表看起来更碎；这一轮选择不加。
- 首页卡片的封面仍然有 LIVE/人气/分区三个角标（第八十轮去掉了 LIVE 的重复显示，人气与分区还在）。


---

## 第九十八轮迭代：下滑收起分区、上滑显示（附一次性能事故）

用户要求"页面简洁一点，下滑隐藏分区、上滑显示，其他也优化一下"。

### 做了什么

- **首页头部随滚动折叠**：只折分类树（分组行 + 分区行 + 第三级），**保留平台 tab**——切平台是顶层
  导航，不该跟着滚走。分包用 `AnimatedVisibility`（expand/shrink + fade，160ms）。
- **首页卡片不再画分区角标**：那是用户刚点过的那个 chip，一张截图里同样的信息出现两次。封面只剩
  人气与星标。关注页保留（那页没有分区 chip），搜索页本来就不画。新增 `showCategoryBadge` 参数。
- 截图 `01d-home-header-collapsed`：滚到第三行再拍，证明折叠后的样子。

### 事故：第一版实现让 instrumented 从 4 分钟变成 18 分钟

第一版用 `snapshotFlow { gridState.firstVisibleItemIndex to firstVisibleItemScrollOffset }` 判断
滚动方向。**这个量正是折叠自己会改变的东西**——收起头部让网格变高，高度变化又动到这两个数字，
判断依据和副作用互为因果。表现不是断言失败，而是整个仪器测试套件慢 4–5 倍，最后被时限砍掉。

**排查过程值得记下**：

1. 我先看到日志里一串 `Failed to find ColorBuffer`，又拉了一轮**绿的**日志对比（里面也有 16 条），
   断定"这是模拟器噪音、模拟器崩了"。**这个结论是错的。**
2. 真正定案的是**对照实验**：把上一个已知快的提交 `f2de3c3` 拉成一条分支，
   `workflow_dispatch` 在同一时间窗跑同一套 instrumented。结果：

   | 提交 | 折叠实现 | instrumented |
   | --- | --- | --- |
   | `f2de3c3` | 无此功能 | **4.0 min ✅** |
   | `75a403b` | `snapshotFlow` 读滚动位置 | **18.2 min ❌** |
   | `609fc78` | `NestedScrollConnection` 读手势增量 | **7.5 min ✅** |

3. 修法：`NestedScrollConnection.onPreScroll` 拿 `available.y`——一次拖动只会调用几次，而且它是
   **输入**，不是布局的结果，无法反向喂养自己。纯函数换成
   `categoriesVisibleAfterDrag(visible, deltaY, scrolledAway)`，6px 阈值低于任何机型的 touch slop。

### 这一轮学到的（已写进 runbook）

- **不要在"读布局"的地方写回布局**：任何"根据 X 决定要不要改变 X 的成因"的写法，在 Compose 里
  都是一个反馈环。手势增量、点击事件这类外部输入才是安全的判断依据。
- **归因于基础设施之前先做对照实验**：同一时刻、同一配置、只差一个变量的两次运行，比任何日志
  推理都硬。这次我差点把锅甩给 runner。
- 连带地，CI 也补了两道时限（见第九十七轮的 CI 改动后一轮）：instrumented job 25 分钟、套件本身
  900 秒封顶，超时会明确打印"这大概率是 runner flake"，而不是无声地挂一小时。

### 本轮验证

| 检查项 | 结果 |
| --- | --- |
| `:app:lintDebug` | ✅ 0 error |
| JVM 单元测试 | ✅ **864** 个用例（新增折叠决策表 5 条、卡片分区 2 条，其余为前几轮累积），0 失败 |
| 仪器测试 | ✅ **63** 张截图（+`01d`），instrumented 7.5 分钟 |
| Release assembly（R8） | ✅ |
| CI | ✅ `609fc78` 三个 job 全绿 |

### 留下的

- `01-home` 与改动前**逐字节相同**：截图 fixture 的房间没有 `categoryName`，所以"首页不画分区角标"
  在这张图上看不出来（`CardInformationDensityTest` 用带分区的 fixture 钉住了它）。
- 折叠只在首页做了。关注页头部同样高（头像行 + 两行 chip），是下一个可以套用的地方。
- 用户提的"其他也优化"这条线上，还有两处没动：首页卡片封面的三个人气/分区角标是否继续简化、
  以及底部导航是否要更"安静"（现在是白底 + 分隔线）。


---

## 第九十九～一百轮迭代：颜色与芯片（用户："颜色、设计还是有问题"）

### 缺陷是什么

1. **底色带紫调。** `#F6F5F8` 页面、`#EDEAF1` 芯片底、`#E2DEE8` 边框——每一屏都有一层淡紫。
   配一个高饱和的品牌粉时这是双重伤害：粉色本该是屏幕上唯一饱和的东西，而到处"第二弱的紫"
   让整体读成"一个偏紫的主题"而不是"中性画布上的粉色强调"。
2. **占位封面是四个不相关的高饱和色**（深青、紫→玫瑰、青→绿、紫→粉）。网格里任意两张都可能
   撞在一起，一屏读成"一袋颜色"，品牌粉淹在里面。
3. **同一种控件有两种长相**：首页分组行选中是粉色药丸，分区行选中是粉色**+对勾**，未选中两边
   都描边。一屏十几到几十个描边药丸是"页面显得乱"的主要来源；两行同一棵树用两种语言，让两个
   层级看起来像两种控件。

### 怎么修的

- **灰改成逐字节中性**（R=G=B），层次只靠 1–3 点明度差 + 纯白卡片。品牌粉于是成为唯一彩色。
- **占位封面收成同族四色**（灰玫瑰 / 板岩蓝 / 暗梅 / 灰绿），明度与彩度接近：网格看起来是
  "一幅构图"，粉色角标压在上面仍然最响。
- **全仓一套 chip**：抽出 `LiveHubFilterChip`（填充 `surfaceVariant`、**无 border**、选中
  `primaryContainer`），17 处调用点全部换上；分区行的对勾删掉——填充+色相变化本身已经说清
  选中状态，图标是多余的第三人。

### 一处自己造的编译错（值得记）

用 PowerShell 给 import 去重时踩了 `@{}` **键大小写不敏感**的坑：
`import androidx.compose.ui.unit.Dp` 与 `...unit.dp` 被当成同一个键，先出现的 `Dp` 留下、`dp`
被丢掉，于是整列 `Unresolved reference dp`。跨大小写去重要用区分大小写的集合（`-ccontains`）。

### 本轮验证

| 检查项 | 结果 |
| --- | --- |
| `:app:lintDebug` | ✅ 0 error |
| JVM 单元测试 | ✅ **864** 个用例，0 失败（改色不涉及断言：确认过全仓测试没有锁色值） |
| 仪器测试 | ✅ **63** 张截图全部重拍，逐张看过 |
| Release assembly（R8） | ✅ |
| CI | ✅ `aef37be` 三个 job 全绿 |

### 留下的

- 关注页头部仍高（头像行 + 一行 chip），可以套用首页那套折叠。
- 设置页「添加屏蔽词」的输入框仍是最响的元素（`OutlinedTextField` 的描边）；可换填充式。
- 深色模式只做了同样的中性化，没有单独推敲（`23-home-dark` / `23b-settings-dark` 已重拍）。


---

## 第一百零二轮迭代：折叠抽成组件、关注页复用（用户："能用已有组件的尽量用"）

上一轮的折叠是写在 `HomeScreen` 里的。关注页的头部更高（标题 + 搜索框 + 特别关注头像行 + 两行
chip），同样该折——但不能抄第二份。

### 做了什么

- **`components/CollapsibleHeader.kt`**：`CollapsibleHeaderState`（一个布尔 + 一个
  `NestedScrollConnection`）、`rememberCollapsibleHeaderState(hasScrolled)`、
  `CollapsibleHeader(state){ ... }`、`Modifier.collapsibleHeaderScroll(state)`，以及纯函数
  `headerVisibleAfterDrag` 与它的 5 条决策表用例（`components/CollapsibleHeaderTest`）。
- **首页**改成用组件（行为不变，"什么算滚动过"的 lambda 传进去）。
- **关注页**：`rememberLazyGridState` 从 `FollowGrid` 提到页面级（头部要问"列表动过没有"），
  头部整体包进 `CollapsibleHeader`。
- **chip 语言统一**（上一轮）之后，这一轮没有再动视觉，只复用了已有组件。

### 顺带修的两处测试写法

1. **锚点不能拿 fixture 当锚点**：关注页的折叠用例原本断言「全部」chip，可关注页显示哪些 chip
   取决于列表内容（全是同一平台时平台行本来就不画）。改用只属于头部的「正在直播」副标题。
2. **半露的卡片不能当手势起点**：底部那张卡的圆心通常落在窗口外，注入的触摸点无效，失败会
   显示成"头部没回来"。展开手势改成从窗口中心往下拖。

### 截图 harness 的一个真缺口

`scrollToText` 走的是列表的**语义动作**（`scrollToItem`），**不产生嵌套滚动事件**——所以"靠滚动
折叠头部"的页面在语义滚动下什么都不折。`01d` 上一轮能折纯属运气（语义滚动先在 chip 行上试、
再落到网格上的副作用），`07f` 第一次拍就直接暴露了。

新增 `capture(swipeUpCount = n)`：从窗口中心往上真拖，手势与折叠动画期间放开时钟、之后冻结。
`01d` 与 `07f` 都改用它，两张图从此各只说明一件事。

### 本轮验证

| 检查项 | 结果 |
| --- | --- |
| `:app:lintDebug` | ✅ 0 error |
| JVM 单元测试 | ✅ **866** 个用例（折叠决策表 5 + 两屏各两条手势用例），0 失败 |
| 仪器测试 | ✅ **64** 张截图（+`07f-follow-header-collapsed`） |
| Release assembly（R8） | ✅ |
| CI | ✅ `ce1306b` 三个 job 全绿 |

### 留下的

- 设置页「添加屏蔽词」的输入框仍是全页最响的元素（`OutlinedTextField` 描边）。
- 深色模式没有单独推敲。
- 播放页的清晰度/弹幕面板里还有 8 处 chip，现在也走同一个组件了，但没有专门的截图覆盖它们
  在大字号下的样子。

---

## 第一百零三轮迭代：官方组件收口（用户：「尽量使用官方组件，别自己写」）

上一轮把折叠头部抽成组件后，用户给了这一轮的题目：新应用应该 Compose-first、用
`androidx.compose.material3`，把动态色彩、色调表面、弹簧动效这些 MD3 特性用起来，
**尽量用官方组件，别自己写**。所以这一轮不是找功能 bug，而是把「我们自己写的、材料里
其实已经有了」的控件换回官方实现，并把主题系统缺的那两块补上。

### 缺陷是什么

1. **播放页有七个自造控件。** `PillButton`（`Box` + `clickable` 包一个 `Surface`）、
   `ControlFab`、`PictureButton`、全屏栏的返回与关注、弹幕的「开/关」两个 chip。三类后果：
   `Modifier.clickable` 不带 `Role.Button`（读屏只说"可双击"，不说"按钮"）；全屏关注星
   无论是否已关注都描述成「关注」（读屏会劝你关注一个已经关注的房间，而同一页的竖屏
   顶栏一直是对的）；同一个「显示弹幕」在设置页是 `Switch`、在播放页是两个 chip。
2. **设置页与同步页的「标题 + 说明 + 控件」行是手搭的 `Row`。** 六处复制同一套
   16dp padding + `Column` + `labelSmall` 算术，行高、内边距、字阶各自为政。
3. **关注页两处 a11y 缺陷。** 「选择特别关注」的选中态只有一个 18dp 对勾
   （`contentDescription = null`），读屏听不出哪些已选；特别关注头像的点击是裸
   `pointerInput`，读屏与开关访问**没有任何动作可触发**（既不能打开也不能上移下移）。
4. **主题只下发了 `ColorScheme` 与 `Typography`，没有 `Shapes`。** `MaterialTheme` 的第三个
   系统是空的，圆角散在 17 处字面量里；**Material You 只是一个不存在的开关**——第 99 轮把
   `dynamicColor` 字段删了，理由正是"它没接到主题上"。
5. **同步记录的成功/失败只靠颜色。** `↑`/`↓` 是 `Text`，失败是红 tint，读屏读不出失败。
6. 顺带：**清除缓存连点会清两次、发两条 snackbar**，中间没有任何"进行中"反馈。

### 怎么修的

- **播放页**：五个 pill 落到一个 `Button`（20dp 圆角 + `buttonColors` + `contentPadding`
  保留原视觉）；`ControlFab`/`PictureButton`/全屏栏返回 → `IconButton`（包住原来的
  38dp / 34dp 圆盘与白图标，像素基本不变）；全屏关注星 → `IconToggleButton`（描述随状态变
  关注 / 取消关注，选中态有了）；弹幕开/关 → 一个 `Switch`，与设置页同一个控件。
- **设置 / 同步**：卡片里的行改 `ListItem`，容器色显式透明
  （`ListItem` 自己填 `surface`，而 filled `Card` 是 `surfaceContainerHighest`——品牌配色下
  这两个 token 恰好同色、动态配色下不同，不覆盖就会在卡片里再画一层矩形）。说明文字仍用
  `labelSmall`：字阶是第 82 轮钉死的，这一轮**只换结构不换字阶**，截图因此只差行高与内边距。
  屏蔽词输入 → 填充式 `TextField`（第 99 轮记过描边是全页最响的元素）。
- **关注页**：选择行 → `ListItem` + `Modifier.toggleable(Role.Checkbox)` + 只读
  `Checkbox`（行拥有状态、勾选框只显示，读屏能听到已选中）；头像的同一节点补
  `role = Role.Button`、`onClick("打开直播间")`、`customActions` 上移/下移（复用拖动用的
  `onReorder`，只有真能移动的方向才给，不新造行为）。
- **主题**：新增 `LiveHubShapes`（6 / 10 / 12 / 20 / 28dp）交给 `MaterialTheme`；新增
  `dynamicColor`（默认关）：`dynamicLightColorScheme` / `dynamicDarkColorScheme`，
  Android 12 起可用，设置页开关 + 持久化，品牌配色仍是默认。SDK 判断写在分支里而不是藏在
  helper 后面——**lint 只认字面量比较**，藏在 helper 里会 `NewApi` 打红 `:app:lintDebug`。
- **同步记录**：箭头图标带 `contentDescription`，失败追加「，失败」——方向本来就在行文本里，
  只有失败是读屏拿不到的。
- **清除缓存**：`clearing` 守卫 + 按钮位置换 `CircularProgressIndicator`、`enabled = false`。

### 一处只有 CI 才能发现的错

本地没有编译器，第一次推送只报一处：`SemanticsProperties.CustomActions` 不存在。
compose-ui 1.12.1 把这个键放在 **`SemanticsActions`**（`AccessibilityKey`，merge policy 把
父子列表拼起来），`SemanticsProperties` 里只有 `Role` / `ToggleableState` 之类。改成
`config.getOrNull(SemanticsActions.CustomActions)`（键缺失时给空列表而不是抛异常）后全绿。
**这一轮的价值有一半来自这里**：`ListItem` 的合并语义、`expectValue(Role, …)`、
动态配色的资源读取、`Button`/`IconToggleButton` 的重载全都一次编译通过，说明"按 1.4.0
源码逐个核参数"这套做法是可靠的。

### 本轮验证

| 检查项 | 结果 |
| --- | --- |
| `:app:lintDebug` | ✅ 0 error（`TouchTargetSizeCheck` 仍是 unknown issue id 的旧告警，与本轮无关） |
| JVM 单元测试 | ✅ **887** 个用例，0 失败（866 → +21：主题 3、动态取色 3、播放控件 6、同步行 4、关注语义 4、ViewModel 1） |
| 仪器测试 | ✅ **64** 张截图全部重拍；**44 张有变化、20 张逐字节未变** |
| Release assembly（R8） | ✅ |
| CI | ✅ `762c4f7` 三个 job 全绿 |

截图逐张看过的结论：

- **播放页 13 张**：pill 由 34dp 变 40dp（M3 `Button` 的最小高度）、全屏星标 22→24dp、
  设置面板里弹幕由两个 chip 变一个开关。没有裁切、没有位置错位。
- **设置/同步 8 张**：卡片变成 M3 列表（行高 72dp、16dp 内边距、行间分隔线），
  动态取色行出现在外观卡底部，大字号（`27`）下说明文字折成三行而开关仍在行内。
- **关注 6 张**：选择面板的行变成 56dp 的 `ListItem` + 复选框，选中项红色标题 + 勾选；
  1.5× 下（`29`，非面板）头像与 chip 未变形。
- **搜索 12 张 + 大字号 2 张**：这批不是本轮的控件改动，而是 `Shapes` 生效后
  `AssistChip` 的圆角 8→10dp、输入框 4→6dp。**这是有意让主题接管形状的可见代价**，
  逐张确认只是圆角变化、没有布局位移。
- 首页/分类/卡片网格 20 张逐字节未变，证明 `shapes.medium` 保持 12dp、卡片没有被这一轮碰到。

### 留下的（按价值排，下轮可直接开工）

1. **播放页横屏设置抽屉退不出**（真缺陷，本轮没动）：`PlayerSettingsDrawer` 是手搭的
   scrim + `Surface`，系统返回键关不掉它——`BackHandler` 只在
   `fullscreen && !compact && !isLandscape` 时启用，而抽屉只在 `isLandscape` 时出现；
   scrim 也没有 dismiss 语义。改成 `ModalNavigationDrawer` + `ModalDrawerSheet` 能一次解决
   三条，代价是宽度/形状默认值与现在的 42% / 320dp 不同，且没有截图基线。
2. **`StreamerCard` 的 1dp 阴影画不出来**：`clip(CardShape)` 在 `Surface` 外面，阴影被裁掉。
   官方 `Card(onClick = …)` 能修，但 `Card` 没有 `onLongClick`，关注页的长按需要一个分支，
   且会动 20+ 张截图——单独立项。
3. **`StreamerCard` 的关注星**仍是 `IconButton` + 条件图标，可以换成 `IconToggleButton`
   （与全屏栏那次同样的收益），低风险，只是不在本轮四个文件里。
4. **搜索页没有用官方 `SearchBar`**：现在是 `OutlinedTextField` + chip 行。换成
   `DockedSearchBar` 是一次交互改版（焦点、IME、320dp 下的折叠），要单独一轮。
5. **`MatchingCodeDialog` 仍是 `Dialog` + `Surface`**：第 36/37 轮记录了理由
   （`AlertDialog` 的固有宽度测量 + 输入框导致 `AppNotIdle`），没有推翻依据前不动。
6. **填充式输入框的填色等于卡片色**：本项目的主题把 `surfaceContainerHighest` 映射成卡片色，
   所以「填充」在视觉上只剩一条下划线。要更明显就得给 `TextField` 覆盖 container token
   （`surfaceContainer`），属于视觉轮的活。
7. **动态配色下的观感没有渲染证据**：64 张截图全是品牌配色。打开 Material You 后的
   卡片/芯片/列表行对比度需要一次专门的截图（harness 支持传 `dynamicColor` 之后）。
8. 官方组件里本项目**没有用途**的：`NavigationRail` / `BottomAppBar` / `DatePicker` /
   `SearchBar` / `Badge` / `LinearProgressIndicator`。不为"用上"而加。

---

## 第一百零四轮迭代：视觉重塑（大标题 / 无框卡片 / 浮动导航）

用户的下一句是「界面弄简洁好看一点，需要有设计感的那种」，并给了一张参考图
（legado-with-MD3 的「书架」页，https://github.com/HapeLee/legado-with-MD3 ）：页面
左上一个很大的标题、右上搜索/更多图标、下面一行文字 Tab（选中项下划线），内容是
「封面 + 标题」的三列网格、**没有卡片容器**，底部是一条**浮起来的圆角导航**。

上一轮解决的是「控件用官方的」；这一轮解决的是「页面怎么排」。规则压缩成三条：
**一屏一个标题、封面就是卡片、导航浮起来。**

### 缺陷是什么

1. **每个页面的开头都不一样，而且没有一层是"页面名"。** 设置从「外观」这个小标签开始，
   同步直接从「本机」卡片开始，关注把页面名和计数焊成一句话（`关注  4/5 正在直播` 同一个
   基线），首页是平台 Tab、搜索是搜索框。切 Tab 之后眼睛每次都要重新找"这是哪一页"。
2. **卡片是三层套起来的盒子。** 16dp 圆角的 `Surface` + 6dp 内边距 + 12dp 圆角的封面，
   封面下面再一块文字区：一张 144dp 宽的卡片上有三个矩形、一个 1dp 阴影，而封面本身才是
   用户在看的东西。搜索结果更奇怪——同一件事（一个直播间）在网格里是白卡片、在列表里是
   灰色长条，两种语言。
3. **封面角上贴了三样东西**：LIVE 药丸、观众数黑色药丸、分区药丸，再加一个星标。
   观众数在 11sp 白字 + 黑色蒙版上，是整张卡片最难读的数字。
4. **底部导航是贴边的一条板**：上面一根 1dp 分割线，选中和未选中用同一种实心图标。

### 怎么修的

- **`components/PageHeader.kt`**：24sp `headlineSmall`（-0.4sp 字距）+ 12sp 弱化副标题，
  左右 16dp（关注页 12dp，跟它 12dp 的内容对齐）。关注/同步/设置 三页统一用它开头；
  **首页不加**——平台 Tab 行本身就是标题，加一行标题要 52dp，而第 22/78 轮把首页
  chrome 从 268dp 压到 100dp 才换来今天的三排卡片（`HomeCategoryDensityTest` 钉在
  190dp 这条线上）；搜索页同理，搜索框就是它的标题。
- **卡片去掉容器**：`StreamerCard` 从 `Surface + 内嵌封面 + 文字区` 改成
  `封面（满宽、圆角即卡片）+ 标题 + 元信息`，直接画在页面底色上。观众数从封面黑药丸
  移到元信息行（眼睛图标 + 11sp 弱化字），封面只剩 LIVE 与星标；角标改成 92% 的浅色
  药丸（LIVE 仍用品牌粉），星标两种状态共用同一块浅色圆片——原来是黑蒙版/白圆片两个
  长相。`StreamerSearchCard` 同步去掉灰底，与网格同一套语言。
- **底部导航浮动**：`Box(navigationBarsPadding + 12dp 左右/10dp 下) >
  Surface(26dp 圆角, surfaceContainer, 8dp 阴影) > NavigationBar(windowInsets=0)`；
  去掉那根分割线；未选中用 `Icons.Outlined.*`、选中用 `Icons.Filled.*`；标签 11→12sp。
- **设置/同步每行加 leading 图标**（22dp、onSurfaceVariant）：动态取色 Palette、
  显示弹幕 ChatBubbleOutline、屏蔽词 Block、后台刷新 Refresh、仅 Wi-Fi Wifi、图片缓存
  Storage、自动同步 Sync、本机 Smartphone、已配对 Devices、附近设备 Sensors。
- 顺手清掉 `HomeScreen` 里两个早就没人用的 import、`LiveHubRoot` 里浮动导航取代掉的
  `Column` / `HorizontalDivider`。

### 一次只有 CI 能发现的错

`LiveHubRoot` 从 `Column + NavigationBar` 改成 `Box + Surface + NavigationBar` 之后，
`fillMaxWidth` 成了新用到的 API，而它在这个文件里从没被导入过——三个 job 一起红在
`:app:lint` 的 `Unresolved reference 'fillMaxWidth'`。补一行导入即绿（`8098459`）。

### 本轮验证

| 检查项 | 结果 |
| --- | --- |
| `:app:lintDebug` | ✅ 0 error |
| JVM 单元测试 | ✅ **891** 个用例，0 失败（887 → +4：`DesignLanguageTest`） |
| 仪器测试 | ✅ **64** 张截图全部重拍；**50 张变化、14 张逐字节未变** |
| Release assembly（R8） | ✅ |
| CI | ✅ `8098459` 三个 job 全绿 |

截图逐张看过的结论：

- **首页 12 张（含深色/1.5×/2.0×）**：卡片变成"封面 + 两行字"，一屏 6 张封面、信息层级
  清楚；浮动导航在 320dp 上 5 个标签都放得下（未选中描边、选中实心）。2.0× 下平台 Tab
  仍是单字（第 91 轮记录过的已知上限），卡片标题/元信息按 ellipsis 降级，导航没有溢出。
- **关注 6 张**：标题 24sp + 「4/5 正在直播」副标题，下面是头像行、chip 行、网格；
  1.5× 下标题不折行。
- **搜索 12 张**：结果行去掉灰底后与网格同一语言；空态/历史/最近看过没有回归。
- **同步/设置 8 张**：masthead + leading 图标；1.5× 下副标题仍能一行放下（同步那条
  「同一 Wi-Fi 下与已配对设备增量同步」是本轮最长的副标题，专门看过）。
- **播放页 10 张逐字节未变**：这一轮没碰播放器，正好证明改动范围是"浏览页"。
- 深色模式三张：封面、文字、浮动导航对比度正常，没有"白卡片残留"。

### 留下的

1. **首页没有大标题**（有意）：加一行 masthead 要 52dp，会推翻第 22/78 轮的密度工作。
   若哪天确认首页可以牺牲一排卡片，`PageHeader(title = "直播")` 放进 `CategoryTree`
   顶部即可（折叠时会跟着收起）。
2. **分类 chip 两行没动**：参考界面用的是文字 Tab，我们的分类是两级 chip。改成 Tab 会
   重开第 78 轮"分组收进 chip + 面板"的决定，且要重新量密度，单独立项。
3. **搜索页顶部没有标题**：搜索框本身就是标题，加一行 24sp 的「搜索」与它重复。
4. **占位封面仍是四色渐变 + 播放三角**：真实封面在截图 harness 里不可得；要不要把占位
   也做成参考图那种纯色 + 平台字标，需要真机看图再定。
5. **动态配色（Material You）下的新配色没有渲染证据**：50 张截图仍是品牌配色。
6. 播放页 chrome（pill 按钮、全屏栏、弹幕列表）这一轮没动，仍是第 103 轮之后的样式。

---

## 第一百零五～一百零七轮迭代：三处用户报告（关注排序+滑动+去搜索 / 全屏横屏 / 本地网络弹窗）

用户在 v0.21.0 之后连报三件事，并发了截图。三条都不是"设计偏好"，是**动不了**：

### 1. 关注页「特别关注」：不按开播排序、也划不动（105）

- **排序**：`special` 是 `specialKeys.mapNotNull { byKey[it] }`——纯手动顺序，于是一个没开播的
  置顶房间会排在开播的房间前面，而下面的网格是"开播优先"。改成
  `.sortedBy { if (it.isLive) 0 else 1 }`（稳定排序，组内仍是手动顺序）。
  已知代价写进了 KDoc：`FollowOrdering.moveWithin` 持久化的是**手动**顺序，所以把离线头像拖到
  开播头像之前会存下来、下一帧又被分组排回去——排序只对"组内"有意义。
- **划不动（真 bug）**：头像的手势是裸 `awaitEachGesture` + `awaitLongPressOrCancellation`。
  这个 API 在**两种**情况下都返回 null：提前抬手，以及"父级消费了移动"（正是这一行自己的
  横向滚动）。于是旧代码把每一次横扫都当成 tap，抬手时打开一个直播间——用户看到的就是
  "划不动"。改成官方 `clickable` + `detectDragGesturesAfterLongPress`：
  滚动赢了手势时拖动检测器自己取消，拖动消费了抬手时 `clickable` 自己取消。
  semantics 交回 `clickable`（role/onClick/ripple 由组件给），`customActions` 上移/下移保留。
  新增 `DesignLanguageTest` 一条：存入顺序是"离线在前"时，开播头像必须画在前面（此前
  所有夹具都恰好是开播在前，这条规则零覆盖）。

### 2. 关注页去掉搜索框（105）

用户："关注页面不需要搜索"。删除 `FollowSearchField`、`FOLLOW_SEARCH_THRESHOLD`、
`followQuery` 过滤、`UiState.followQuery`、`UiAction.SetFollowQuery`、`MainViewModel` 分支、
`FollowTagsTest` 的 reducer 行；**`FollowSearchTest` 整个删除**（功能没了，测试随之消失，
这是需求变更不是"为了绿删测试"）；`ScreenshotTest` 去掉 `07c-follow-search`（64 → 63 张）；
README 去掉"按名字/标题过滤"。

### 3. 播放页点全屏不自动横屏（106）

`playerFullscreen` 只按**窗口**判定：竖屏点全屏是把画面画满竖屏窗口并藏掉 chrome，窗口
根本没转。新增 `ui/player/PlayerOrientation.kt`：全屏按钮请求
`SCREEN_ORIENTATION_SENSOR_LANDSCAPE`，退出全屏 / 最小化 / 组合销毁时恢复
`SCREEN_ORIENTATION_UNSPECIFIED`；**物理旋转不锁定**（那条路本来就会全屏，锁住反而夺走
"转回来"）。`PlayerOrientationTest` 5 条：决策表 + 点「全屏」请求横屏、点「退出全屏」释放。
`PlayerScreen` 顺手改成 `internal`——CI 抓到 `public` 函数暴露 `internal` 的
`PlayerOrientation`（一次红灯，见验证表）。

### 4. 本地网络弹窗（107）

用户截图问「进应用的这个选择要连接的设备是干嘛的啊」。那是 **Android 16 的本地网络访问
权限**：应用第一次碰局域网（我们的同步服务端 / mDNS）时，系统自己弹的，不是我们的界面。
同步页「本机」卡片加了一段说明，把"系统会弹什么、为什么、拒绝会怎样"写在弹窗之前。
**只做了说明，没改触发时机**：目前只要已配对 + 自动同步开着，启动就会起服务端（第 87 轮的
决定），所以有配对设备的人一开应用就会看到弹窗。把它推迟到"用户第一次打开同步页"需要改
那套策略并重写 `SyncServerLifecycleTest` 的两个前提，留作下一轮。

### 本轮验证

| 检查项 | 结果 |
| --- | --- |
| `:app:lintDebug` | ✅ 0 error（第一次红灯：`PlayerScreen` public 暴露 internal 类型） |
| JVM 单元测试 | ✅ **892** 个用例，0 失败（891 − `FollowSearchTest` + `PlayerOrientationTest` 5 + 1） |
| 仪器测试 | ✅ **63** 张截图（`07c-follow-search` 随功能删除） |
| Release assembly（R8） | ✅ |
| CI | ✅ `a25b7b6` 三个 job 全绿 |

截图看过：`07-follow-list` 顶部没有搜索框、头像/标题/网格顺序正常；`13-sync` 多出的那段
说明在「本机」卡片里、折行正常；其余 61 张里只有关注/同步相关几张随文字变化。

### 留下的

1. **本地网络弹窗的时机**（见上）：要么推迟到同步页，要么在设置里给一个「允许局域网同步」
   的显式开关；两条都要改 `autoSyncWanted()` 与 `SyncServerLifecycleTest`。
2. **跨组拖动会被排序排回去**：如果用户真的需要"置顶一个没开播的"，得再设计（比如只对
   开播组排序、离线组保持手动，或者给"固定"与"排序"两个概念拆开）。
3. `07` 编号出现空档（07c 删除），没有重排——`07d` 在文档与大字号注释里被按名字引用。
