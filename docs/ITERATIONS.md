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