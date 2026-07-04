# 设计:规则驱动的 WebView SNI-Bypass 套壳浏览器

- 状态:待评审(brainstorming 产出,未进入实现)
- 日期:2026-06-22
- 范围:`Pix-EzViewer`(Android / Kotlin)
- 关联:`app/.../networks/`(现有 API 两轴 bypass 模型)、`app/.../ui/WebViewActivity.kt`

---

## 1. 背景与问题

API 层早有干净的单一真相源:`NetworkMode.kt` 的 `applyApiNetwork()` = `DnsMode(direct/doh/system) × SniMode(replace/empty/plain) × VerifyConfig`,统一灌给所有鉴权/接口 OkHttpClient。

浏览器层却是三套机制的泥团:

| Activity | bypass | 机制 |
|---|---|---|
| `WebViewActivity` | 无 | 裸 `loadUrl`,墙内必挂 |
| `OKWebViewActivity` | 有,但另起炉灶 | `WebviewDnsInterceptUtil`:pixiv/pximg 走 `imageHttpClient`/`pixivOkHttpClient`,recaptcha 走祖传 `RubyHttpXDns + HttpsURLConnection + NullHostNameVerifier` |
| `NewUserActivity` | 无(本次不动) | OAuth WebView,裸 load |

并用被复用过度的 `dnsProxy` 布尔在前两个 Activity 间二选一(同时它还插在图片路径与 `Works.kt` 镜像逻辑里)。

附带诉求:
1. 不止 pixiv,**内置浏览器要能 bypass 更多网站**。
2. UI 太简陋(`CoordinatorLayout + 裸 WebView + 一个 FAB`),要更像**正式套壳浏览器**。

参照项目 `racpast/SNIBypassGUI`(本地 Acrylic DNS + 本地 nginx + 自签 CA + per-domain `upstream` 源站 IP + per-domain SNI 策略/ECH)。其**运行时模型不可移植到安卓**(装不了系统 CA、跑不了 nginx),但其 `ProxyRules.json` 的 **(域名 → SNI 策略 / ECH 标记 / 源站 IP 种子)** 数据可作为规则引擎的 seed。

---

## 2. 目标 / 非目标

**目标**
- 合并两个浏览器 Activity 为单一 `WebViewActivity`,消除 `dnsProxy` 作为 Activity 选择器的角色。
- 内置一个**规则驱动的 SNI-bypass 引擎**,作用域限于 WebView 的 `shouldInterceptRequest`,支持 pixiv 及更多站点。
- 源站 IP 走 **DoH 优先 ∪ 规则兜底 + 运行时探测择优**(零硬维护为主,人工兜底为辅)。
- 给浏览器加原生 Material chrome:可编辑地址栏、进度条、下拉刷新、前进后退、分享/外部打开菜单。
- 删除祖传 `WebviewDnsInterceptUtil` 的 `HttpsURLConnection/NullHostNameVerifier` 路径与 `RubyHttpXDns`。

**非目标(本次不做)**
- 不动 `NewUserActivity`(独立 OAuth 登录 WebView)。
- 不改 API / 图片 / 下载路径(`applyApiNetwork()`、`imageProxySocket`、`dnsProxy` 在图片侧的作用保持)。
- 不实现 ECH(Android/OkHttp 支持不成熟;仅留模型字段)。
- 不引入新依赖(UI 用已有 Material/SwipeRefresh/webkit)。
- 不做系统级/全 app bypass 引擎(留未来工作)。

---

## 3. 决策记录

| # | 维度 | 决定 | 理由 |
|---|---|---|---|
| D1 | 架构 | 合并为单一 `WebViewActivity` + 统一拦截 | 消除两套真相源 |
| D2 | 登录页 | `NewUserActivity` 不动 | 超范围;`LoginActivity→浏览器` 登录路径随合并自然统一 |
| D3 | 范围 | 仅 WebView 内置浏览器通用化 | 对 pixiv 客户端划清边界,不动 API/图片 |
| D4 | IP 策略 | DoH ∪ 规则兜底 + 运行时探测 | 兼顾低维护与稳健;复用既有 `autoSelect` 思路 |
| D5 | UI | 原生 Material 自建 | 依赖齐全、全控 `WebViewClient`、与 bypass 契合 |
| D6 | 地址栏 | 可编辑地址/搜索栏 | 配合 D3 通用站点,真正浏览器手感 |
| D7 | 非 GET 请求 | GET 全 bypass,非 GET 放行原生栈 | `shouldInterceptRequest` 拿不到 POST body,无法重发;读多写少场景 GET 占绝大多数 |
| D8 | 规则数据来源 | 随包 **Cealing-Host.json(BSL-1.0,宽松)** 为主种子;SNIBypassGUI 仅作灵感不打包 | Cealing 格式 `[[域名],SNI,IP]` 与 BypassRule 1:1、持续更新、BSL 仅需源码署名 → 避开 AGPL 传染 |

