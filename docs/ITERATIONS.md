# 迭代记录

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
- 墓碑式删除，保留 30 天
- 设备 ID 决胜，保证收敛
- `(deviceId, sequence)` 幂等去重
- 配对码 + 共享密钥鉴权
- LAN HTTP 服务端 + 套接字客户端 + mDNS 发现
- 同步范围可单独开关

### UI
- 首页（平台切换 / 分区 / 瀑布流 / 无限加载）
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

| 模块 | 测试数 | 覆盖内容 |
| --- | --- | --- |
| `core:common` | 16 | 退避策略、TTL 缓存（含并发单飞）、数值格式化 |
| `core:sync` | 51+ | HLC、合并算法、墓碑剪枝、同步引擎、协调器、LAN 服务端 |
| `platform:douyu` | 43 | 签名公式、弹幕编解码、JSON 映射、清晰度选择 |
| `platform:huya` | 34 | Tars 编解码往返、防盗链签名、心跳、GBK 回退 |
| `platform:bilibili` | 29 | 列表/WBI 签名、mixinKey 置换、帧编解码、Cookie |
| `platform:douyin` | 20 | Protobuf、推送帧、签名、URL 安全 |
| `:app` (instrumented) | 4 | 容器装配、DataStore 往返、关注列表、同步包生成 |

---

## 第二轮迭代

在上述绿灯基础上补齐了四项原先只有后端、没有界面的能力（commit `14c668e` 再次全绿）：

| 功能 | 实现位置 | 说明 |
| --- | --- | --- |
| 分区订阅 | `CategorySubscriptionStore` + `HomeScreen` | 顶行固定显示已订阅分区；同样是带墓碑的同步记录，会随 `CATEGORIES` scope 同步 |
| 关注拖拽排序 | `ReorderableFollowList` + `FollowStore.reorder` | 长按拖动；顺序写进每条记录的 `order` 字段并整体重新盖 HLC，所以排序本身也会同步 |
| B 站扫码登录 | `BilibiliAuth` + `BilibiliLoginCard` + `QrCodeRenderer` | 轮询 `qrcode/poll`，登录成功后 `Set-Cookie` 并入共享 Cookie 存储 |
| 关注后台刷新 | `FollowRefreshWorker` | WorkManager 每 30 分钟刷新开播状态；只在主播仍被关注时更新，不会把已取消的关注"复活" |

顺带修掉一个缓存 bug：`FollowStore.records()` 原先用 `isNotEmpty()` 判断是否已缓存，
导致空关注列表每次调用都重新解析 DataStore；改成可空哨兵。

补记一个真实缺陷：

9. **`FollowStore` 空列表缓存失效**
   `if (cachedRecords.isNotEmpty()) return cachedRecords` 无法区分"未加载"和"已加载但为空"，
   关注列表为空时每次读取都会重新解析 JSON。

---

## 第三轮迭代

补上配对流程的完整握手（commit `0428168`，CI 全绿）：

`SyncController.pair(peerName, address, code)` 会真的向对端发一个 `pair` 请求，
对端用自己正在托管的配对码校验，成功后返回 per-peer 共享密钥；两端各自落库。
在此之前 `PairPeer` 只在本地登记了一个哈希出来的设备 ID，跨设备同步实际上无法建立。

配对对话框新增"对方局域网地址"输入框，默认填入本机地址，方便用户照抄到另一台设备。

---

## 第四轮迭代：跨设备同步的端到端验证

前三轮里，同步只有**组件级**单元测试（合并算法、HLC、HTTP 服务器各自独立），
从来没有两台设备真正通过 HTTP 交换过数据。这一轮补上了
`CrossDeviceSyncTest`：两个独立配置的「设备」各跑一个真实的 `SyncHttpServer`，
通过 `LanSyncTransport` 在 loopback 上互相通信，只把地址簿替换成固定地址。

**这一轮找出了 4 个真实的协议缺陷，每一个都会让同步完全不可用：**

| # | 缺陷 | 后果 |
| --- | --- | --- |
| 10 | `completePairing` 用本机 `idGenerator` 生成 secret | 双方各存了一个互不相同的密钥，双向认证全部失败 |
| 11 | 服务端 `handlePull` / `handlePush` 在响应里回显 `localSecret` | 对端会用这个固定值覆盖掉正常密钥 |
| 12 | 配对响应回传的是**调用方**的 secret，不是自己的 | 调用方存了个自己刚发出去的值，永远认证不过 |
| 13 | `LanSyncTransport` 用 `peer.sharedSecret` 作为请求凭据 | 字段语义反了：那是「我用来认证对端」的凭据，不是「我对端出示」的凭据 |

另外修了一个可用性问题：

| # | 缺陷 | 后果 |
| --- | --- | --- |
| 14 | 配对时记录对端地址用的是 `DEFAULT_PORT` 而非对端实际端口 | 端口被占用回退到临时端口时，配对成功后无法再连上 |

第 14 条的修法是给 `SyncEnvelope` 增加 `preferredPort` 字段：握手是唯一能得知对端
监听端口的时机，必须携带。

### 验证的场景

`CrossDeviceSyncTest` 覆盖了微信输入法式同步最容易出错的地方：

- 配对后双方各持对方会出示的密钥（`authenticate` 的双向不变量）
- 手机上的关注出现在平板上
- 手机上的取消关注会在平板上**移除**该房间，并留下墓碑
- 晚到的「关注」不会复活已被取消的房间
- 同一轮交换执行两次不会产生重复
- 两台离线设备各自增删后，一次交换即可收敛到相同结果
- 未配对设备无法拉取
- 重放的 payload 被序号去重挡下
- 对端停机时返回失败而不是挂起
- 含 emoji 的主播名能完整往返

---

## 当前状态

第四轮结束（commit `78d4107`）：

| 检查项 | 结果 |
| --- | --- |
| `:app:lintDebug` | ✅ |
| JVM 单元测试 | ✅ **264 个用例，0 失败** |
| `:app:assembleDebug` / `assembleRelease` | ✅ |
| 仪器测试（API 34 模拟器） | ✅ 24 个用例 |
| 页面截图 | ✅ 20 张，每次 CI 重新渲染 |

代码规模：13 个 Gradle 模块，约 13 000 行 Kotlin。

## 后续可做

- [ ] 同步页实时展示 mDNS 扫描到的设备（`SyncController.discoveredPeers` 已接，UI 已加）
- [ ] 弹幕字号/透明度设置项接入设置页滑块
- [ ] 播放器全屏/横屏手势
- [ ] 关注列表增量同步的分页（当前一次传全量）
- [ ] **两台真机实测**：所有同步验证都跑在 loopback 上，真实 Wi-Fi 下的 mDNS 发现
      与跨网段行为仍未在硬件上验证过


---

## 第五轮迭代：ViewModel 与 UI 交互测试

前四轮里，`MainViewModel` 的所有交互逻辑（导航、搜索、关注、同步动作）**只在截图里
被目视检查过**，没有任何自动化断言。这一轮补上 `MainViewModelTest`（20 个用例）与
`FollowOrderingTest`（8 个用例），用 Robolectric 在 JVM 上驱动真实的
`AppContainer` + `DataStore`。

### 这一轮暴露的缺陷

| # | 缺陷 | 后果 |
| --- | --- | --- |
| 15 | `LiveHubApplication.onCreate` 直接调用 `WorkManager` 调度 | WorkManager 不可用时（受限配置、单元测试、初始化器被禁用）**应用启动即崩溃** |
| 16 | `:app` 模块混用 JUnit4 与 JUnit Platform，缺 vintage 引擎 | `testDebugUnitTest` 一个测试都发现不了，`failOnNoDiscoveredTests` 直接判定失败 |
| 17 | `FollowOrdering.move` 的索引语义未定义 | 拖拽往下移动时落点偏移一格 |

第 15 条是真实的健壮性缺陷：后台刷新只是锦上添花的能力，它失败不该让整个应用起不来。
修复方式是把调度包在 `runCatching` 里并记录日志。

第 17 条最终确认**原实现是对的**（`toIndex` 是移除后的位置），错的是测试假设。
但测试本身有价值：它把"落点必须是请求的索引"这条规则固定下来了。

### 覆盖的场景

`MainViewModelTest`：

- 启动落在首页；每个底部导航目标都能到达；返回键回到首页
- 打开直播间进入播放页、关闭清空弹幕缓冲
- 输入不触发搜索；空查询提交时给出可读提示
- 关注/取消关注、重复关注不产生重复项、关注列表跨 ViewModel 实例存活
- 拖拽排序后 store 与内存状态一致
- 弹幕开关、屏蔽词规范化（去空白/去重/截断）、平台选择、主题切换都能持久化
- 同步范围选择持久化；无配对设备时同步给出说明
- 消息消除后 message 与 error 同时清空

`FollowOrderingTest`：前后移动、边界钳制、空列表、单元素、全排列不丢不重。

### 测试规模

| 模块 | 用例数 |
| --- | --- |
| `:app`（Robolectric + 仪器） | 32 |
| `core:sync` | 54 |
| `platform:bilibili` | 29 |
| `platform:douyu` | 45 |
| `platform:huya` | 34 |
| `platform:douyin` | 21 |
| `core:common` | 16 |
| 合计 | **265 个 JVM 用例 + 24 个仪器用例** |

---

## 第六轮迭代：对着线上接口逐个验证，而不是只读参考代码

前五轮的验证都建立在"代码看起来对"之上。这一轮换了方法：**直接请求线上接口和
WebSocket，用真实响应判断实现对错**。结果发现前四轮里被认为"已实现"的能力，有
一批在线上是完全失效的。

### 斗鱼

| # | 缺陷 | 证据 |
| --- | --- | --- |
| 18 | **搜索永远返回 0 条** | 线上 `searchUser` 的每个元素是 `{"type":1,"anchorInfo":{…}}`，房间号/昵称/live 标记全在 `anchorInfo` 里。旧代码直接从元素上读 `rid`/`nickName`，实测 20 条里**保留 0 条**。参考实现是下钻 `anchorInfo` 的，因此一直是对的。 |
| 19 | **`betard` 分类永远为空、观众数永远 0** | 线上 `cate2_name` 字段存在但**值为空字符串**，真实标签是 `second_lvl_name`（根上还有 `game.tag_name`）。观众数根本没有 `hotabit`/`hot_num`，实际是 `room_biz_all.hot`（字符串）。 |
| 20 | **清晰度切换是空操作** | 探针响应广告 `原画720P30:0 / 超清:3 / 高清:2`，但旧实现只解析选中的那一个 rate，清晰度菜单永远只有 1 个 chip。现在把广告出来的每个 rate 并发解析成 variant，并让 CDN 列表随 `PlaybackSource` 一起返回，线路选择才真正可用。 |
| 21 | **弹幕发的是 text 帧** | 握手声明 `Sec-WebSocket-Protocol: binary`，但 `loginreq`/`joingroup`/`mrkl` 用 `encodeUtf8()` 发成 opcode-1 文本帧。`DouyuDanmakuProtocol.encode` 就在旁边（而且有单测），却没被调用过。 |

### 虎牙

