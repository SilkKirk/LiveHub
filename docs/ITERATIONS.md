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