---

## 4. 总体架构

```
WebViewActivity (单一)
 ├─ Chrome: MaterialToolbar(可编辑地址栏+菜单) / LinearProgressIndicator / SwipeRefreshLayout
 └─ WebViewClient.shouldInterceptRequest(request)
        │
        ▼
   WebViewBypassInterceptor.intercept(request)
        ├─ 追踪器(d.pixiv.org/fb/twitter/ga) ───────► 返回空 body
        ├─ 图片 *.pximg.net ────────────────────────► 复用 RestClient.imageHttpClient(图片路径不动)
        ├─ BypassRuleStore.match(host) 命中 ───────► 按 rule 重发(见 §6)
        │        rule = BypassRule{domains, sni, frontSni, ip, directIps, verify}
        │        endpoint = BypassResolver.resolve(host, rule)  // DoH∪兜底 → 探测 → 缓存
        │        client   = 按 endpoint 装配的 OkHttpClient(复用 SNI 原语)
        └─ 未命中 / 非GET / 重发失败 ───────────────► null(交还原生 WebView)
```

核心统一点:WebView 引擎复用 API 层的 SNI 原语(`RubySSLSocketFactory` 空 SNI、`ReplaceSniSocketFactory` 替换 SNI、`VerifyConfig`、`SniMode`/`DnsMode` 枚举、DoH 客户端),本质是"**per-domain 参数化的 applyApiNetwork + 运行时解析**"。

---

## 5. 组件设计

> 新增目录:`app/src/main/java/com/perol/asdpl/pixivez/networks/bypass/`

### 5.1 `BypassRule.kt`(模型)
- **职责**:描述一个域名集合的 bypass 策略。
- **形态**(复用既有枚举,不另造):
  - `domains: List<String>` —— 后缀模式(如 `pixiv.net`、`www.pixivision.net`)。
  - `sni: SniMode` —— 复用 `NetworkMode.SniMode`(REPLACE/EMPTY/PLAIN)。
  - `frontSni: String?` —— REPLACE 用(pixiv.me、g.cn…);空则取全局 `SniReplaceConfig.host()`。
  - `ip: DnsMode` —— 复用 `NetworkMode.DnsMode`(DIRECT/DOH/SYSTEM)。
  - `directIps: List<String>` —— 源站直连/兜底 IP(DIRECT 或作探测候选)。
  - `verify: Boolean` —— 证书+主机名校验。
  - `ech: Boolean = false` —— 预留,不实现。
- **依赖**:`NetworkMode` 的枚举。

### 5.2 `BypassRuleStore.kt` + `CealingHostParser.kt` + `assets/bypass/`
- **职责**:加载规则、按 host 最长后缀匹配。
- **接口**:`match(host: String): BypassRule?`。
- **数据文件(D8)**:`app/src/main/assets/bypass/`
  - `cealing-host.json` —— 直接打包 `SpaceTimee/Cealing-Host` 快照(**BSL-1.0**,宽松)。格式 `[[域名模式], fakeSni, ip]` 与 BypassRule **1:1**:`fakeSni=""`→`SniMode.EMPTY`,非空→`REPLACE(frontSni)`;`ip=""`→走 DoH。已含 pixiv/fanbox/pximg(同款 210.140.139.x)、gstatic→g.cn、github、e-hentai 等 138 条。
  - `NOTICE` —— BSL-1.0 署名 `SpaceTimee/Cealing-Host`。
- `CealingHostParser.kt` —— 解析 Cealing 格式(`*` 通配后缀、`#`/`$` 标签、`^` 排除分隔)→ `BypassRule`。**同一 parser 复用于未来运行时导入最新表**(§11)。
- **内置补充**:Cealing 表未覆盖而登录/验证码所需的少量域(如 recaptcha、accounts.pixiv.net 若缺)以代码内建规则补齐。
- **许可**:BSL-1.0 宽松,随 MIT app 打包仅需源码署名(无 copyleft 传染);`bypass/CLAUDE.md` + README 注明来源。SNIBypassGUI(AGPL)仅作灵感,**不打包其数据**。
- **依赖**:app assets、`kotlinx.serialization.json`(已依赖,1.9.0)。