| # | 缺陷 | 证据 |
| --- | --- | --- |
| 22 | **TARS 长度字段编解码都错** | 写方向用裸大端 int32，读方向也按裸 int32 读。TARS 的 SIMPLE_LIST/LIST 长度是 **tag 0 的紧凑整型**。线上实测：用紧凑编码发注册帧，服务端回 **394 字节推送**；用裸 int32 发同一帧，只回两个小帧然后静默。读方向更糟——把长度 head 当数据吃掉，得到 `truncated payload (len=1641489)`，调用方的 `runCatching` 把每一帧都吞了，**虎牙弹幕双向全死**。 |
| 23 | **心跳帧服务端不认** | 旧实现"重新合成"心跳 `{0:105, 1:{0:10, …}}`。实测线上：参考实现抓到的 111 字节帧会收到 `OnUserHeartBeat`/`tRsp` 回复，合成的 50 字节帧**完全没有回应**。一个被忽略的心跳比没有心跳更糟——看门狗会因此拆掉健康连接。改为直接使用抓包字节。 |

### 抖音

| # | 缺陷 | 证据 |
| --- | --- | --- |
| 24 | **protobuf 字段号读错** | `PushFrame.payload` 是字段 **8**（旧代码读 3），`ChatMessage.content` 是字段 **3**（旧代码读 7）。两个字段在真实帧里都不存在，因此 `decodeFrame` 对每一帧都返回空列表。 |
| 25 | **ack/心跳帧字段号错** | ack 把 logId 写进字段 1（PushFrame 没有字段 1），心跳把 roomId 塞进帧里。改为字段 2/7/8 的合法 PushFrame。 |

### 四个平台共同

| # | 缺陷 | 证据 |
| --- | --- | --- |
| 26 | **干净关闭后不再重连** | 四个适配器在 `onClosed` 都发 `willRetry=false` 并停止。四个参考实现都是 `onClosed` **或** `onFailure` 都重连。平台会定期回收空闲 socket，因此这等于"看几分钟后弹幕永久冻结"。 |

### 界面（同一轮，来自与参考实现的逐屏对照）

- **下拉刷新此前完全不存在**（全仓库 0 处），首页/关注/搜索都已补上
- **屏蔽词只在全屏悬浮层生效**，竖屏列表渲染的是原始 `state.danmaku`，用户在设置里加的屏蔽词在竖屏完全无效
- **弹幕透明度、横屏字号没有任何入口**（`danmakuOpacity` 有持久化字段但无 setter）
- **分区订阅没有入口**：`ToggleCategorySubscription` 有 action、有 handler，但没有任何 UI 发出它，订阅只能靠同步从别的设备传过来
- **主题是两态且不持久化**：`浅色` 无法选中，重启即丢失。改为系统→深色→浅色循环并写入 DataStore
- **关闭播放器/搜索永远回首页**，从关注列表点进去再退出会丢失位置
- **视频比例硬编码 16:9**：改为从 Media3 `VideoSize`（含旋转与像素比）学习，竖屏流满幅显示，并补上 IDN 报错时的 https→http 回退
- **关注列表按手动顺序**，在线的主播被压在离线名字下面；改为在线优先（稳定排序保留手动顺序），并补上"在线边框"和行内取消关注

### 本轮 CI 自身也修了 4 个问题

CI 从一个"看不见原因的红灯"变成了能定位的红灯。期间修掉：

1. lint 报告只写文件不打日志，而读取 job 日志需要管理员权限 → 改为把每条发现输出成 `::error::`/`::warning::` 注解（并按真实 severity 分级，之前把 8 条 warning 也标成 error，掩盖了真正的失败原因）
2. `PlayerScreen` 插 import 时把 `TextAlign` 和 `TextOverflow` 两行 import 粘成一行 → 顶层语法错误，全模块无法编译
3. 单测失败只报 `exit code 1` → 增加 JUnit XML → 注解的转换，另加对 `e:` 行的兜底注解
4. `hexToBytes` helper 写在了另一个测试类里，虎牙测试源集编译失败，导致**一个 JVM 用例都没跑**

### 当前状态（run 107，commit `34c309e`）

| 检查项 | 结果 |
| --- | --- |
| `:app:lintDebug` | ✅ 通过（0 error，仅剩版本提醒与方向性 hint） |
| JVM 单元测试 | ✅ 通过 |
| `:app:assembleDebug` / `assembleRelease` | ✅ |
| 仪器测试（API 34 模拟器） | ✅ 通过 |
| debug APK 产物 | ✅ 27.1 MB |

### 仍未解决 / 下一轮

- ~~**抖音弹幕连不上**~~：第七轮已补上完整握手（见下）。
- ~~**抖音签名不一致**~~：第七轮已合并为唯一实现。
- ~~**斗鱼清晰度还没走标签路径**~~：第七轮已接上。
- ~~**虎牙列表分页**~~：第七轮已改为读服务端元数据。
- **抖音 HTTP 风控画像仍不完整**：`headers()` 已补 `Cookie`，`msToken` 已改为真实 107 字符，UA/`browser_version` 已对齐，但**未在真机验证过服务端是否接受**。
- **未做真机验证**：以上都是本机 HTTP/WebSocket 探针 + 单测的结论，真实设备上的 mDNS、CDN 可达性、弹幕握手仍未验证。

---

## 第七轮迭代：抖音握手、虎牙分页、斗鱼标签清晰度

### 抖音弹幕握手

上一轮把抖音列为"唯一确认完全不可用"的能力。原因有二，本轮都已修：

1. **WS URL 是裸地址**。抖音只接受携带约 30 个 query 参数 + `signature` 的
   `webcast/im/push/v2/` 连接，而 `signature` 由站点自己的 `webmssdk.js` 计算。
   新增 `DouyinDanmakuHandshake`（纯逻辑，可单测）负责拼参数、从房间页解析
   `user_unique_id`、在三个推送域名间轮换；`DouyinRhinoSigner` 用 Rhino（与斗鱼
   签名同一个引擎）求值 `getMSSDKSignature`。

   **本机验证**：先按 `seedBrowserGlobals` 注入 `window`/`navigator`/`document`/
   `location`/`screen`，再调用 `getMSSDKSignature(stub, ua)`，在 V8 下 **12/12 次**
   返回干净的 16 字符 token。MD5 stub 用单测钉住，digest 与我独立用 Node 算的完全一致
   （`b5fe08c8a92fb38880f89a3a56a46b43`）。

2. **四个接口里三个用了自造签名**。`rooms()` 调用的是真的 `aBogus`（我逐行比对过，
   与参考 `generateABogus` 除函数名外**逐字节相同**），但 `search`/`roomInfo`/
   `findInFeed` 调用的是自造的 `DouyinSigner.sign`/`xBogus`。自造签名不可能通过验签，
   那三个接口必然失败。自造实现已删除，模块里现在只有一个 `a_bogus`，不会再选错。

顺带按参考补回：`Cookie`（缺失时抖音返回 HTTP 200 空 body，与"分区为空"无法区分）、
真实 107 字符 `msToken`（原先传空串）、参考 UA 与 `browser_version`、
`internalExt` 从 `Response` 字段 5 读而非从 PushFrame 读、只有 `needAck`
（字段 9）为真时才按该帧 logId 回 ack（定时无脑 ack 服务端不认）。

### 虎牙分页：读服务端元数据，而不是猜

**实测**：`iPageSize` 被**完全忽略**——请求 10/20/30/60 都返回 120 条并声称
`iPageSize: 120`；而响应确实带 `iPageNo`/`iTotal`/`iTotalPage`。

旧停止条件是"条数 ≥ 请求的 pageSize"，前五页永远为真，只是恰好第 6 页为空才停下来；
一个返回重复页的分区会**无限加载**。规则已抽成 `hasMorePages()` 并用单测覆盖中页、
末页、耗尽（`iTotal: 0` + `iTotalPage: 1`）与元数据缺失四种情况。

### 斗鱼：标签清晰度路径接上了

**rate 码跨房间不稳定**：同一个 `高清`，一个房间是 `2`，另一个房间是 `8`。旧 `select()`
只按记忆的码匹配，匹配不到就直接跳 `原画`——用户换房间后清晰度选择被悄悄丢掉。

现在 `select()` 会把不可用的记忆码经 `resolveRate(labelFor(code), …)` 映射一遍。
`resolveRate` 本来就是干这个的，但**没有任何调用方**，只有它自己的测试在用。

断言是**逐条推演**得到的，不是猜的：

| 场景 | 结果 |
| --- | --- |
| 高清 记为 2，本房间编号 8 | → 8 |
| 标清 记为 1，本房间编号 7 | → 7 |
| 高清，房间只有 原画/标清 | → 1（关键字链走到"最高 rate"），且一定是房间真实提供的码 |

第三条我第一版断言写成 0（原画），推演后发现是 1，改的是断言而不是代码。

### 本轮踩到的坑（都是断言写错，不是实现错）

| 断言 | 实际情况 |
| --- | --- |
| `a_bogus` URL safe | 是 s4 字母表 base64，必然含 `/`、`+`、`=`；入 URL 时百分号编码 |
| `a_bogus` 确定性 | `generateRc4BbStr` 把 `System.currentTimeMillis()` 写进 payload（`b[16]`），每次调用本就不同 |
| 房间号从 `roomInfo` 块取 | 实测四个真实房间页，页面里是 `\"roomInfo\":{\"roomId\":\"\"}`——**空串**，数字 ID 必须走 API 兜底 |
| `hexToBytes` 位置 | 写在另一个测试类里，虎牙测试源集编译失败，**一个 JVM 用例都没跑** |

### 当前状态（run 114，commit `7142c1a`）

| 检查项 | 结果 |
| --- | --- |
| `:app:lintDebug` | ✅ 通过（0 error） |
| JVM 单元测试 | ✅ 通过 |
| `:app:assembleDebug` / `assembleRelease` | ✅ |
| 仪器测试（API 34 模拟器） | ✅ 通过 |
| debug APK 产物 | ✅ 27.3 MB |

### 下一轮

- **抖音握手真机验证**：socket 之前的一切都有测试覆盖，但握手最后一步只能靠实际连接确认。
- **抖音频道路径**：`rooms()` 传的 `partition` 目前是否被服务端尊重仍未验证（参考实现记录过被忽略）。
- **同步增量分页**：关注列表同步目前一次传全量。

---

## 第八轮迭代：同步计数与播放器手势

### 同步"发送条数"报的其实是序号

`SyncCoordinator` 把 `pushed` 设成 `local.sequence`，而 `sequence` 是**只增不减的去重计数器**。
于是同步摘要显示的数字与实际传输量无关：全新安装关注 3 个房间会显示"发送 1"，
同步几次后同样 3 条会显示"发送 9"。

改为读 `SyncPayload.recordCount`（解码 entries 后的条数，解码失败返回 0 而不是抛异常，
因为它只用于展示）。新增测试先消耗两个序号再同步，这样按序号算会得到 5，正确答案是 3。

这个缺陷是靠**读代码路径**发现的，不是测试报出来的——数字单独看很合理，所以它躲过了
前四轮所有同步相关的工作。

### 同步记录行原先不显示条数

`SyncLogRow` 只渲染 `note`，而成功的 push 是 `note = null`，于是一次 12 条的传输在界面上
表现为"一个时间戳，什么都没发生"。现在固定显示 `发送/接收 N 条`，并在 note 含有条数之外的
信息时才追加 `· note`。

### 播放器双击暂停

画面现在区分单击（切换控件）与双击（暂停）。暂停状态只存在于本页面并以房间为 key，
离开页面必定恢复，不会留下一个"看着像卡死"的流；暂停时画面中央显示
`已暂停 · 双击继续`，因为直播暂停后的静止画面与崩溃后的画面无法区分。
手势层只在竖屏生效，避免吞掉本该给全屏按钮的点击。

### B 站搜索不再把失败当错误抛

空关键词直接返回，不发请求；JSON 解析失败、非 2xx、`code != 0` 一律返回"没有结果"。
搜索是少数几个"报错不如空列表"的地方：抛异常会把结果网格换成用户没要求的重试按钮，
参考实现也是这个行为。

### CI 自身：仪器测试失败现在可读

`connectedDebugAndroidTest` 打一堆日志然后给一个退出码，所以模拟器红灯只报
`The process '/usr/bin/sh' failed with exit code 1`。现在把 AndroidTest 的结果 XML 转成注解，
并在"测试没跑起来"时回退到扫描 gradle 日志。

第一版有个 bug：当 XML 里没有失败时它继续走日志扫描，于是普通打包告警
（`Unable to strip the following libraries...`）在**全绿**的第 117 次运行上被标成了红色注解。
已改为只有在 gradle 本身失败或压根没有结果文件时才看日志，并本地验证了三条路径
（无输入 → 一行提示、通过 → 静默、失败 → 一条指名道姓的注解）。

### 当前状态（run 120，commit `daaaa74`）

| 检查项 | 结果 |
| --- | --- |
| `:app:lintDebug` | ✅ 通过（0 error） |
| JVM 单元测试 | ✅ 通过 |
| `:app:assembleDebug` / `assembleRelease` | ✅ |
| 仪器测试（API 34 模拟器） | ✅ 通过 |
| debug APK 产物 | ✅ |

两个 job 全绿且**没有任何错误注解**——验证了上一段那个误报已修好。



---

## 第九轮迭代：四个平台弹幕的真实网络验证

这一轮把"弹幕到底能不能用"从推测变成了实测。方法：在本机用 Node 直连各平台的弹幕
WebSocket，用真实直播间跑完整握手 + 解码，把结果和项目里的实现逐项对照。

### 结论总览

| 平台 | 结果 | 证据 |
| --- | --- | --- |
| 抖音 | ✅ **可用** | 61 帧解出 82 条真实弹幕，昵称与内容全部正确 |
| 斗鱼 | ✅ **可用** | 97 帧解出 34 条真实弹幕 |
| B 站 | ✅ **可用** | WBI 签名 code=0，认证成功，解出真实弹幕 |
| 虎牙 | ⚠️ **部分可用** | 51 帧中解出 5 条；另一类帧结构仍被丢弃（见下） |

### 抖音：握手与解码全部对得上

真实直播间（KPL王者荣耀职业联赛，room `7703137625093278464`）：

- 签名：`webmssdk.js` 在 V8 下返回干净的 16 字符 token
- socket：`webcast5-ws-web-hl.douyin.com` 打开成功，12 秒收到 41 帧 / 65 KB
- 解码：61 帧产出 **82 条真实弹幕**，另 1 条进场消息，`needAck` 在 61 帧上置位

这验证了整套移植：URL 参数集、MD5 stub、JS 引擎调用，以及每个 protobuf 字段号
（`PushFrame.payload=8`、`ChatMessage.content=3`、`User.nickname=3`、
`Response.needAck=9`）。

已把那次握手的 stub digest 钉进单测（`2affaa9a00a0cd469bcdc03f90b5a97e`），
任何 key、固定值或顺序的漂移都会在 JVM 上被抓到，不需要联网。

### 斗鱼：二进制帧修复确认有效

`loginreq` / `joingroup` 用二进制帧发出后，97 帧解出 34 条真实弹幕。这确认了上一轮
"文本帧改二进制帧"的修复是正确的——服务端在声明 `Sec-WebSocket-Protocol: binary`
的连接上不认文本帧。

### B 站：WBI 签名 + 认证链路通了

`nav` 取 wbi keys → `getDanmuInfo` 用 WBI 签名 → `code=0` → 拿到 token 与 host →
auth 包发出 → `op=8` 认证成功 → 解出真实弹幕。顺带确认了 zlib（ver=2）解压路径。

### 虎牙：找到并修掉两个真实缺陷，但还有一类帧没处理

**缺陷一：`ZERO_TAG` 被当成解析错误。**
type 12 是 TARS 表示"值为 0"的写法，对字符串就是空串。线上帧用它表示可选字段，
而 `readStringBytes` 抛 `not a string type: 12`，整条消息被 `runCatching` 吞掉。

**缺陷二：缺失的可选字段被当成协议错误。**
`requireHead` 在 `currentTag > tag` 时抛 `missing tag N (saw M)`。但 TARS 按 tag 升序
写字段、默认值字段直接省略，所以"跳过的 tag 就是不存在"。现在返回 null 并把位置回退，
让调用方还能取更大的 tag。

**仍未解决：** 捕获的那条真实帧（`威仔zzz: 只有凌晨是直播`）依然解不出来。手工走一遍：

- 内层负载以 `05` 开头——tag 0、type 5（DOUBLE），随后是 `0a`（tag 0、STRUCT_BEGIN），
  所以这条消息不是 `decodeChat` 假设的扁平 `{0:int, 1:int, 2:bytes}` 形状
- 昵称确实在 tag 2（`26 09` + 9 字节），但同一 struct 的 tag 3 是 `3c`——ZERO_TAG，空的；
  可见文本藏在更深一层，位于 tag 6 的嵌套 struct 内（`2c 36 15`）
- 帧里还有 type 8（MAP），`Tars.Reader.skip` 未实现

所以**虎牙弹幕不能算已验证可用**。tagged 长度编码是对的（raw int32 在 51 帧上全错，
tagged 解出 5 条），`ZERO_TAG` 和缺失字段的处理也是对的且有测试覆盖，但至少有一类
线上帧的 body 布局仍未处理。那 5/51 的命中率与"部分形状能用、这条不行"一致。

那条捕获帧保留为 `@Disabled` 测试，注释里写清了上面三点——它是证据，删掉就等于把
这个缺口藏起来。

### 顺带清理

- 一次 `git add -A` 把本地临时脚本 `.ci-check.py` 扫进了提交，已删除。
- CI 的 release 构建从 `verify` 里拆成独立 job 并行跑，三个 job 现在同时启动。

### 当前状态（run 129）

| 检查项 | 结果 |
| --- | --- |
| `:app:lintDebug` | ✅ 通过（0 error） |
| JVM 单元测试 | ✅ 通过 |
| Release assembly（R8） | ✅ 通过 |
| 仪器测试（API 34 模拟器） | ✅ 通过 |
| debug APK 产物 | ✅ |

三个 job 全绿，无任何错误注解。

### 下一轮

- **虎牙 body 布局**：处理 DOUBLE/MAP 类型与嵌套 struct，让捕获帧能解出来。
- **同步增量分页**：关注列表仍是一次传全量。
- **抖音推荐流分页**：`webcast/feed` 忽略 `offset` 且服务端把 `count` 压到 6，
  目前只呈现单页。

---

## 第十轮迭代：同步真正增量、播放页空白渲染

这一轮做了两件互不相关的事：把上一轮列出的"同步仍是一次传全量"做掉，以及把 CI 里
一张**已经坏掉但没人发现**的截图修好。第二件事比第一件重要。

### 一张 703 字节的截图

CI 每轮都会上传 `ui-screenshots` 产物，里面有 20 张各页面的 PNG。把它们下载下来逐张
看，`17-player.png` 只有 **703 字节**——纯黑，什么都没有。

原因是测试夹具只给了 `selectedVariant`，没给 `room`。播放页的头部、标题行、弹幕面板
全部读 `state.room`，于是什么都不渲染，只有视频表面那层黑底留着。更糟的是
`VideoSurface` 的 `when` 分支：

- `variant != null && playerError == null` → 播放器
- `state.loading` → 转圈
- `room.isLive == false` → 未开播
- `else` → **"无法播放该直播间"**

"还没有 variant、也还没有错误"这个状态（也就是刚进房间、地址还在解析的那一两秒）
掉进了最后一个分支，显示成播放失败。现在这条路径显示"正在解析播放地址…"，失败文案
只留给真的失败。

顺带把播放页拆成 `PlayerScreen`（持有设置面板）与 `PlayerContent`（纯布局，`fullscreen`
作为参数）。之前 `fullscreen` 是内部状态，外部无法构造，所以任何测试或截图都到不了
全屏形态，也无法在不经过整页的情况下渲染播放页。

### 截图现在会自己失败

截图的价值取决于"空白图会不会让构建变红"。`capture()` 原先只是写文件，所以一张纯黑图
可以安安静静地躺几十轮。现在它会在 24×24 的采样网格上统计不同颜色数，低于阈值就直接
失败并报出是哪个屏幕——纯色渲染等同于"什么都没画出来"。

新增 `17b-player-fullscreen`，夹具也补上了 room、关注态、线路列表和弹幕。

### 同步：从全量改成增量

`SyncCoordinator` 以前每个 scope 都无条件 `push` 整个集合。但一次交换的第一步就是
`pull`，而 `pull` 的响应**精确描述了对端持有什么**。于是把它当作基线：
`buildPayload(scope, peerKnown)` 只保留"对端没有"或"版本更高"的记录，比较用的正是
`SyncMerge` 那一套 `(hlc, deviceId)`，所以被跳过的记录即使发过去也会在对端输掉合并。

两个直接结果：

1. 双方一致的 scope 不再产生一次纯浪费的请求（"无需发送"）。
2. 服务端的 `onPush` 改为返回相对刚收到的负载的增量，不再把对端刚发来的东西原样回声。

超大集合按 `SyncPayload.MAX_RECORDS_PER_PAYLOAD`（500）分片。**每片单独占用一个
`sequence`** 是必须的：接收方的重放保护就是按 `sequence` 判重的，整批共用一个序号会
让第二片起全部被当成重放丢掉。

### 本轮验证

| 检查项 | 结果 |
| --- | --- |
| `:app:lintDebug` | ✅ |
| JVM 单元测试 | ✅ |
| 仪器测试（含新截图断言） | ✅ |
| Release assembly（R8） | ✅ |

新增测试：共享记录不回传、已一致的 scope 只发一次请求、12 条记录按 5 条切片得到
5/5/2 且序号互不相同、禁用 scope 不产生负载、增量双向收敛、第二次交换传输 0 条、
超过 500 条的关注列表完整同步。

### 更正一处过期文档

README 的"已知限制"里写着"斗鱼只有一页、没有三级分类"，这与代码不符：`mixListV1`
的 `2_`/`3_` 两条路径和 `getThreeCate` 都已实现并有抓包测试
（`DouyuMixListFixtureTest`）。那两条结论来自更早的、把分区 id 传错（`2_86` 而非
`2_1`）的版本，属于**记错了**而不是平台限制。已改写该节，避免把实现缺陷记成平台缺陷。

### 下一轮

- **抖音推荐流分页**：`webcast/feed` 忽略 `offset` 且服务端把 `count` 压到 6。
- **播放页逐项对照**：把参考实现的 `ScrollingDanmakuOverlay`（全屏横向滚动弹幕）与
  LiveHub 的 `DanmakuOverlay` 逐行比对，确认轨道分配与避让策略一致。
- **同步**：`pull` 仍是整包返回，可以考虑同样的分片，但目前关注列表规模下不是瓶颈。

---

## 第十一轮迭代：弹幕层的两个真实缺陷

上一轮的"下一轮"里写着"逐行比对弹幕浮层"。真去读了才发现两个缺陷，都只在**运行中的
弹幕层**里才看得到——截图冻结了时钟所以什么都还没动，纯 JVM 测试又根本不组合 UI。
这就是它们能活过九轮的原因。

### 缺陷一：新弹幕会把整个缓冲区重新加一遍