### 5.3 `BypassResolver.kt`(IP 策略 C)
- **职责**:为 (host, rule) 选出可用 endpoint。
- **接口**:`resolve(host: String, rule: BypassRule): Endpoint?`,`Endpoint = (ip: InetAddress, sni: SniMode, frontSni: String?, verify: Boolean)`。
- **算法**:
  1. 候选 IP = `rule.directIps` ∪ DoH(host)(把 `DohApiDns` 的 DoH 客户端泛化到任意域名;不再限于 3 个 API host)。
  2. 候选 SNI = `rule.sni`(必要时补 EMPTY 兜底)。
  3. **运行时探测**(泛化 `SniReplaceConfig.probe`):逐个 (ip, sni) HEAD/GET → 握手未被 RST、HTTP≠421、证书合理 → 选中。
  4. 缓存 `host → Endpoint`(TTL,参照 `DohApiDns` 10min)。
- **依赖**:DoH 客户端、SNI 原语、`SniReplaceConfig` 的探测逻辑(抽取为共享 helper,API 侧行为不变)。

### 5.4 `WebViewBypassInterceptor.kt`(替代 `WebviewDnsInterceptUtil`)
- **职责**:`shouldInterceptRequest` 的全部分发与重发。
- **接口**:`intercept(request: WebResourceRequest): WebResourceResponse?`。
- **流程**:见 §6。
- **OkHttp 装配**:用 `Dns{host→endpoint.ip}` + 按 `endpoint.sni` 选 SNI socket factory(复用 `ReplaceSniSocketFactory`/`RubySSLSocketFactory`)+ verify 策略——与 `applyApiNetwork` 同构,但按 endpoint 参数化;按 endpoint 缓存 client 避免重建。
- **依赖**:`BypassRuleStore`、`BypassResolver`、`RestClient.imageHttpClient`、SNI 原语。

### 5.5 `WebViewActivity.kt`(合并)
- **职责**:承载浏览器 chrome + 注入 bypass 的 `WebViewClient`。
- **并入** `OKWebViewActivity` 的:夜间模式 CSS 注入、`onReceivedSslError` 对话框、完整 WebSettings、正确的 `/ja/` 路径替换、`shouldOverrideUrlLoading` 的 pixiv:// 与原生路由。
- **Chrome(原生 Material)**:
  - `AppBarLayout + MaterialToolbar`:导航键=关闭;**可编辑地址栏**(EditText/SearchBar,回车 → `loadUrl`,自动补 `https://`,非 URL 走搜索);溢出菜单=刷新/停止、复制链接、分享、外部浏览器打开、前进、连接设置快捷入口;标题/副标题 = `onReceivedTitle` / 真实 host。
  - `LinearProgressIndicator`:绑 `onProgressChanged`,满 100 隐藏。
  - `SwipeRefreshLayout` 包 WebView:下拉刷新。
  - 返回键:`canGoBack` → `goBack`,否则 finish(`OnBackPressedDispatcher`)。
  - 移除 FAB(并入 toolbar)。
- **依赖**:`WebViewBypassInterceptor`、Material、SwipeRefresh、androidx.webkit。

---

## 6. 数据流(`intercept` 管线)

1. host ∈ 追踪器集 → 返回空 JS body(同现状)。
2. host 属 `*.pximg.net` → `imageHttpClient` 重发(图片路径不动)。
3. `BypassRuleStore.match(host)`:
   - 命中且 `request.method == GET`:`BypassResolver.resolve` → 按 endpoint 取/建 client → 执行 → 包 `WebResourceResponse`(改写 content-type、`Access-Control-Allow-Origin:*`)。失败 → 返回 null。
   - 命中但非 GET / 重发抛错:返回 null(交还 WebView,best-effort)。
   - 未命中:返回 null(原生加载)。
4. 重定向:OkHttp 跟随后,若终态 host 与原 host 不同,按终态 URL 内容返回(沿用现有处理),相对链接风险见 §8。

---

## 7. 删除清单
- `ui/OKWebViewActivity.kt`(功能并入 `WebViewActivity`)。
- `ui/OKWebViewActivity.kt` 内的 `WebviewDnsInterceptUtil`(含 `HttpsURLConnection`/`NullHostNameVerifier`/`getIgnoreSSLContext` 整条祖传路径)。
- `networks/RubyHttpXDns.kt`(web 域名→源站 IP 知识迁入 `bypass_rules.json` 种子)。唯二引用:`OKWebViewActivity`(随之删)+ `RestClient.kt:52` 的 `apiDns` 死字段(`proxySocket` 默认参从无调用方),一并清理。
- `dnsProxy` 作为 Activity 选择器:`PixivsionActivity` / `RecomFragment` 去分支恒走 `WebViewActivity`。(`dnsProxy` 本体保留,服务图片路径与 `Works.kt`。)

---

## 8. 文件影响