```kotlin
LaunchedEffect(messages) {
  if (messages.isEmpty()) return@LaunchedEffect
  messages.forEach { message -> flying.add(...) }   // ← 全量
}
```

`messages` 不是"新到的那条"，而是 ViewModel 的整个滚动缓冲（上限 200）。每来一条
弹幕，列表身份就变，effect 重跑，把**已有内容全部再加一次**。三条消息时：`a` 被加
3 次、`b` 2 次、`c` 1 次。真实直播间里几秒钟屏幕就糊成一片，`laneCursor` 也在不停
回绕。

修法是记一个 `consumedCount` 偏移，只处理新尾部；当缓冲区从头部被裁剪（列表比偏移
短）时把偏移归零。参考实现同样用 `lastCount` 做了这件事。

### 缺陷二：每条弹幕都是满屏宽

```kotlin
Modifier.offset(...).fillMaxSize()   // ← Text 测量成整个浮层宽
```

三个字的弹幕一开始就顶在右边缘，左移动画读起来像是"气泡在滑"而不是"文字在滚"。
去掉 `fillMaxSize()` 后按内容自然宽度排布。

### 这一轮的教训：怎么才能测到它

两次尝试都失败，值得记下来：

1. **只冻结时钟不够**。每条弹幕跑 8 秒 `Animatable`，结束即 `onFinished` 删除自己；
   `autoAdvance` 默认开着时 `waitForIdle()` 会把动画跑完，断言看到的是空浮层。
   → 先 `mainClock.autoAdvance = false`。
2. **推进一帧也不够**。状态变化后要跑完"重组 → 重新启动 effect → effect 派发 →
   列表变更再组合"这条链，`advanceTimeByFrame()` 只走了第一步，新加的 `c` 始终没有
   节点。→ 改成 `advanceTimeBy(500)`，仍在 8 秒飞行内，弹幕处于屏幕中间。

最终 `DanmakuOverlayTest` 用 Robolectric + Compose 测试规则在 JVM 上跑了三条断言：
新增不重复、关闭时不渲染、屏蔽词不进浮层。为此把 `compose-ui-test-junit4` 加进了
`testImplementation`。

### 本轮验证

| 检查项 | 结果 |
| --- | --- |
| `:app:lintDebug` | ✅ |
| JVM 单元测试（含新的浮层测试） | ✅ |
| 仪器测试 | ✅ |
| Release assembly（R8） | ✅ |

CI run 14006998 全绿。

### 下一轮

- **抖音推荐流分页**：`webcast/feed` 忽略 `offset` 且服务端把 `count` 压到 6。
- **全屏弹幕轨道避让**：当前是 round-robin 分配轨道，同轨道两条弹幕在长文本下仍可能
  重叠；参考实现用 `laneAvailableAt` 按估算宽度计算"该轨道何时空出"，值得移植。
- **同步**：`pull` 仍是整包返回。

---

## 第十二轮迭代：轨道避让、三个状态截图全是同一张、以及一次环境的锅

### 全屏弹幕轨道：从轮询改成"这条轨道什么时候空出来"

上一轮列出的第一件事。原来的分配是 `laneCursor % laneCount`——它既不知道一条弹幕有多
宽，也不知道上一条在哪儿。结果是一条长弹幕还在右侧时，后面来的短弹幕从同一条轨道进
场、追上去、重叠。这是纯轮询必然的失效方式，也是参考实现用 `laneAvailableAt` 记录
"每条轨道下次可用时刻"的原因。

现在的规则：

```
轨道下次可用 = 当前帧时刻 + round(文本宽 * 8000 / (屏宽 + 文本宽)) + 80ms
```

即"这条弹幕自己的尾巴完全越过右边缘"所需的时间。三个关键点：

1. **宽度用 `TextMeasurer` 实测，不用字符数估算**。同样长度下 `|||` 和中文的宽度差几
   倍，而估算的偏差恰好发生在重叠最严重的地方。
2. **时钟用 `withFrameMillis`**，即动画真正推进的那条时间线。用墙上时钟会和动画漂移，
   在测试的冻结/减速时钟下更会完全对不上。
3. **120ms 容差内仍可占用**，避免稳定流量每隔一个轨道周期就卡一下；**超出容差则丢弃**
   这条弹幕。丢弃是刻意的——所有主流客户端在高并发时都丢，堆上去只会让画面不可读。

顺带修了两处几何：离场点从"屏宽的 60%"改成"自身宽度"，长文本不再被砍断、短文本不再
空等；浮层加 `clipToBounds()`，弹幕不会溢出到弹幕列表区域。

调度算术抽成 `chooseLane` / `laneFreeAfterMs` 两个纯函数，`DanmakuLaneSchedulingTest`
直接在 JVM 上覆盖（8 条断言，包括容差边界与零宽回退），不需要组合 UI。

### 三个截图是同一张

对比产物时按**字节数**分组，发现 `18-player-loading.png` 和 `19-player-error.png`
都是 **33412 字节**——完全一样。去看图：两张都显示"视频画面（截图占位）"，既没有加载
转圈，也没有"主播未开播"。

原因是上一轮我为截图加的 `videoSurface` 替身被**无条件**绘制：

```kotlin
if (videoSurface != null) videoSurface(...) else VideoSurface(...)   // ← 无条件
```

而加载/未开播/错误这三个状态存在的意义就是"没有画面"。替身把它们原生的内容全盖掉了，
于是我上一轮"修好了播放页渲染"实际上只修好了**播放中**那一种状态。

现在只有真正在播（有 variant、不在 loading、没有 playerError）时才替换：

```kotlin
if (videoSurface != null && isPlaying && playerError == null && effectiveVariant != null)
```

三个状态随之恢复：加载显示"正在解析播放地址…"、错误显示消息 + 重试/返回、未开播显示
"主播未开播 / 当前直播间没有在直播" + 返回。截图字节数从 33412/33412/— 变成
22965/25386/27043，两两不同。

**教训**：只看"有没有文件"或"文件名"是不够的，得看字节数是否撞车、并且真的打开看图。

### 一次不怪代码的失败

中间有一轮 `Build & test` 红了，报错是：

```
initializationError: Failed to fetch maven artifact
  org.robolectric:android-all-instrumented:14-robolectric-10818077-i7
```

Robolectric 首次运行时会从 Maven Central 下载它的 Android 运行时 jar，下载发生在测试
JVM 内部。一次瞬时网络失败就会让**所有** Robolectric 测试挂掉，看起来和代码错误一模一
样——那一次 `Instrumented tests` 是绿的，说明代码没问题。

CI 的测试步骤现在先单独跑 `DanmakuOverlayTest` 最多三次把缓存喂热，再跑全量。若三次
都失败仍然继续跑全量并由它如实报告，所以这一步不会掩盖真实问题。

### 顺带核对的两件事

- **虎牙搜索分页可用**。实测 `search.cdn.huya.com` 四页（start=0/20/40/60）房间 id
  **两两零重叠**，`numFound=1741`。`rows` 参数被服务端忽略（要 20 给 40），但 `start`
  被正确采纳，所以"下一页"是对的。不是缺陷。
- **抖音推荐流单页是已知的平台限制**：`webcast/feed` 忽略 `offset`、`count` 被压到 6，
  代码里已明确降级为 `nextCursor = null` 并在 UI 显示"已经到底了"。这一项从待办里去掉。

### 本轮验证

| 检查项 | 结果 |
| --- | --- |
| `:app:lintDebug` | ✅ |
| JVM 单元测试（含 8 条轨道调度断言） | ✅ |
| 仪器测试（23 张截图，无重复） | ✅ |
| Release assembly（R8） | ✅ |

CI run 16578192 全绿。

### 下一轮

- **玩家页设置抽屉**：参考实现在横屏全屏时用左侧滑出抽屉，LiveHub 统一用底部弹层；
  需要判断横屏下底部弹层是否够用（高度受限）。
- **同步**：`pull` 仍是整包返回。
- **视觉细节**：逐张打开 23 张截图做一轮对照。

---

## 第十三轮迭代：两个"看得见但没人看"的布局缺陷

这一轮做的正是上一轮列出的两件事：横屏设置面板，以及逐张打开截图做视觉对照。两件事
各抓到一个布局缺陷，都属于**渲染出来但不可用**——单元测试不会失败，截图断言也不会
失败（因为它们只检查"有没有内容"）。

### 设置面板在横屏下有一半选项够不着

`PlayerSettings` 是一个 `Column`，没有任何滚动：

```kotlin
Column(modifier = Modifier.fillMaxWidth()...) {
  SettingsLabel("清晰度"); FlowRow { ... }
  SettingsLabel("线路");   FlowRow { ... }
  ...弹幕开关 / 显示区域 / 字号 / 透明度 / 横屏弹幕字号
}
```

竖屏时底部弹层能拿到大半个窗口，勉强塞得下；横屏时弹层只有很短的高度，而内容一行都
不少。超出的部分不只是"看不见"——**没有滚动，也就没法拖进视野**，等于这些设置根本改
不了。

两处修改：

1. 内容列加 `verticalScroll(rememberScrollState())`。单这一条就让任何窗口尺寸下每一行
   都可到达。
2. 横屏时改用左侧抽屉（`PlayerSettingsDrawer`）而不是底部弹层，从长边取高度。参考实现
   出于同样原因做了同样的切换。

`PlayerSettings` 从 `private` 改成 `internal`，这样 `PlayerSettingsTest` 能在横屏尺寸下
组合它并调用 `performScrollTo` —— 这个 API 在没有可滚动祖先时会**失败**，所以它断言的
正是"真的能滚"，而不是"节点在树里"（节点一直在树里，这正是缺陷本身）。

测试用 `@Config(qualifiers = "w640dp-h360dp-land")` 让 `LocalConfiguration` 报告横屏，
从而连横屏专属那一行也一并覆盖。

### 同步页第三个 chip 被挤成竖排文字

逐张打开截图时发现 `13-sync.png` 里"播放与弹幕设置"是一列竖着的单字。原因是
`ScopeCard` 用了普通 `Row`：

```kotlin
Row { SyncScope.entries.forEach { FilterChip(...) } }
```

三个标签是 4、4、7 个字，在 320dp 宽度下超过一行。`Row` **不会换行**，它会压缩子项，
于是最后一个 chip 的文本被压到一个字符宽。改成 `FlowRow` 自动换行，并给 label 加
`maxLines = 1, softWrap = false`，将来的长名称也不会重演。

顺带核查了另外两处 chip 行（首页平台栏、播放设置），它们本来就用 `FlowRow`，所以只有
同步页有这个缺陷。

### 方法上的收获

这两个缺陷都是"要么打开图看，要么永远发现不了"。上一轮我加了纯色检测，它能抓
"整屏空白"，但抓不到"布局塌陷"。已经能自动抓到的有两类：

| 能自动抓 | 手段 |
| --- | --- |
| 整屏空白 | 全像素不同颜色数 < 12 |
| 两个状态渲染成同一张 | 按字节数分组，撞车即可疑 |

还抓不到的是"内容溢出/被压扁"。这一轮靠人眼发现；把 `performScrollTo` 这类断言用起来
是更靠谱的方向（它对"够不着"会失败，对"看得见"才通过）。

### 本轮验证

| 检查项 | 结果 |
| --- | --- |
| `:app:lintDebug` | ✅ |
| JVM 单元测试（含 `PlayerSettingsTest` 3 条） | ✅ |
| 仪器测试（23 张截图） | ✅ |
| Release assembly（R8） | ✅ |

CI run 18309181 全绿。`13-sync.png` 从 48484 字节变为 54060 字节，chip 已正常横排换行。

### 下一轮

- **同步 `pull` 分页**：仍是整包返回，和上一轮给 `push` 做的一样。
- **更多"够得着"断言**：给其他长列表页面（设置页、同步页日志）加同类测试。
- **视觉对照**：剩余截图（1-6、9-10、12、16、20）尚未逐张检查。

---

## 第十四轮迭代：pull 分页收尾，以及一个 151MB 的下载为什么要挪到 Gradle

### pull 也开始分片

`push` 两轮前就按 500 条分片了，`pull` 却仍把整个 scope 塞进一个响应。现在 `pull`
端点接受 `chunk` 参数，`LanSyncTransport.pull` 循环取回各片并在本地拼成一个 payload，
所以传输层以上的代码完全无感。不带 `chunk` 时返回第 0 片，老的调用方式照旧能用。

**一处诚实的限制**（写进代码注释而不是糊过去）：每片都是重新快照的，两次分片请求之间
若有记录增删，分片边界会漂移，某条记录可能被传两次或漏掉。重复无害（合并按 key 幂等），
漏掉的会在下次同步补回；而且只影响超过 500 条的 scope。要做到严格原子需要在协议里加
快照令牌，对这个量级不值得。`buildPullPayload` 对越界 chunk 做钳制而不是报错。

### 三次 CI 红灯，其实都是同一个 151MB 文件

连续三轮 `Build & test` 红，报错都是：

```
initializationError: Failed to fetch maven artifact
  org.robolectric:android-all-instrumented:14-robolectric-10818077-i7
```

上一轮我加过"预热缓存 + 重试三次"，这一轮看到三次预热**全部**失败。先确认这个坐标是
真的：HTTP 200，**151,142,227 字节**。

问题不在网络，在**下载位置**：Robolectric 是在测试 JVM 内部、类初始化时去 Maven Central
拉这个 jar，落到 `~/.m2` —— 而 CI 里没有任何缓存持久化那个目录。于是每个干净的 runner
都要在测试启动阶段重新下 151MB，任何一次慢或失败就同时打挂所有 Robolectric 测试。

Gradle 不同：它的依赖缓存由 `gradle/actions/setup-gradle` 跨 run 持久化。所以改成由
Gradle 解析这个 jar（`robolectricRuntime` 配置 + `copyRobolectricRuntime` 任务），再用
`robolectric.offline=true` + `robolectric.dependency.dir` 指过去。坐标万一解析不到，
构建会以一条点名该坐标的 Gradle 解析错误失败，比 N 个 `initializationError` 好排查得多。

仓库里三个 Robolectric 测试都固定 `@Config(sdk = [34])`，一个 jar 够用。

### 顺带修掉的编译错误

上一轮 pull 分页的测试里写了 `assertTrue("msg", cond)` ——那是 JUnit 4 的参数顺序，而本
项目的 JVM 测试用的是 Jupiter，message 在最后一个参数。改成 `assertTrue(cond, "msg")`。

### 本轮验证

| 检查项 | 结果 |
| --- | --- |
| `:app:lintDebug` | ✅ |
| JVM 单元测试（Robolectric 离线，含 pull 分页与钳制断言） | ✅ |
| 仪器测试 | ✅ |
| Release assembly（R8） | ✅ |

CI run 21172066 全绿。新增测试：空手机 / 满平板的分片拉取（512 条）、越界 chunk 钳制。

### 下一轮

- **"够得着"断言**：设置页、同步页日志等长列表，用 `performScrollTo` 覆盖。
- **视觉对照**：剩余截图（1-6、9-10、12、16、20）尚未逐张检查。
- **`pull` 原子性**：如需严格一致，加快照令牌。

---

## 第十五轮迭代：播放页去掉顶部那排，以及一个"声明了但根本不会触发"的手势

需求很直接：播放页顶部那一排（返回、星标关注、X）都不要了，靠左滑返回，关注用下面
那个按钮就够。

### 那排控件本身就是冗余的

- **返回**和 **X** 调的是同一个 `ClosePlayer`。一个屏幕上有两个入口做同一件事，而
  第三个入口（系统返回手势、`LiveHubRoot` 里的左滑）本来就能用。
- **星标**和下面 `RoomActionRow` 里的「关注」按钮也是同一件事，而带文字的按钮明显
  更容易被找到。
- 那排胶囊里还有主播名、开播圆点、在看人数——这些不是装饰，所以没有跟着容器一起
  消失，而是搬进了下面的 `RoomActionRow`（抽成 `StreamerLine`，全出血布局共用）。

竖屏普通布局现在视频直接贴在顶部，竖屏视频流原本就是这个布局，现在两种情况一致了。

### 但删掉顶部栏之前，先发现"左滑返回"其实从来没工作过

这是本轮真正的收获。`LiveHubRoot` 里那段手势的注释写着"从左边滑是用户预期的返回
手势"，实现是：

```kotlin
detectHorizontalDragGestures { change, dragAmount ->
  if (dragAmount > SWIPE_THRESHOLD_PX) onAction(UiAction.ClosePlayer)
}
```

`dragAmount` 是**相对上一帧**的位移，不是整次手势的累计位移。拿它去和 180px 比，
人手给不出这种单帧位移，所以这个手势一次都不会触发。第二个问题：一旦真的超过，它在
**之后的每一帧**都会再触发一次，一次滑动会连发几十个 `ClosePlayer`。

如果先删了顶部栏再发现这件事，竖屏下就只剩系统返回手势了。所以顺序反过来了：先修
手势，再删按钮。

修法：累计位移对阈值，回调只发一次；并且要求手势**从左侧边缘附近起手**（宽度 8%），
否则画面中间的水平拖动是在拖视频，不是返回。

还有一个坑：`pointerInput(Unit)` 会让手势块捕获初始化时的 `widthPx`（0），边缘区就
永远算不出来。改成 `pointerInput(widthPx)`，宽度变了重建。

### 删掉顶部栏带来的 inset 问题

`MainActivity` 用了 `enableEdgeToEdge()`。原来视频靠顶部那排控件把它和状态栏隔开，
控件没了之后视频会画到状态栏和挖孔下面。所以：

- 竖屏普通布局：视频加 `windowInsetsPadding(WindowInsets.statusBars)`；
- 弹幕列表加 `WindowInsets.navigationBars`，否则最后一行被导航栏盖住；
- 全屏和竖屏全出血布局**故意不加**——那两种就是要铺满屏幕。

### 本轮验证

新增 `BackSwipeContainerTest`（4 条）：左边缘滑动会关闭、一次滑动只关一次、短滑动
不关、从屏幕中间起手不关。这个缺陷"看截图看不出来，因为界面上什么都没错"，只能靠
真的拖一下并数回调次数。

| 检查项 | 结果 |
| --- | --- |
| `:app:lintDebug` | ✅ |
| JVM 单元测试（含 `BackSwipeContainerTest`） | ✅ |
| 仪器测试 | ✅ |
| Release assembly（R8） | ✅ |

### 下一轮

- **全屏栏的星标**：横屏全屏时下面没有关注按钮，星标仍然需要，暂时保留。
- **"够得着"断言**：设置页、同步页日志等长列表，用 `performScrollTo` 覆盖。
- **视觉对照**：剩余截图尚未逐张检查。

---

## 第十六轮迭代：弹幕"往上刷"，以及排名第一和第二的写法互相抵消

反馈很短："弹幕怎么是往上刷的"。

### 两个错误单独看都讲得通，合起来就是倒退

`DanmakuList` 当时是这样：

```kotlin
val display = remember(messages) { messages.asReversed() }  // 新的在最上面
LaunchedEffect(display.size) {
  if (display.isNotEmpty()) listState.animateScrollToItem(0) // 每次滚回顶部
}
```

- `asReversed()`：把最新的放在索引 0，注释写着"chat flows downward from the top"。
- `animateScrollToItem(0)`：每来一条就滚到顶。

分开看，两者都指向"最新的在最上面"。合起来却是：新消息插到顶上 → 下面所有行**整体
下移** → 下一帧又被滚回顶部、整体**上移**。屏幕上看到的是旧内容往上走，也就是倒着刷。

真正的顺序问题不在于"新的放上面"，而在于**视口每帧被人为拉回**。直播客户端的惯例
是旧的在上、新的从底部追加、视口停在尾部，这既符合"往下刷"的直觉，也不需要任何
反向滚动。

### 修的时候又踩到两个坑

1. **`layoutInfo` 在 composition 里读的是上一帧**。第一版把"是否在底部"写在 effect 里，
   列表一旦超过一屏就永远判定"不在底部"，跟随尾部直接失效——正好在列表变长之后。
   改用 `derivedStateOf` 观察实时布局。
2. **精确判断会差一条**。新消息到达时列表已经比上次布局长一格，`lastVisible ==
   lastIndex` 对"该滚动的这一条"永远判 false。判断放宽一行。

### 本轮验证

新增 `DanmakuListOrderTest`（3 条）：旧的在新的上方（直接比较 `boundsInRoot.top`）、
新消息到达后仍然可见、屏蔽词不渲染。断言的是**顺序**而不是动画，因为缺陷是"排序和
滚动同时错"才显形的。

| 检查项 | 结果 |
| --- | --- |
| `:app:lintDebug` | ✅ |
| JVM 单元测试（含 `DanmakuListOrderTest`） | ✅ |
| 仪器测试 | ✅ |
| Release assembly（R8） | ✅ |

### 下一轮

- **全屏横向弹幕的观感**：`DanmakuOverlay` 的飞行速度固定 8 秒，长弹幕会显得偏慢。
- **"够得着"断言**：设置页、同步页日志等长列表，用 `performScrollTo` 覆盖。

---

## 第十七轮迭代：三级分类的翻页和刷新悄悄退回了二级分类

### 缺陷是什么

斗鱼是唯一有三级分类的平台（`cate2` 下面还有一层 `cate3`）。选中三级分类之后：

- **往下滚动加载更多**，追加进来的是整个二级分类的房间——顶部还写着"峡谷之巅"，
  下面却混进了英雄联盟其它子分类的直播间；
- **下拉刷新**更彻底，直接把三级分类丢掉，列表变回二级。

两个操作都不报错，列表也"有内容"，所以空态截图发现不了，`hasVisibleContent` 那种
"是不是纯色"的断言同样发现不了。

### 为什么会这样

`selectSubCategory` 是故意**不**替换 `selectedCategory` 的——父级 chip 要保持高亮，
否则用户在两级选择器里就找不到自己在哪（`MainViewModel.kt` 那句注释
"keeping the parent selected"）。于是三级分类选中后，`selectedCategory` 仍然是父级。

`loadMore()` 和 `refresh()` 却都直接读了 `selectedCategory`：

```kotlin
private fun loadMore() {
  val category = current.selectedCategory ?: return   // ← 永远是父级
  ...
}
```

第一页是对的，因为 `selectSubCategory` 走的是 `loadRooms(platform, category)`，传进去的是
三级分类本身；只有**第二页起**和**刷新**会退回。

参考项目 `dtv_mobile` 的
`shared/src/commonMain/kotlin/dtv/mobile/ui/screens/douyu/DouyuHomeScreen.kt` 里 `loadPage`
每次都重新解析最深的选中项：

```kotlin
val result: PagedResult<Streamer> = if (selectedCate3 != null) {
  appState.repo.fetchDouyuLiveListByCate3(cate3Id = selectedCate3!!.id, page = (page + 1).coerceAtLeast(1), limit = PAGE_SIZE)
} else {
  appState.repo.fetchDouyuLiveListByCate2(cate2Id = cate2.id, offset = page * PAGE_SIZE, limit = PAGE_SIZE)
}
```