**新增**
- `networks/bypass/BypassRule.kt`
- `networks/bypass/BypassRuleStore.kt`
- `networks/bypass/BypassResolver.kt`
- `networks/bypass/WebViewBypassInterceptor.kt`
- `networks/bypass/CealingHostParser.kt`
- `networks/bypass/CLAUDE.md`(含第三方数据许可说明)
- `app/src/main/assets/bypass/cealing-host.json`(Cealing-Host 快照,BSL-1.0)
- `app/src/main/assets/bypass/NOTICE`(署名 SpaceTimee/Cealing-Host,BSL-1.0)
- 根 `README.md` 增一节:第三方规则数据来源与署名

**修改**
- `ui/WebViewActivity.kt`(合并 + chrome + 注入拦截器)
- `res/layout/activity_web_view.xml`(toolbar/进度条/下拉刷新/地址栏)
- `res/menu/`(新增浏览器溢出菜单)
- `IntentActivity.kt`(pixiv.me 仍指 `WebViewActivity`,确认无碍)
- `ui/home/pixivision/PixivsionActivity.kt`、`ui/home/recom/RecomFragment.kt`、`ui/account/LoginActivity.kt`(去 `dnsProxy` 分支 / 改引用)
- `AndroidManifest.xml`(删 `OKWebViewActivity`)
- `networks/CLAUDE.md`、`ui/CLAUDE.md`(架构同步)

**删除**:见 §7。

---

## 9. 风险与边界

1. **GET-only(D7,API 根因)**:`WebViewClient.shouldInterceptRequest` 的 `WebResourceRequest` **不暴露 POST body**,故 POST/PUT 无法重发、WebSocket/部分流式拦不住。**决定:非 GET 放行原生栈**(host 被 SNI 封则该请求失败)。读多写少的浏览场景 GET 占绝大多数;现状本就 GET-only,非退步。需重 AJAX-POST 第三方站时,未来可加 JS fetch/XHR 桥(§11)。
2. **无系统 CA**(区别于 SNIBypassGUI):仅被规则命中的 host 在 OkHttp 重发层获 bypass;WebView 自身直连的未命中请求仍走系统栈。
3. **规则/兜底 IP 会腐烂**:靠 D4 运行时探测 + 日后远程规则更新缓解。
4. **重定向相对链接**:跨 host 重定向后页面内相对资源 host 仍是原 host,可能不命中规则;需在拦截器内对终态 host 重新匹配。
5. **首连探测延迟**:探测择优有 RTT 成本;需异步 + 进度反馈 + 结果缓存,避免首屏卡顿。
6. **地址栏 UX**:URL/搜索判别、错误输入、深链拦截与地址栏显示的一致性。
7. **ECH 缺位**:纯 anycast + 空/替换 SNI 不通的站,本期无解,只能 PLAIN 或放弃。
8. **许可合规**:主种子用 **Cealing-Host(BSL-1.0,宽松)**,随 MIT app 打包仅需源码署名,无 copyleft 传染。SNIBypassGUI(AGPL)仅作选站/策略灵感,**不打包其数据**。不打包任何站点 favicon。此为务实判断,非法律意见。

---

## 10. 验证标准(回归集)

- pixivision 文章在新 `WebViewActivity` 正常加载(图文)。
- `pixiv.me` 短链跳转正常。
- `LoginActivity` 网页登录(`app-api.pixiv.net/web/v1/login`)走统一拦截,`pixiv://account/login?code=` 回调命中 → 登录成功。
- 至少一个非 pixiv 种子站(如 wikipedia 或 github)能在内置浏览器打开。
- 地址栏输入 URL/关键词 → 正确跳转/搜索。
- 进度条、下拉刷新、前进后退、分享/外部打开、复制链接可用。
- 切换 SNI/DNS/校验设置后行为符合预期(`snackbarForceRestart` 后)。
- `OKWebViewActivity` / `RubyHttpXDns` / `WebviewDnsInterceptUtil` 删除后编译通过、无悬挂引用。

---

## 11. 未来工作
- JS `fetch`/`XHR` 桥:突破 GET-only,覆盖 AJAX POST(见 §9.1)。
- 外部规则源导入 / 订阅(`CealingHostParser` 已就绪;指向 Cealing-Host "Up 2 Date" 上游即可热更新 IP)。
- ECH 支持(待 Android/OkHttp 成熟)。
- 将规则引擎上提为全 app(API/图片/WebView)共用的统一 bypass 层(范围 A)。
- 探测结果与 `apiDirectIPs` 思路打通,用户可手动覆盖。

---

## 12. CLAUDE.md 同步(实现时强制)
- `networks/CLAUDE.md`:新增 `bypass/` 子层职责;`RubyHttpXDns` 移除说明;DoH 客户端泛化说明。
- `networks/bypass/CLAUDE.md`(新建):引擎职责 + **AGPL 数据 / MIT 代码混合许可**声明。
- `ui/CLAUDE.md`:`OKWebViewActivity` 合并入 `WebViewActivity` 的说明 + 浏览器 chrome 结构。