它的刷新（`PullToRefreshBox` 的 `onRefresh`）调的也是 `loadPage(reset = true)`，走同一条分支。
差别就在这里：参考项目**每页都判断一次**，我们只在进入分类时判断了一次。

### 怎么修的

抽出 `activeCategory(state) = state.selectedSubCategory ?: state.selectedCategory`，
`loadMore()` 与 `refresh()` 都改走它。生产代码只动了这两处加一个私有函数。

### 测试为什么断言 URL 而不是 state

两种情况下 `UiState` 长得一模一样：`selectedCategory` 都是父级、`selectedSubCategory`
都是三级分类。错的只是**发出去的那个请求**，所以 `CategoryPaginationTest` 断言真实 URL。

为此给 `AppContainer` 加了 `adaptersOverride` 构造参数——这个类的注释本来就写着
"Swapping an implementation in a test is a constructor call"，只是之前没留这个口子。
测试里装的是**真实的 `DouyuAdapter`** 配一个记录 URL 的假 `HttpClient`，因此断言顺带覆盖了
`c3:` 前缀 → `mixListV1/3_<id>` 这层映射：

| 测试 | 断言 |
| --- | --- |
| 第一页就用三级分类 | `mixListV1/3_3683/1` |
| 翻页仍在三级分类内 | `mixListV1/3_3683/2`（旧代码是 `2_1/2`） |
| 刷新仍在三级分类内 | `mixListV1/3_3683/1`（旧代码是 `2_1/1`） |

三条在旧代码上都会红。假响应必须返回满 `PAGE_SIZE` 条房间，否则
`DouyuApi.rooms` 判定"没有下一页"，`LoadMore` 根本不会发请求，测试会假绿。

### 顺带纠正一件之前记错的事

`ci.py shots` 会把**字节数相同**的两张图标成 `<-- identical to ...`。本轮
`03-home-pinned.png` 与 `01-home.png` 都是 80037 字节，看起来正好像"状态根本没渲染"。
把 23 张图逐个算 SHA256 之后：**23 个哈希两两不同，没有任何两张真的一样**。
`ci.py` 比的是 `os.path.getsize`，不是内容：

```python
size = os.path.getsize(os.path.join(root, name))
dup = seen.get(size)
```

`01` 和 `03` 只是"选中的 chip 从英雄联盟换成了绝地求生"，图案结构一样，压缩后字节数
恰好相等。所以"字节数相同 = 真缺陷"这条经验在本轮不成立，得自己再核一遍哈希。

### 本轮验证

| 检查项 | 结果 |
| --- | --- |
| `:app:lintDebug` | ✅ |
| JVM 单元测试（含 `CategoryPaginationTest` 3 条） | ✅ |
| 仪器测试 | ✅ |
| Release assembly（R8） | ✅ |

CI run 41559431300 三个 job 全绿（commit `ea3d062`）。

顺手清掉全仓库最后一条调试输出：`DouyuScriptSigningTest` 里的 `println("signed=...")`，
它旁边的注释说这是为了失败时能一眼看出签名形状，可紧随其后的 `assertEquals` 已经把
`$signed` 写进失败消息了，那行只是往 CI 日志里多刷一行。

截图：23 张与上一轮**逐字节相同**。这一轮改的是滚动时才发出的请求，界面本来就不该变，
这个"没变"本身就是证据——没有顺手把首页改坏。逐张看过
01/02/03/04/05/06/09/10/12/20/21。

### 下一轮

- **截图里渲染了三个不可能出现的状态**（本轮看截图时发现，都还没修）：
  - `03-home-pinned` 名字里的"置顶"根本不存在——`HomeScreen.kt` 的注释写着
    "Nothing here is pinnable"，那张图证明的只是"换了一个 chip 选中"；
  - `20-huya-home` 和 `21-bilibili-home` 都只换了 `platform` 与 `categories`，没换
    `selectedCategory`，于是渲染出"平台是虎牙、选中的却是一个斗鱼分区"的状态：
    20 里那个"网游"chip 因此没有任何选中，21 里则是组选中了、分类一个都没选中。
    真机上不可能出现。
- **首次进入没有三级分类行**：`loadCategories` 只设 `selectedCategory` 和房间，不拉
  `subCategories`（只有 `selectCategory` 会拉），所以启动或切平台后要等用户点一下分类
  chip，第三行才出现。
- **全屏横向弹幕速度固定**（runbook 待办第 2 条）：`DanmakuOverlay` 飞行时长写死 8 秒。
  注意参考项目的 `ScrollingDanmakuItem` 同样写死 9000ms，所以这条不能拿"参考项目这么做"
  当依据，得自己判断要不要按字数换算。
- **"够得着"断言**：设置页、同步页日志等长列表仍缺 `performScrollTo` 覆盖。

## 第十八轮迭代：三级分类行首屏就该在，而且要能一键退回上一级

上一轮的"下一轮"列了四件事，其中三件是同一块地方——首页的分区选择器。这一轮把它们
一起做掉，因为它们其实是同一个问题的三个面：**三级分类这条路径从来没有被完整地设计过**。

### 缺陷是什么

两个，都只在斗鱼出现（四个平台里只有它发布三级分类）。

**一、冷启动没有三级分类行。** 打开 App，斗鱼首页是"网游 / 手游 / 娱乐"一行、
"英雄联盟 / 绝地求生 / DNF"一行，房间列表也是英雄联盟的，但第三行
（峡谷之巅、云顶之弈……）根本不存在。想按三级分类筛选，唯一的办法是
**再点一下已经高亮的"英雄联盟"**——一个看起来完全无效的动作。

**二、进了三级分类就出不来。** 点开峡谷之巅之后，第三行有选中态，但：

- 没有任何"全部"能退回整个英雄联盟；
- `CategoryRow` 里第二行 chip 的选中条件是

  ```kotlin
  selected = state.selectedCategory?.key == category.key &&
    state.selectedSubCategory == null
  ```

  所以父级 chip **失去高亮**。

用户看到的是：第三行选中了峡谷之巅，第二行一个都没选中。"回到整个英雄联盟"这件事在
界面上没有任何可见控件——只能靠"猜"到去点那个看起来没选中的父级 chip。

顺带暴露一个内部矛盾：`MainViewModel` 的 `activeCategory` 注释写着
"the parent chip has to stay selected, otherwise the user loses their place"，
而 UI 层恰恰把它取消选中了。两处对同一件事的描述相反，说明写其中一处时没看另一处。

### 依据

`E:\app\_ref\dtv_mobile\shared\src\commonMain\kotlin\dtv\mobile\ui\screens\douyu\DouyuHomeScreen.kt`

**一、三级分类跟着二级分类走，初始选中也算。**

```kotlin
LaunchedEffect(Unit) {
  ...
  selectedCate1 = savedCate2?.first ?: data.cate1List.firstOrNull()
  selectedCate2 = savedCate2?.second ?: selectedCate1?.cate2List?.firstOrNull()
}

LaunchedEffect(selectedCate2?.id) {
  val cate2Id = selectedCate2?.id ?: return@LaunchedEffect
  cate3List = appState.repo.fetchDouyuThreeCate(cate2Id)
  selectedCate3 = null
}
```

第二个 `LaunchedEffect` 的 key 是 `selectedCate2?.id`，而 `selectedCate2` 在
`LaunchedEffect(Unit)` 里就被赋了初值，所以**初始选中同样会触发拉取**。我们的
`loadCategories` 自己选了第一个分区却只拉房间，把"选中"和"拉三级分类"拆成了两条路径，
只有用户点击那条会走后者。

**二、第三行以"全部"开头，选中值可以为 null。**

```kotlin
@Composable
private fun Cate3Chips(
  cate3List: List<DouyuCate3>,
  selectedId: String?,
  onSelect: (DouyuCate3?) -> Unit,
) {
  LazyRow(...) {
    item {
      CategoryPill(label = "全部", selected = selectedId == null, onClick = { onSelect(null) })
    }
    items(cate3List, key = { it.id }) { c3 -> ... }
  }
}
```

`selectedId: String?` + `onSelect: (DouyuCate3?) -> Unit`：三级分类的选中值天生可空，
null 表示"整个二级分类"。我们的 `UiAction.SelectSubCategory` 收的是非空 `Category`，
从类型上就堵死了这条路。

### 怎么修的

生产代码动了三处：

1. 抽出 `loadSubCategories(platform, category)`，`loadCategories`（首屏与切平台）和
   `selectCategory`（用户点击）都调它。这样"谁选的分类"不再决定"要不要拉三级"。
2. `UiAction.SelectSubCategory(category: Category?)` 改成可空，`selectSubCategory` 里
   null 表示退回父级：`loadRooms(platform, category ?: parent, reset = true)`。
   已有的 `activeCategory(state) = selectedSubCategory ?: selectedCategory` 天然支持这个语义，
   不用改。
3. `HomeScreen` 去掉第二行那个 `&& state.selectedSubCategory == null` 条件，并在第三行
   前面加一个"全部"chip（`item(key = "sub-all")`）。

### 踩了什么坑

**一、`loadRooms` 会取消自己的父协程。** `loadCategories` 的整个函数体跑在 `roomsJob` 里，
而 `loadRooms` 第一行就是 `roomsJob?.cancel()`——等于在协程内部取消自己。之前能跑，是因为
取消之后没有新的挂起点，函数体直接走完。这也正是上一轮把三级分类放进独立
`subCategoriesJob` 的原因；`loadSubCategories` 用 `viewModelScope.launch`，是
`viewModelScope` 的子协程而不是当前协程的子协程，所以 `roomsJob` 被取消不会带走它。
调用顺序仍然是先 `loadSubCategories` 再 `loadRooms`，避免以后有人在这中间加挂起点。

**二、测试要等两个协程，不能只等房间列表。** 三级分类和房间是两条独立的协程，只等
`!state.loading` 会跟三级分类赛跑，结果取决于哪个请求先回来。所以新增的
`awaitFirstLoadWithSubCategories` 把三个条件并在一起等，超时消息也能直接看出缺哪个。

**三、`ci.py shots` 的 "identical to" 又骗了一次。** `01-home.png` 与
`03-home-other-category.png` 都是 80037 字节，被标成 `<-- identical to`。逐个算 SHA256：
24 张两两不同。上一轮已经把这条写进记录（`ci.py` 比的是 `os.path.getsize`），本轮再次
复现，说明这不是偶发。

### 本轮验证

| 检查项 | 结果 |
| --- | --- |
| `:app:lintDebug` | ✅ |
| JVM 单元测试（437 条，0 失败） | ✅ |
| 仪器测试（24 张截图） | ✅ |
| Release assembly（R8） | ✅ |

CI run **41562103607**，commit `7586cd3`，三个 job 一次通过。

新增/改动的测试（从 CI 的 `test-reports` 产物里核对过，确认真的跑了）：

| 测试类 | 条数 | 断言什么 |
| --- | --- | --- |
| `SubCategoryFirstLoadTest`（新） | 3 | 首屏就有三级行；拉的是当前选中分区的 `tag_id`；切平台往返后能恢复 |
| `CategoryPaginationTest` | 4（+1） | 点"全部"之后请求回到父级 `mixListV1/2_1/1` |

关于"这些测试在旧代码上会不会红"，要说准确：

- `SubCategoryFirstLoadTest` 的三条**在旧代码上必定超时失败**——旧代码里
  `loadCategories` 从不调用 `subCategories()`，`state.subCategories` 永远是空的，
  `awaitFirstLoadWithSubCategories` 5 秒后断言失败。这是构造上的必然，不是猜测；
  但本机不能跑 Gradle，我没有真的看着它红。
- 关于"全部"那条**无法在旧代码上存在**：旧的 `SelectSubCategory` 收非空 `Category`，
  `SelectSubCategory(null)` 编译不过。所以它是一条跟着 API 一起长出来的能力测试，
  不是对未改代码的回归测试——这点不该含糊过去。

### 截图里看到了什么

24 张，逐张比对上一轮（`r17` → `r18`）：**只有本轮打算改的 4 张变了，其余 20 张逐字节相同**。

- 新增 `03b-home-subcategory`：第三行终于有画面了——`全部`（未选中）、`峡谷之巅`
  （选中，浅蓝底）、`云顶之弈`、`排位赛`（右边缘裁切，说明是可横向滚动的 LazyRow）。
  **并且第二行的"英雄联盟"仍然是高亮态**，这正是第二个缺陷修好之后的正确样子。
- `03-home-other-category`（原 `03-home-pinned`）：与旧文件 **SHA256 完全相同**，
  证明这次只是改名，没有顺手改动渲染内容。图上第二行高亮的是"绝地求生"，
  与"换了一个非默认分区"这个新名字相符。
- `20-huya-home`：修好了。虎牙 chip 选中，组行"网游 / 娱乐"里网游选中，
  分类行"英雄联盟"选中、旁边"王者荣耀"，两张卡都带"虎牙"角标。
  旧版这里**一个 chip 都没选中**。
- `21-bilibili-home`：修好了。哔哩哔哩 chip 选中，组行"网游 / 手游"里网游选中，
  分类行"英雄联盟"选中、旁边"DOTA2"。旧版是"组选中了、分类一个都没选中"。
- 其余 20 张（首页、空态、错误态、搜索、同步、设置、播放器全系）逐字节未变，
  说明这轮没有碰到别的界面。

顺带记一个**密度问题**，本轮没动：斗鱼首页有三级分类时是四行 chip
（平台行在 320dp 宽下还会折成两行），占掉约 200/640 的竖向空间，只剩不到两排卡片。
参考项目 `screenshots/douyu.jpeg` 里平台放在**底部导航**、分类收在"选择分类"弹层里，
只留一行 chip，所以它更紧凑。我们的底部导航已经给了首页/关注/搜索/同步/设置，
这个取舍不是一行代码能改的，留作后面的审计项。

### 下一轮

- **全屏横向弹幕速度固定**（runbook 待办第 2 条）：`DanmakuOverlay` 飞行时长写死 8 秒。
  参考项目 `ScrollingDanmakuItem` 同样写死 9000ms，所以这条**不能**拿"参考项目这么做"
  当依据，得自己判断"等速"还是"等时长"更合理，并把理由写清楚。
- **"够得着"断言**（runbook 待办第 3 条）：设置页、同步页日志等长列表仍缺
  `performScrollTo` 覆盖，横屏或小屏下可能有控件够不着而测试全绿。
- **首页 chip 密度**：见上一节。若要动，先想清楚平台切换器放哪儿。
- 审计项 4-10（斗鱼/虎牙/抖音/B站 的协议细节、搜索页密度、直播页横竖屏与手势、
  跨设备同步的配对与增量传输）仍全部未做。

## 第十九轮迭代：弹幕按速度飞，不按秒数飞

### 缺陷是什么

全屏（横屏、或竖屏全出血）弹幕层里，**每一条弹幕都在屏幕上待同样的 8 秒**，不管它有多长。
因为行程是"屏宽 + 自身宽度"，时长却固定，速度就成了字数的函数：

| 弹幕 | 宽度 | 旧速度 | 每秒经过固定点的字数 | 旧时长 | 新时长 |
| --- | --- | --- | --- | --- | --- |
| 3 字 | 0.14 屏宽 | 0.142 px/ms | 3.1 | 8 s | 6.3 s |
| 10 字 | 0.45 屏宽 | 0.182 px/ms | 4.0 | 8 s | 8.0 s |
| 30 字 | 1.36 屏宽 | 0.296 px/ms | 6.5 | 8 s | 13.0 s |

（基准：一屏放得下 22 个汉字、16sp）

短弹幕慢吞吞地爬，长弹幕被拖着飞，两者差一倍多。用户看到的不是"弹幕快慢一致"，
而是"越长的弹幕越赶"。

### 为什么会这样

`DanmakuOverlay.kt` 里 `FLIGHT_MILLIS = 8_000` 同时喂给了两处：`DanmakuLine` 的 `tween`
时长，和 `laneFreeAfterMs` 的轨道交接。但每条弹幕的行程是 `屏宽 + 自身宽度`，不是常数。
行程变、时长不变 ⇒ 速度变。

**顺带纠正 runbook 里的诊断**：待办第 2 条写的是"长弹幕在屏幕上显得很慢"。按上面的算术
其实是**反的**——固定时长下行程越长速度越高，所以长弹幕更快、短弹幕更慢。这条我在
这里改成算得出来的说法，因为迭代记录不该复述一个和代码对不上的诊断。要修的东西
（写死的 8 秒）是同一个。

### 参考项目怎么做的，以及为什么不能直接抄

`_ref/dtv_mobile/shared/src/commonMain/kotlin/dtv/mobile/ui/screens/PlayerScreen.kt`：

```kotlin
// ScrollingDanmakuItem, 1399-1404 行
animationSpec = tween(durationMillis = 9000, easing = LinearEasing)
```

确实也写死了 9000ms。但它的调用点 `ScrollingDanmakuOverlay`（1366-1381 行）传的是
`startX = widthPx, endX = -widthPx`——**行程恒为 2×屏宽**，与文字宽度无关。所以参考项目
**是等速的**，只是把等速写成了"等距离 + 等时长"。它不能当作"等时长"的依据。

（参考项目这样写还有个副作用：比屏还宽的弹幕在动画结束时尾巴还没走完，会突然消失。
我们没有走这条路，因为我们的行程本来就按真实字宽算。）

我们的行程随字宽变，所以时长必须推导出来。

### 怎么修的

新增纯函数：

```kotlin
internal fun flightDurationMs(textWidthPx: Float, screenWidthPx: Float): Long {
  val screen = screenWidthPx.coerceAtLeast(1f)
  val travel = screen + textWidthPx.coerceAtLeast(1f)
  return ((travel / screen) * MILLIS_PER_SCREEN_WIDTH).toLong().coerceAtMost(MAX_FLIGHT_MILLIS)
}
```

- `MILLIS_PER_SCREEN_WIDTH = 5_500f`：速度用"走完一个屏宽要多久"表达，手机和平板手感一致，
  不随像素密度漂移。
- 校准：十字弹幕约 0.45 屏宽 ⇒ 1.45 × 5500 = 7975ms，**和旧行为基本一致**。也就是说
  这次改动不会让"典型弹幕"突然变快或变慢，只把两头拉开——这是刻意的，改速度感知最怕
  整体平移。
- `MAX_FLIGHT_MILLIS = 15_000`：不设上限的话，一条 100 字的长文会占住一条轨道半分钟，
  高峰期轨道被占满就只能丢弹幕，整个弹幕层会被一条广告饿死。超过约 40 字的弹幕会碰到
  这个上限，比基准稍快，但不会被截断。
- `laneFreeAfterMs` 改用**这一条自己的时长**。轨道在它尾巴让开入口的那一刻就能复用，
  那是它自己飞行时间的一个比例。原来用同一个 8 秒算，长弹幕的轨道会提前约 1.6 秒放行，
  后一条直接叠上去。
- `FlyingDanmaku` 增加 `flightMillis` 字段，`DanmakuLine` 用它而不是全局常量。

### 踩到的坑

**一、JVM 上根本量不出字宽。** 本来想写"两条不同长度的弹幕，一条先消失"的 Robolectric
测试。去读 Robolectric 源码才发现：legacy 图形模式下 `ShadowPaint.measureText` 返回的是
字符数，而 Compose 走的是 `nGetRunAdvance`，在非 `REALISTIC` 模式下**返回 0**
（`nGetFontMetricsInt` 同样返回 0）。也就是说 JVM 测试里每条弹幕都量成 0、被兜底成 1px，
长短完全分不出来——这种测试写出来是自欺欺人。所以拆成三层：

- JVM 纯函数测试：给定宽度，算得对不对。
- JVM Compose 测试：直接驱动 `DanmakuLine`，宽度由测试自己给（不依赖测量），验证它用的是
  条目自带的时长。为此把 `FlyingDanmaku` 和 `DanmakuLine` 从 private 提成 internal。
- 仪器测试：真机字体，跑完整链路。

**二、仪器测试的方法名不能带空格。** 第一版用了反引号名
`` `a long danmaku outlives the old fixed eight seconds` ``，CI 直接红在
`:app:dexBuilderDebugAndroidTest`：

```
D8: Space characters in SimpleName '...' are not allowed prior to DEX version 040
```

`minSdk = 26`，androidTest 一律按低于 040 的 DEX 版本 dex，含空格的方法名根本打不进包。
JVM 单元测试不 dex，所以只有 androidTest 这个源集必须写 camelCase——现有的
`ScreenshotTest` 一直是这么写的，只是没人写下来过。这是本轮唯一一次 CI 红，红得很有价值。

**三、等速和上限天然矛盾。** 严格等速意味着无限时长；设了上限，超长弹幕就比基准快。
选了上限，因为"一条长文占死轨道"是更糟的失败模式，并把这条取舍写进注释而不是藏着。

### 本轮验证

| 检查项 | 结果 |
| --- | --- |
| `:app:lintDebug` | ✅ |
| JVM 单元测试（447 条，0 失败，0 跳过） | ✅ |
| 仪器测试（32 条，0 失败，含新增计时测试） | ✅ |
| Release assembly（R8） | ✅ |

CI run **41566147166**，commit `67a99fb`，三个 job 全绿。

第一次推的 `a3b613e` 是红的，只有 `Instrumented tests` 红，原因就是上面第二条（D8 空格）。
**同一次 run 里 `Build & test` 和 `Release assembly` 都是绿的**，所以 JVM 侧的 447 条测试、
lint、R8 在 `a3b613e` 上就已经通过了，红的只有 androidTest 的打包那一步。

新增/改动的测试（从 CI 的 `test-reports` / `instrumented-reports` 产物里逐条核对过）：

| 测试类 | 条数 | 断言什么 |
| --- | --- | --- |
| `DanmakuFlightSpeedTest`（新，JVM） | 9 | 三种字宽等速；短弹幕比旧的 8 秒快；长弹幕比旧的 8 秒慢；十字弹幕仍约 8 秒（校准）；跨屏宽时长一致；超长弹幕被 15 秒截住；零宽度不崩；轨道交接按自身宽度；交接不能用共享时长 |
| `DanmakuOverlayTest`（+1，JVM/Robolectric） | 4 | `DanmakuLine` 用的是条目自带的时长：3 秒的飞行到 2 秒还在、4.5 秒结束 |
| `DanmakuFlightTimingTest`（新，仪器） | 1 | 真机测量下，6.5 秒时一字弹幕已消失、55 字弹幕还在；再推到 9 秒（超过旧的 8 秒）长弹幕仍在 |

关于"这些测试在旧代码上会不会红"，要说准确：

- `DanmakuFlightSpeedTest` 的九条**在旧代码上编译不过**：`flightDurationMs` 那时不存在
  （旧的是常量）。所以它们不是对未改代码的回归测试，是跟着 API 一起长出来的能力测试。
  但其中三条（短弹幕 < 8 秒、长弹幕 > 8 秒、等速）在语义上就是旧行为的三条反例。
- `DanmakuOverlayTest` 新增那条在旧代码上也**编译不过**（`FlyingDanmaku` 没有
  `flightMillis` 字段）。如果只把结构补上、时长仍传常量 8000，它在 4.5 秒时
  `finished` 还是 0，断言会失败。
- `DanmakuFlightTimingTest` 是**唯一一条能真正复现用户看到的现象**的测试，而且它在旧代码上
  必然红：旧代码 6.5 秒时一字弹幕还在（`assertCountEquals(0)` 失败），9 秒时 55 字弹幕
  已经没了（`assertExists` 失败）。这条本机跑不了 Gradle，我没有看着它红——但它在新代码上
  跑绿了（2.304 秒），这是 CI 产物里查到的，不是推断。

### 截图里看到了什么

24 张，与上一轮（`r18`）**逐字节相同**，SHA256 两两比对过。这不是"没检查"，
而是这一轮最值得记的发现：

- `17b-player-fullscreen` 里**一条弹幕都没有**。画面是视频占位、返回键、收藏星、
  右侧三个圆形按钮（设置/刷新/全屏）。也就是说截图测试跑的是默认 `autoAdvance = true`，
  `waitForIdle` 会把动画一路跑完，弹幕气泡全被 `onFinished` 移除了才截的图。
- `17-player`（竖屏）里能看到弹幕，但那是**竖屏弹幕列表**（观众A: 这波操作太秀了、
  观众B: 666666…），不是全屏层，本轮没碰。

结论：**这 24 张截图对全屏弹幕层是零覆盖**，而缺陷恰好在那一层——它能活十八轮，
正是因为唯一的视觉防线看不见它。这也解释了为什么"截图没变化"在这里是正确结果而不是
漏检：一个只改动画时长的改动，本来就不该动任何静态布局，24 张全等恰好证明没有连带改动。
真正的证据是仪器测试那条。

### 下一轮

- **给弹幕层补一张截图**（本轮新发现，已记进 runbook）：在 `ScreenshotTest` 里加一个
  `mainClock.autoAdvance = false` + `advanceTimeBy(~1200ms)` 的变体，让气泡停在中途再截。
  本轮没做是因为 `captureToImage` 在冻结时钟下是否稳定没有把握，而这一轮已经没有
  再跑一轮红绿的时间预算；不想在收尾时赌仪器测试 job（60 分钟超时）。
- **"够得着"断言**（runbook 待办第 3 条）：设置页、同步页日志仍缺 `performScrollTo`。
- **首页 chip 密度**（runbook 待办第 4 条）：斗鱼三级分类时四行 chip 占掉约 1/3 屏高。
- 审计项 5-12（四家平台的协议细节、搜索页密度、直播页横竖屏与手势、跨设备同步）仍未做。
- 小事：CI 日志里有 `createComposeRule` 被标记 deprecated（建议迁到
  `androidx.compose.ui.test.junit4.v2.createComposeRule`）。不影响构建，只是提示，
  现有所有 Robolectric/仪器测试都用旧的那个，要迁就一起迁。

## 第二十轮迭代：全屏弹幕层的第一张截图，以及一个被记错了十八轮的原因

### 缺陷是什么

`ScreenshotTest` 里唯一一张全屏播放页的截图 `17b-player-fullscreen`，画面里**一条弹幕都
没有**。也就是说全屏弹幕层（`DanmakuOverlay`）从来没有被任何一张截图看见过：它能连挂
十八轮，是因为唯一的视觉防线根本照不到它。

### 为什么会这样（第十九轮的诊断是错的）

第十九轮把原因记成"`ScreenshotTest` 用默认 `autoAdvance = true`，`waitForIdle` 把动画
跑完了"，runbook 待办第 13 条也照着这个说法写。**本轮核实：这个说法是错的，代码里不是
这么回事。**

`ScreenshotTest.capture()` 第一行就是 `composeRule.mainClock.autoAdvance = false`，紧跟
`advanceTimeByFrame()`（见 `ScreenshotTest.kt` 里那段注释，写的是"否则播放器控件会自动
隐藏"）。时钟从第一帧起就是冻结的，`waitForIdle()` 在冻结时钟下**不推进时间**，所以动画
根本不会被"跑完"。

真正的原因是**另一个方向**：弹幕气泡是从屏幕右边缘**之外**飞进来的
（`DanmakuLine`：`startX = screenWidthPx`，`endX = -textWidthPx`），而 `capture()` 只渲染
一帧。第一帧时五条气泡全部停在 `x = 屏宽` 的位置，被 `DanmakuOverlay` 的
`clipToBounds()` 裁得干干净净。**不是被跑完了，是还没进场。**

这个区别不是措辞问题：按错误诊断去改（比如"让动画停在中间"），改的地方是 `waitForIdle`
的行为，而它本来就不动时钟，改了等于没改。按真实原因去改，缺的是"多推进一段虚拟时间"
这一个动作。

### 怎么修的

`capture()` 加两个参数，默认值保持原来的语义（其他 24 张截图一行都不用改）：

- `advanceMillis: Long = 0L`：推进多少虚拟时间。0 就是原来的一帧。
- `onScreenText: String? = null`：要求某条弹幕的**位置**落在画面内。

新增 `17c-player-fullscreen-danmaku`，同一份 `playerState()`、同样 `fullscreen = true`，
只多一个 `advanceMillis = 2_500L`。

**为什么是 2500ms。** 两个约束夹出来的区间：

- 下限：得让气泡明显进场。恒定速度下位移是 `t / 5500ms × 屏宽`，2500ms 走 45% 屏宽，
  五条气泡的左边缘实测都在 `x ≈ 175`（屏宽 320），远在"进场"这一侧。
- 上限：`FULLSCREEN_HIDE_DELAY_MILLIS = 3000`，超过它顶栏和右侧圆钮会自己隐藏。2500ms
  留了 500ms 余量，所以这张图**同时**保住了顶栏、三个圆钮和弹幕层——比只看弹幕更有用。

**为什么断言位置而不是"节点存在"。** 这是本轮唯一真正的设计决定。停在框外的气泡在语义树
里**照样存在**（`Text` 已经组合、已经布局，只是被父级裁剪掉了），所以
`onNodeWithText(...).assertExists()` 这种断言对"看得见"这件事毫无证明力——它恰恰会让
"截图里什么都没有"这个状态一路绿下去，也就是这个缺陷活了十八轮的同款机制。断言改成读
`boundsInRoot`，要求气泡左边缘进入画面宽度的 90% 以内、右边缘还在画面里。

判定用"进入 10%"而不是"完全在框内"，是因为完全包含对宽标签太紧：位移是 `t/5500×屏宽`
（与标签宽度无关），而标签越宽越容易右边缘还在框外。本轮实测最宽的一条
（`刚来，错过了什么`）右边缘正好压在 318/320 上，只差 2px——用"完全包含"写，这张图以后
只要字体度量变一点点就会红，而它红的原因跟缺陷无关。

### 本轮验证

| 检查项 | 结果 |
| --- | --- |
| `:app:lintDebug` | ✅ |
| JVM 单元测试（447 条，0 失败，0 跳过） | ✅ |
| 仪器测试（33 条，0 失败，含新增 1 条） | ✅ |
| Release assembly（R8） | ✅ |

CI run **41569274928**，commit `f878889`，三个 job 全绿。条数是从 run 的
`test-reports` / `instrumented-reports` 产物里的 XML 逐个累加出来的，不是估的：
JVM 51 个 XML 共 447 条，仪器 1 个 XML 共 33 条（上一轮 32 条，本轮 +1）。

新测试 `ScreenshotTest.playerFullscreenDanmaku` 在产物里耗时 0.515 秒，通过。

### 截图里看到了什么

25 张（上一轮 24 张），逐张 `read_image`，并与 `r19` 做了 SHA256 两两比对：

| 结果 | 张数 | 说明 |
| --- | --- | --- |
| 与 r19 逐字节相同 | 24 | 本次只加参数、加一张图，没有连带改动 |
| 新增 | 1 | `17c-player-fullscreen-danmaku.png`，72107 字节 |

`17c` 里能清楚看到**五条弹幕分五条轨道、排成一条斜阶梯**：这波操作太秀了 / 666666 /
主播声音好听 / 刚来，错过了什么 / 主播能开个麦吗，右侧三个圆钮和顶栏「← 一条小团团 ★」
都还在。斜阶梯是**正确**结果而不是排版错误：五条弹幕是在同一帧一起进场的，所以它们共享
同一个 x，只是轨道不同。

为了确认"两张图的差别只有弹幕"，把 `17b` 和 `17c` 逐像素比对：**差异只落在 y=60..188
这 90 行**，正是弹幕轨道区（overlay 有 `padding(top = 56.dp)`，轨道高 24dp×1.15≈27.6dp，
五条轨道落在 56..193）。其余 550 行完全一致。这既是"对照实验"的证据，也说明
`advanceMillis` 没有意外改动别的东西。

再逐轨量了气泡的横向范围（以 `17b` 为背景做差）：

| 轨道 | 弹幕 | x 范围 | 宽度 |
| --- | --- | --- | --- |
| 1 | 这波操作太秀了 | 175..299 | 124 |
| 2 | 666666 | 176..234 | 58 |
| 3 | 主播声音好听 | 176..282 | 106 |
| 4 | 刚来，错过了什么 | 176..318 | 142 |
| 5 | 主播能开个麦吗 | 176..300 | 124 |

左边缘齐刷刷落在 175/176，正是"同一帧进场"的算术结果（320 − 2516/5500×320 ≈ 173.6，
差 1~2px 是文本内边距）。这也顺带印证了第十九轮那条恒定速度的实现：位移只跟时间有关，
跟标签宽度无关——宽度只影响右边缘。

### 顺带确认的两件事

1. **runbook 待办第 13 条里"先确认冻结时钟下 `captureToImage` 稳定"这条顾虑可以销掉。**
   现有 24 张截图本来就是在冻结时钟下截的，一直稳定；本轮新增的这张也是冻结时钟 + 推进
   2500ms，耗时 0.515 秒，没有把仪器测试 job 赌进去。
2. `ci.py logs --job "Instrumented tests"` 在本机连试两次都抛 `BadZipFile: File is not a
   zip file`——日志包的下载被截断了，跟本轮开头 `ci.py status` 撞上的 `IncompleteRead`
   是同一类问题。按硬约束没有去改 `ci.py`，改从 run 的 artifact 里直接数 XML 拿条数。

### 下一轮

- **"够得着"断言**（runbook 待办第 3 条），本轮已经把地形看清了：`SettingsScreen` 是
  `LazyColumn`，`SyncScreen` 的「我的设备」「同步日志」在 320×640 下**整段在折叠线以下**
  ——`13-sync.png` 里只能看到「我的设备」的标题被切掉一半，日志一条都看不见。这两个页面
  目前**一条测试都没有**（`PlayerSettingsTest` 只覆盖播放器设置弹层）。做法照
  `PlayerSettingsTest` 的样子：约束一个矮窗口 + `performScrollToNode`，断言最后一行够得着。
- **首页 chip 密度**（待办第 4 条）：斗鱼三级分类时四行 chip 占掉约 1/3 屏高。
- 审计项 5-12 仍未做（四家平台协议细节、搜索页密度、直播页横竖屏与手势、跨设备同步）。
- 小事：`createComposeRule` 的 deprecated 提示仍在，要迁就一起迁。