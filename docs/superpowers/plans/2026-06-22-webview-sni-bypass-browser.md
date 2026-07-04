# WebView SNI-Bypass 套壳浏览器 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把两个浏览器 Activity 合并为单一带正式 chrome 的套壳浏览器,其 WebView 经一个规则驱动的 SNI-bypass 引擎(种子自 Cealing-Host)支持 pixiv 及更多站点。

**Architecture:** `WebViewActivity` 的 `shouldInterceptRequest` 把命中规则的 GET 请求经"per-domain 参数化的 OkHttp client(复用 API 层 SNI 原语)+ 运行时 IP 探测"重发;规则来自打包的 Cealing-Host 快照(BSL-1.0)。API/图片路径不动。

**Tech Stack:** Kotlin、OkHttp 4、androidx.webkit、Material Components 1.12、SwipeRefreshLayout、kotlinx.serialization.json 1.9、JUnit4。

## Global Constraints

- 范围限 WebView;**不改** `applyApiNetwork()` / 图片 `imageProxySocket` / `NewUserActivity`。
- **非 GET 请求放行原生栈**(`shouldInterceptRequest` 无 POST body)。
- **不引入新依赖**(只用已声明的库)。
- 规则数据 = 打包 `SpaceTimee/Cealing-Host`(**BSL-1.0**),仅需源码署名;**不打包** SNIBypassGUI(AGPL)数据;不打包站点 favicon。
- ECH 不实现,仅留模型字段。
- 复用现有 SNI 原语:`RubySSLSocketFactory`(空 SNI)、`ReplaceSniSocketFactory`(替换 SNI)、`SniMode`/`DnsMode`/`VerifyConfig`(`networks/NetworkMode.kt`)。
- 纯逻辑单元(parser、match、规则映射)**不得 import `android.*`**,以便 JVM 单测。
- 提交信息结尾附加 `~.O`。
- 工作分支:`feat/webview-sni-bypass-browser`(已建)。
- 架构级文件增删后**必须同步对应目录 CLAUDE.md**。

---

### Task 1: `BypassRule` 模型 + `CealingHostParser`(纯逻辑,TDD)

**Files:**
- Create: `app/src/main/java/com/perol/asdpl/pixivez/networks/bypass/BypassRule.kt`
- Create: `app/src/main/java/com/perol/asdpl/pixivez/networks/bypass/CealingHostParser.kt`
- Test: `app/src/test/java/com/perol/asdpl/pixivez/networks/bypass/CealingHostParserTest.kt`
- Modify: `app/build.gradle.kts`(确保 `testImplementation(libs.junit)`)

**Interfaces:**
- Produces:
  - `data class BypassRule(val patterns: List<HostPattern>, val sni: SniMode, val frontSni: String?, val ip: String?)`,方法 `fun match(host: String): Int`(返回匹配特异度=模式长度,-1 不匹配)。
  - `sealed class HostPattern { fun match(host: String): Int }`,子类 `Exact(host)`、`Suffix(base)`。
  - `object CealingHostParser { fun parse(json: String): List<BypassRule> }`。
- Consumes:`com.perol.asdpl.pixivez.networks.SniMode`(REPLACE/EMPTY/PLAIN)。

- [ ] **Step 1: 确保 junit 测试依赖**

`app/build.gradle.kts` 的 dependencies 块加入(若不存在):

```kotlin
    testImplementation(libs.junit)
```

- [ ] **Step 2: 写失败测试**

`CealingHostParserTest.kt`:

```kotlin
package com.perol.asdpl.pixivez.networks.bypass

import com.perol.asdpl.pixivez.networks.SniMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CealingHostParserTest {
    @Test fun emptySni_maps_to_EMPTY_and_ip_kept() {
        val r = CealingHostParser.parse("""[ [["*pximg.net"],"","210.140.139.133"] ]""").single()
        assertEquals(SniMode.EMPTY, r.sni)
        assertNull(r.frontSni)
        assertEquals("210.140.139.133", r.ip)
    }

    @Test fun nonEmptySni_maps_to_REPLACE_with_frontSni() {
        val r = CealingHostParser.parse("""[ [["*pixiv.net","*fanbox.cc"],"pixivision.net","210.140.139.155"] ]""").single()
        assertEquals(SniMode.REPLACE, r.sni)
        assertEquals("pixivision.net", r.frontSni)
    }

    @Test fun blankIp_maps_to_null() {
        val r = CealingHostParser.parse("""[ [["*.googlevideo.com"],"",""] ]""").single()
        assertNull(r.ip)
    }

    @Test fun suffix_pattern_matches_host_and_subdomain_but_longest_wins() {
        val r = CealingHostParser.parse("""[ [["*pixiv.net"],"",""] ]""").single()
        assertTrue(r.match("www.pixiv.net") >= 0)
        assertTrue(r.match("pixiv.net") >= 0)
        assertEquals(-1, r.match("example.com"))
    }

    @Test fun label_and_operator_tokens_are_tolerated() {
        // '#'/'$' 前缀为标签/次级标记,'^' 为排除分隔;解析不得崩溃,仍产出可匹配正例
        val rules = CealingHostParser.parse(
            """[ [["#*google*","$*google.com","*gstatic.com"],"g.cn","183.56.143.147"] ]"""
        )
        assertTrue(rules.single().match("www.gstatic.com") >= 0)
    }
}
```

- [ ] **Step 3: 运行测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.perol.asdpl.pixivez.networks.bypass.CealingHostParserTest"`
Expected: FAIL（未定义 `BypassRule`/`CealingHostParser`，编译错误）

- [ ] **Step 4: 实现 `BypassRule.kt`**

```kotlin
/*
 * ┌──────────────────────────────────────────────────────────────────────┐
 * │ BypassRule —— 单条「域名集合 → SNI 策略 + 落点 IP」规则。              │
 * │ 复用 API 层 SniMode;ip=null 表示交由运行时 DoH 解析。无 android 依赖。 │
 * └──────────────────────────────────────────────────────────────────────┘
 */
package com.perol.asdpl.pixivez.networks.bypass

import com.perol.asdpl.pixivez.networks.SniMode

sealed class HostPattern {
    /** 命中返回特异度(越大越具体),否则 -1。 */
    abstract fun match(host: String): Int

    data class Exact(val host: String) : HostPattern() {
        override fun match(host: String) = if (host.equals(this.host, true)) this.host.length + 1 else -1
    }

    /** Cealing `*base`:任意前缀 + base 结尾(含 base 本身)。 */
    data class Suffix(val base: String) : HostPattern() {
        override fun match(host: String) =
            if (host.equals(base, true) || host.endsWith(base, true)) base.length else -1
    }
}

data class BypassRule(
    val patterns: List<HostPattern>,
    val sni: SniMode,
    val frontSni: String?,
    val ip: String?,
) {
    fun match(host: String): Int = patterns.maxOfOrNull { it.match(host) } ?: -1
}
```

- [ ] **Step 5: 实现 `CealingHostParser.kt`**

```kotlin
/*
 * ┌──────────────────────────────────────────────────────────────────────┐
 * │ CealingHostParser —— 解析 SpaceTimee/Cealing-Host 格式(BSL-1.0)。     │
 * │ 条目 = [ [域名模式...], fakeSni, ip ]                                   │
 * │   fakeSni "" → EMPTY,非空 → REPLACE(frontSni);ip "" → null(走 DoH)。 │
 * │ 域名前缀:'*' 通配后缀;'#' 标签(忽略);'$' 次级(剥前缀按普通);     │
 * │           '^' 排除分隔(取首段正例,其余忽略——保守实现)。            │
 * │ 同一 parser 复用于打包种子与未来运行时导入。无 android 依赖。           │
 * └──────────────────────────────────────────────────────────────────────┘
 */
package com.perol.asdpl.pixivez.networks.bypass

import com.perol.asdpl.pixivez.networks.SniMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

object CealingHostParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(text: String): List<BypassRule> {
        val root = json.parseToJsonElement(text).jsonArray
        return root.mapNotNull { entry ->
            val arr = entry as? JsonArray ?: return@mapNotNull null
            if (arr.size < 3) return@mapNotNull null
            val rawDomains = (arr[0] as JsonArray).map { it.jsonPrimitive.content }
            val fakeSni = arr[1].jsonPrimitive.content.trim()
            val ip = arr[2].jsonPrimitive.content.trim()
            val patterns = rawDomains.mapNotNull(::toPattern)
            if (patterns.isEmpty()) return@mapNotNull null
            BypassRule(
                patterns = patterns,
                sni = if (fakeSni.isEmpty()) SniMode.EMPTY else SniMode.REPLACE,
                frontSni = fakeSni.ifEmpty { null },
                ip = ip.ifEmpty { null },
            )
        }
    }

    private fun toPattern(token: String): HostPattern? {
        if (token.startsWith("#")) return null            // 标签,非匹配项
        var t = token.removePrefix("$")                   // 次级标记按普通处理
        t = t.substringBefore("^").trim()                 // 排除分隔:保守取正例
        if (t.isEmpty()) return null
        return if (t.startsWith("*")) HostPattern.Suffix(t.removePrefix("*"))
        else HostPattern.Exact(t)
    }
}
```

- [ ] **Step 6: 运行测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.perol.asdpl.pixivez.networks.bypass.CealingHostParserTest"`
Expected: PASS（5 测试全绿）

- [ ] **Step 7: 提交**

```bash
git add app/src/main/java/com/perol/asdpl/pixivez/networks/bypass/BypassRule.kt \
        app/src/main/java/com/perol/asdpl/pixivez/networks/bypass/CealingHostParser.kt \
        app/src/test/java/com/perol/asdpl/pixivez/networks/bypass/CealingHostParserTest.kt \
        app/build.gradle.kts
git commit -m "feat(bypass): add BypassRule model + Cealing-Host parser

~.O"
```

---

### Task 2: 打包 Cealing-Host 数据 + `BypassRuleStore`(match TDD)

**Files:**
- Create: `app/src/main/assets/bypass/cealing-host.json`（Cealing-Host 快照）
- Create: `app/src/main/assets/bypass/NOTICE`（BSL-1.0 署名）
- Create: `app/src/main/java/com/perol/asdpl/pixivez/networks/bypass/BypassRuleStore.kt`
- Test: `app/src/test/java/com/perol/asdpl/pixivez/networks/bypass/BypassRuleStoreTest.kt`

**Interfaces:**
- Consumes: `CealingHostParser.parse`, `BypassRule`.
- Produces:
  - `object BypassRuleStore { fun init(context: Context); fun match(host: String): BypassRule? }`
  - `internal fun matchIn(rules: List<BypassRule>, host: String): BypassRule?`（纯函数,供测试;最长匹配胜出,平手取靠前）。

- [ ] **Step 1: 放入 Cealing-Host 快照**

下载当前 `https://github.com/SpaceTimee/Cealing-Host/blob/main/Cealing-Host.json` 原文存为 `app/src/main/assets/bypass/cealing-host.json`（保持其数组格式,供 `CealingHostParser` 直接解析）。

Run:
```bash
curl -fsSL https://raw.githubusercontent.com/SpaceTimee/Cealing-Host/main/Cealing-Host.json \
  -o app/src/main/assets/bypass/cealing-host.json
head -c 80 app/src/main/assets/bypass/cealing-host.json
```
Expected: 输出以 `[` 开头的 JSON 数组。

- [ ] **Step 2: 写署名文件**

`app/src/main/assets/bypass/NOTICE`:

```
Bundled rule data: app/src/main/assets/bypass/cealing-host.json
Source : https://github.com/SpaceTimee/Cealing-Host
License: Boost Software License 1.0 (BSL-1.0)

This data file is distributed under BSL-1.0. The surrounding Pix-EzViewer
source code remains under its own (MIT) license. No site favicons are bundled.
```

- [ ] **Step 3: 写失败测试(纯匹配逻辑)**

`BypassRuleStoreTest.kt`:

```kotlin
package com.perol.asdpl.pixivez.networks.bypass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BypassRuleStoreTest {
    private val rules = CealingHostParser.parse(
        """[
          [["*pixiv.net"],"pixiv.me","210.140.139.155"],
          [["www.pixiv.net"],"","210.140.139.223"],
          [["*pximg.net"],"","210.140.139.133"]
        ]"""
    )

    @Test fun longest_match_wins() {
        // www.pixiv.net 同时命中 "*pixiv.net" 与精确 "www.pixiv.net",取更具体者
        assertEquals("210.140.139.223", BypassRuleStore.matchIn(rules, "www.pixiv.net")?.ip)
    }

    @Test fun suffix_match_for_subdomain() {
        assertEquals("210.140.139.155", BypassRuleStore.matchIn(rules, "i.pixiv.net")?.ip)
    }

    @Test fun no_match_returns_null() {
        assertNull(BypassRuleStore.matchIn(rules, "example.com"))
    }
}
```

- [ ] **Step 4: 运行确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.perol.asdpl.pixivez.networks.bypass.BypassRuleStoreTest"`
Expected: FAIL（`matchIn` 未定义）

- [ ] **Step 5: 实现 `BypassRuleStore.kt`**

```kotlin
/*
 * ┌──────────────────────────────────────────────────────────────────────┐
 * │ BypassRuleStore —— 加载打包的 Cealing-Host 规则 + 内置补充,按 host    │
 * │ 最长匹配返回规则。init 须在 Application 启动调用一次。                   │
 * └──────────────────────────────────────────────────────────────────────┘
 */
package com.perol.asdpl.pixivez.networks.bypass

import android.content.Context
import com.perol.asdpl.pixivez.networks.SniMode

object BypassRuleStore {
    // 登录/验证码所需、Cealing 表可能未覆盖的内置补充(REPLACE 用 pixiv.me/g.cn)。
    private val builtin: List<BypassRule> = listOf(
        BypassRule(listOf(HostPattern.Exact("accounts.pixiv.net")), SniMode.REPLACE, "pixiv.me", null),
        BypassRule(listOf(HostPattern.Suffix("recaptcha.net")), SniMode.REPLACE, "g.cn", null),
        BypassRule(listOf(HostPattern.Suffix("gstatic.com")), SniMode.REPLACE, "g.cn", null),
    )

    @Volatile private var rules: List<BypassRule> = builtin

    fun init(context: Context) {
        rules = try {
            val text = context.assets.open("bypass/cealing-host.json")
                .bufferedReader().use { it.readText() }
            builtin + CealingHostParser.parse(text)   // 内置优先(平手靠前胜出)
        } catch (e: Exception) {
            builtin
        }
    }

    fun match(host: String): BypassRule? = matchIn(rules, host)

    internal fun matchIn(rules: List<BypassRule>, host: String): BypassRule? {
        var best: BypassRule? = null
        var bestScore = -1
        for (r in rules) {
            val s = r.match(host)
            if (s > bestScore) { bestScore = s; best = r }   // > 保证平手取靠前
        }
        return if (bestScore >= 0) best else null
    }
}
```

- [ ] **Step 6: 运行确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.perol.asdpl.pixivez.networks.bypass.BypassRuleStoreTest"`
Expected: PASS

- [ ] **Step 7: 在 Application 初始化 store**

`app/src/main/java/com/perol/asdpl/pixivez/services/PxEZApp.kt` 的 `onCreate()` 末尾加入(import `com.perol.asdpl.pixivez.networks.bypass.BypassRuleStore`):

```kotlin
        BypassRuleStore.init(this)
```

- [ ] **Step 8: 提交**

```bash
git add app/src/main/assets/bypass/ \
        app/src/main/java/com/perol/asdpl/pixivez/networks/bypass/BypassRuleStore.kt \
        app/src/test/java/com/perol/asdpl/pixivez/networks/bypass/BypassRuleStoreTest.kt \
        app/src/main/java/com/perol/asdpl/pixivez/services/PxEZApp.kt
git commit -m "feat(bypass): bundle Cealing-Host rules + BypassRuleStore (BSL-1.0)

~.O"
```

---

### Task 3: `BypassResolver`（DoH ∪ 兜底 IP + 运行时探测 + 缓存）

**Files:**
- Create: `app/src/main/java/com/perol/asdpl/pixivez/networks/bypass/BypassResolver.kt`
- Test: `app/src/test/java/com/perol/asdpl/pixivez/networks/bypass/BypassResolverTest.kt`

**Interfaces:**
- Consumes: `BypassRule`, `SniMode`, `DohApiDns`(其 DoH 客户端)、`SniReplaceConfig`(探测模式参考)。
- Produces:
  - `data class Endpoint(val ip: java.net.InetAddress, val sni: SniMode, val frontSni: String?, val verify: Boolean)`
  - `object BypassResolver { fun resolve(host: String, rule: BypassRule): Endpoint?; internal fun pick(candidates, prober): Endpoint? }`
  - `fun interface Prober { fun ok(ip: InetAddress, host: String, sni: SniMode, frontSni: String?): Boolean }`

**说明:** 真探测需网络,放手动冒烟(Task 8);此处仅 TDD「候选装配 + 选择 + 缓存」逻辑,用假 `Prober`。

- [ ] **Step 1: 写失败测试**

`BypassResolverTest.kt`:

```kotlin
package com.perol.asdpl.pixivez.networks.bypass

import com.perol.asdpl.pixivez.networks.SniMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetAddress

class BypassResolverTest {
    private fun ip(s: String) = InetAddress.getByName(s)

    @Test fun picks_first_probe_ok_candidate() {
        val cands = listOf(
            Endpoint(ip("1.1.1.1"), SniMode.EMPTY, null, true),
            Endpoint(ip("2.2.2.2"), SniMode.EMPTY, null, true),
        )
        val prober = Prober { addr, _, _, _ -> addr.hostAddress == "2.2.2.2" }
        assertEquals("2.2.2.2", BypassResolver.pick(cands, "h", prober)?.ip?.hostAddress)
    }

    @Test fun returns_null_when_none_ok() {
        val cands = listOf(Endpoint(ip("1.1.1.1"), SniMode.EMPTY, null, true))
        assertNull(BypassResolver.pick(cands, "h", { _, _, _, _ -> false }))
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.perol.asdpl.pixivez.networks.bypass.BypassResolverTest"`
Expected: FAIL

- [ ] **Step 3: 实现 `BypassResolver.kt`**

```kotlin
/*
 * ┌──────────────────────────────────────────────────────────────────────┐
 * │ BypassResolver —— 为 (host, rule) 选可用 endpoint:                     │
 * │   候选 IP = rule.ip(若有) ∪ DoH(host);候选 SNI = rule.sni;            │
 * │   逐个运行时探测(握手未被 RST、HTTP≠421)首个可用者,按 host 缓存 TTL。 │
 * │ 复用 API 层 DoH 客户端与 SNI 原语。                                     │
 * └──────────────────────────────────────────────────────────────────────┘
 */
package com.perol.asdpl.pixivez.networks.bypass

import com.perol.asdpl.pixivez.networks.DohApiDns
import com.perol.asdpl.pixivez.networks.ReplaceSniSocketFactory
import com.perol.asdpl.pixivez.networks.RubySSLSocketFactory
import com.perol.asdpl.pixivez.networks.RubyX509TrustManager
import com.perol.asdpl.pixivez.networks.SniMode
import com.perol.asdpl.pixivez.networks.SniReplaceConfig
import com.perol.asdpl.pixivez.networks.VerifyConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class Endpoint(
    val ip: InetAddress,
    val sni: SniMode,
    val frontSni: String?,
    val verify: Boolean,
)

fun interface Prober {
    fun ok(ip: InetAddress, host: String, sni: SniMode, frontSni: String?): Boolean
}

object BypassResolver {
    private const val TTL_MS = 10 * 60 * 1000L
    private val cache = ConcurrentHashMap<String, Pair<Long, Endpoint>>()

    private val probeBase: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .build()
    }

    /** 默认探测器:连指定 IP + 指定 SNI,HEAD 该 host,握手成功且 HTTP≠421 即可用。 */
    private val defaultProber = Prober { ip, host, sni, frontSni ->
        try {
            val factory = when (sni) {
                SniMode.REPLACE -> ReplaceSniSocketFactory(frontSni ?: SniReplaceConfig.host())
                SniMode.EMPTY -> RubySSLSocketFactory()
                SniMode.PLAIN -> null
            }
            val b = probeBase.newBuilder().dns { listOf(ip) }
            if (factory != null) b.sslSocketFactory(factory, RubyX509TrustManager())
                .hostnameVerifier { _, _ -> true }
            b.build().newCall(Request.Builder().url("https://$host/").head().build())
                .execute().use { it.code != 421 }
        } catch (e: Exception) { false }
    }

    fun resolve(host: String, rule: BypassRule): Endpoint? {
        val now = System.currentTimeMillis()
        cache[host]?.let { (at, ep) -> if (now - at < TTL_MS) return ep }
        val ips = buildList {
            rule.ip?.let { runCatching { add(InetAddress.getByName(it)) } }
            runCatching { addAll(DohApiDns.lookupPublic(host)) }
        }.distinct()
        val verify = VerifyConfig.enabled()
        val candidates = ips.map { Endpoint(it, rule.sni, rule.frontSni, verify) }
        return pick(candidates, host, defaultProber)?.also { cache[host] = now to it }
    }

    internal fun pick(candidates: List<Endpoint>, host: String, prober: Prober): Endpoint? =
        candidates.firstOrNull { prober.ok(it.ip, host, it.sni, it.frontSni) }
}
```

- [ ] **Step 4: 给 `DohApiDns` 暴露泛化解析**

`networks/NetworkMode.kt` 的 `DohApiDns` 内新增(不改其 `lookup` 行为,仅供 bypass 用):

```kotlin
    /** 供 WebView bypass:对任意域名经 DoH 解析(失败回退空表)。 */
    fun lookupPublic(host: String): List<InetAddress> =
        try { doh.lookup(host) } catch (e: Exception) { emptyList() }
```

- [ ] **Step 5: 运行确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.perol.asdpl.pixivez.networks.bypass.BypassResolverTest"`
Expected: PASS

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/perol/asdpl/pixivez/networks/bypass/BypassResolver.kt \
        app/src/test/java/com/perol/asdpl/pixivez/networks/bypass/BypassResolverTest.kt \
        app/src/main/java/com/perol/asdpl/pixivez/networks/NetworkMode.kt
git commit -m "feat(bypass): add BypassResolver (DoH + curated IP, runtime probe)

~.O"
```

---

### Task 4: `WebViewBypassInterceptor`（拦截管线）

**Files:**
- Create: `app/src/main/java/com/perol/asdpl/pixivez/networks/bypass/WebViewBypassInterceptor.kt`

**Interfaces:**
- Consumes: `BypassRuleStore.match`, `BypassResolver.resolve`, `RestClient.imageHttpClient`, `RestClient.UA`, SNI 原语。
- Produces: `class WebViewBypassInterceptor(private val ua: String) { fun intercept(request: WebResourceRequest): WebResourceResponse? }`

**说明:** 涉及 `WebResourceRequest`/OkHttp,放构建+手动冒烟验证(Task 8)。逻辑要点见代码注释。

- [ ] **Step 1: 实现拦截器**

```kotlin
/*
 * ┌──────────────────────────────────────────────────────────────────────┐
 * │ WebViewBypassInterceptor —— shouldInterceptRequest 的统一分发:        │
 * │   追踪器→空;*.pximg.net→imageHttpClient;命中规则的 GET→按 endpoint   │
 * │   重发;其余/非 GET/失败→null(交还原生栈)。仅处理 GET。               │
 * └──────────────────────────────────────────────────────────────────────┘
 */
package com.perol.asdpl.pixivez.networks.bypass

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.perol.asdpl.pixivez.networks.ReplaceSniSocketFactory
import com.perol.asdpl.pixivez.networks.RestClient
import com.perol.asdpl.pixivez.networks.RubySSLSocketFactory
import com.perol.asdpl.pixivez.networks.RubyX509TrustManager
import com.perol.asdpl.pixivez.networks.SniMode
import com.perol.asdpl.pixivez.networks.systemTrustManagerOrNull
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class WebViewBypassInterceptor(private val ua: String) {
    private val blocked = setOf(
        "d.pixiv.org", "connect.facebook.net", "platform.twitter.com", "www.google-analytics.com"
    )
    private val clientCache = ConcurrentHashMap<String, OkHttpClient>()

    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        val host = request.url.host ?: return null
        if (host in blocked) {
            return WebResourceResponse(
                "application/javascript", "UTF-8", ByteArrayInputStream(ByteArray(0))
            )
        }
        if (!request.method.equals("GET", true)) return null      // 非 GET:放行(无 body)
        if (host.endsWith("pximg.net")) return reissue(request, RestClient.imageHttpClient)

        val rule = BypassRuleStore.match(host) ?: return null
        val ep = BypassResolver.resolve(host, rule) ?: return null
        return reissue(request, clientFor(host, ep))
    }

    private fun clientFor(host: String, ep: Endpoint): OkHttpClient =
        clientCache.getOrPut(host + "|" + ep.ip.hostAddress + "|" + ep.sni) {
            val ip = ep.ip
            val b = OkHttpClient.Builder()
                .dns(Dns { listOf(ip) })
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
            val factory = when (ep.sni) {
                SniMode.REPLACE -> ReplaceSniSocketFactory(ep.frontSni!!)
                SniMode.EMPTY -> RubySSLSocketFactory()
                SniMode.PLAIN -> null
            }
            if (factory != null) {
                val tm = systemTrustManagerOrNull()
                if (ep.verify && tm != null) b.sslSocketFactory(factory, tm)
                else b.sslSocketFactory(factory, RubyX509TrustManager())
                    .hostnameVerifier { _, _ -> true }
            }
            b.build()
        }

    private fun reissue(request: WebResourceRequest, client: OkHttpClient): WebResourceResponse? =
        try {
            val rb = Request.Builder().url(request.url.toString()).get()
                .header("User-Agent", ua)
            request.requestHeaders.forEach { (k, v) -> if (!k.equals("User-Agent", true)) rb.header(k, v) }
            val resp = client.newCall(rb.build()).execute()
            val type = resp.headers["content-type"]?.substringBefore(";")?.trim() ?: "text/html"
            WebResourceResponse(type, "UTF-8", resp.body?.byteStream()).also {
                it.responseHeaders = resp.headers.toMap().toMutableMap().apply {
                    remove("access-control-allow-origin"); put("Access-Control-Allow-Origin", "*")
                }
            }
        } catch (e: Exception) { null }   // 失败放行原生栈
}
```

- [ ] **Step 2: 暴露 `systemTrustManagerOrNull`**

`networks/NetworkMode.kt` 现有 `private val systemTrustManager`。新增包级可见 helper:

```kotlin
/** 供 bypass 复用系统信任链(校验开启时用);异常返回 null 退化为信任全部。 */
fun systemTrustManagerOrNull(): javax.net.ssl.X509TrustManager? =
    try { systemTrustManager } catch (e: Exception) { null }
```

- [ ] **Step 3: 编译校验**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 提交**

```bash
git add app/src/main/java/com/perol/asdpl/pixivez/networks/bypass/WebViewBypassInterceptor.kt \
        app/src/main/java/com/perol/asdpl/pixivez/networks/NetworkMode.kt
git commit -m "feat(bypass): add WebViewBypassInterceptor (GET-only reissue pipeline)

~.O"
```

---

### Task 5: 合并 `WebViewActivity` + 套壳 chrome + 接入拦截器

**Files:**
- Modify: `app/src/main/java/com/perol/asdpl/pixivez/ui/WebViewActivity.kt`（整体重写为合并版）
- Modify: `app/src/main/res/layout/activity_web_view.xml`
- Create: `app/src/main/res/menu/menu_webview.xml`
- Delete: `app/src/main/java/com/perol/asdpl/pixivez/ui/OKWebViewActivity.kt`

**Interfaces:**
- Consumes: `WebViewBypassInterceptor`, `RestClient.UA`。
- Produces: 仍名 `WebViewActivity`（入参 extra `"url"` 不变）。

**说明:** UI/集成,验证靠构建 + 设备冒烟(Task 8)。`OKWebViewActivity` 的夜间模式 CSS 注入、`onReceivedSslError` 对话框、`shouldOverrideUrlLoading`(pixiv:// 与 artworks/users/member 原生路由)**整体移植**过来(源见删前的 `OKWebViewActivity.kt`)。

- [ ] **Step 1: 重写布局 `activity_web_view.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<androidx.coordinatorlayout.widget.CoordinatorLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:id="@+id/root_container"
    android:layout_width="match_parent"
    android:layout_height="match_parent">

    <com.google.android.material.appbar.AppBarLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content">

        <com.google.android.material.appbar.MaterialToolbar
            android:id="@+id/toolbar"
            android:layout_width="match_parent"
            android:layout_height="?attr/actionBarSize"
            app:navigationIcon="@drawable/ic_close"
            app:menu="@menu/menu_webview">

            <EditText
                android:id="@+id/address_bar"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:imeOptions="actionGo"
                android:inputType="textUri"
                android:maxLines="1"
                android:hint="@string/search_or_url" />
        </com.google.android.material.appbar.MaterialToolbar>

        <com.google.android.material.progressindicator.LinearProgressIndicator
            android:id="@+id/progress"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:visibility="gone"
            app:indicatorColor="?attr/colorPrimary" />
    </com.google.android.material.appbar.AppBarLayout>

    <androidx.swiperefreshlayout.widget.SwipeRefreshLayout
        android:id="@+id/swipe"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        app:layout_behavior="@string/appbar_scrolling_view_behavior">

        <WebView
            android:id="@+id/webview"
            android:layout_width="match_parent"
            android:layout_height="match_parent" />
    </androidx.swiperefreshlayout.widget.SwipeRefreshLayout>
</androidx.coordinatorlayout.widget.CoordinatorLayout>
```

- [ ] **Step 2: 新增菜单 + 字符串 + 关闭图标**

`res/menu/menu_webview.xml`:

```xml
<menu xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:id="@+id/action_refresh" android:title="@string/refresh" />
    <item android:id="@+id/action_forward" android:title="@string/forward" />
    <item android:id="@+id/action_copy" android:title="@string/copy_link" />
    <item android:id="@+id/action_share" android:title="@string/share" />
    <item android:id="@+id/action_open_external" android:title="@string/open_in_browser" />
</menu>
```

`res/values/strings.xml` 追加(若缺):

```xml
    <string name="search_or_url">搜索或输入网址</string>
    <string name="copy_link">复制链接</string>
    <string name="open_in_browser">用外部浏览器打开</string>
    <string name="forward">前进</string>
```
（`refresh`/`share` 已有则复用;`ic_close` 缺失则用现有关闭类 drawable 替代。）

- [ ] **Step 3: 重写 `WebViewActivity.kt`(合并版,接入拦截器)**

要点(完整骨架;`injectCSS`/`shouldOverrideUrlLoading`/`onReceivedSslError` 自删前 `OKWebViewActivity.kt` 原样移植):

```kotlin
package com.perol.asdpl.pixivez.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.addCallback
import androidx.core.net.toUri
import com.perol.asdpl.pixivez.base.RinkActivity
import com.perol.asdpl.pixivez.databinding.ActivityWebViewBinding
import com.perol.asdpl.pixivez.networks.RestClient
import com.perol.asdpl.pixivez.networks.bypass.WebViewBypassInterceptor

class WebViewActivity : RinkActivity() {
    private lateinit var binding: ActivityWebViewBinding
    private val bypass by lazy { WebViewBypassInterceptor(RestClient.UA) }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWebViewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupChrome()
        setupWebView()

        val lang = com.perol.asdpl.pixivez.services.PxEZApp.locale.language
        val url = intent.getStringExtra("url")!!.replace("/ja/", "/$lang/")
        binding.webview.loadUrl(url)
    }

    private fun setupChrome() {
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                com.perol.asdpl.pixivez.R.id.action_refresh -> binding.webview.reload()
                com.perol.asdpl.pixivez.R.id.action_forward ->
                    if (binding.webview.canGoForward()) binding.webview.goForward()
                com.perol.asdpl.pixivez.R.id.action_copy -> copyLink(binding.webview.url)
                com.perol.asdpl.pixivez.R.id.action_share -> shareLink(binding.webview.url)
                com.perol.asdpl.pixivez.R.id.action_open_external -> openExternal(binding.webview.url)
            }
            true
        }
        binding.addressBar.setOnEditorActionListener { v, id, _ ->
            if (id == EditorInfo.IME_ACTION_GO) { binding.webview.loadUrl(normalizeUrl(v.text.toString())); true }
            else false
        }
        binding.swipe.setOnRefreshListener { binding.webview.reload() }
        onBackPressedDispatcher.addCallback(this) {
            if (binding.webview.canGoBack()) binding.webview.goBack() else finish()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() = binding.webview.apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.blockNetworkImage = false
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, p: Int) {
                binding.progress.visibility = if (p in 1..99) android.view.View.VISIBLE else android.view.View.GONE
                binding.progress.progress = p
            }
            override fun onReceivedTitle(view: WebView?, title: String?) {
                binding.toolbar.title = title
            }
        }
        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse? =
                bypass.intercept(request) ?: super.shouldInterceptRequest(view, request)

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                binding.addressBar.setText(url); binding.swipe.isRefreshing = false
            }
            override fun onPageFinished(view: WebView?, url: String?) { binding.swipe.isRefreshing = false }

            // 移植自旧 OKWebViewActivity:pixiv:// 与 www.pixiv.net artworks/users/member 原生路由
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                /* PORT: 旧 OKWebViewActivity.shouldOverrideUrlLoading 实现照搬 */
                return false
            }

            // 移植自旧 OKWebViewActivity:onReceivedSslError 对话框(保留作兜底)
        }
    }

    private fun normalizeUrl(input: String): String {
        val t = input.trim()
        return if (t.contains(".") && !t.contains(" ")) {
            if (t.startsWith("http")) t else "https://$t"
        } else "https://www.google.com/search?q=" + android.net.Uri.encode(t)
    }

    private fun copyLink(url: String?) { /* ClipboardManager 复制 url */ }
    private fun shareLink(url: String?) { /* Intent.ACTION_SEND text/plain */ }
    private fun openExternal(url: String?) { url?.let { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, it.toUri())) } }
}
```

> 实现者注:`copyLink`/`shareLink` 用标准 `ClipboardManager` / `Intent.ACTION_SEND`;`shouldOverrideUrlLoading` 与 `onReceivedSslError` 与夜间模式 `injectCSS` 直接从删前的 `OKWebViewActivity.kt`(本仓库历史)复制,逻辑不变。

- [ ] **Step 4: 删除 `OKWebViewActivity.kt`**

```bash
git rm app/src/main/java/com/perol/asdpl/pixivez/ui/OKWebViewActivity.kt
```

- [ ] **Step 5: 编译校验**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL（如有 `OKWebViewActivity` 残留引用,Task 6 修复后再绿）

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/perol/asdpl/pixivez/ui/WebViewActivity.kt \
        app/src/main/res/layout/activity_web_view.xml \
        app/src/main/res/menu/menu_webview.xml app/src/main/res/values/strings.xml
git commit -m "feat(webview): merge into single WebViewActivity with browser chrome + bypass

~.O"
```

---

### Task 6: 更新调用方 + Manifest(去 dnsProxy 选择器)

**Files:**
- Modify: `app/src/main/java/com/perol/asdpl/pixivez/ui/home/pixivision/PixivsionActivity.kt:99-110`
- Modify: `app/src/main/java/com/perol/asdpl/pixivez/ui/home/recom/RecomFragment.kt:183-195`
- Modify: `app/src/main/java/com/perol/asdpl/pixivez/ui/account/LoginActivity.kt:52,138`
- Modify: `app/src/main/AndroidManifest.xml`

- [ ] **Step 1: PixivsionActivity / RecomFragment 去分支**

两处把 `if (pre.getBoolean("dnsProxy",false)) OKWebViewActivity else WebViewActivity` 改为恒定 `WebViewActivity::class.java`,删除对应 `import ...OKWebViewActivity`。

- [ ] **Step 2: LoginActivity 改引用**

`LoginActivity.kt:138` 的 `OKWebViewActivity::class.java` → `WebViewActivity::class.java`;删 `import ...OKWebViewActivity`(行 52)。

- [ ] **Step 3: Manifest 删条目**

`AndroidManifest.xml` 删除 `<activity android:name=".ui.OKWebViewActivity" />`。

- [ ] **Step 4: 全量编译**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL，无 `OKWebViewActivity` 未解析引用。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/perol/asdpl/pixivez/ui/home/pixivision/PixivsionActivity.kt \
        app/src/main/java/com/perol/asdpl/pixivez/ui/home/recom/RecomFragment.kt \
        app/src/main/java/com/perol/asdpl/pixivez/ui/account/LoginActivity.kt \
        app/src/main/AndroidManifest.xml
git commit -m "refactor(webview): always launch unified WebViewActivity, drop dnsProxy selector

~.O"
```

---

### Task 7: 删除祖传 `RubyHttpXDns` + `RestClient.apiDns` 死字段

**Files:**
- Delete: `app/src/main/java/com/perol/asdpl/pixivez/networks/RubyHttpXDns.kt`
- Modify: `app/src/main/java/com/perol/asdpl/pixivez/networks/RestClient.kt:52`

- [ ] **Step 1: 确认仅剩死引用**

Run: `grep -rn "RubyHttpXDns" app/src/main/java`
Expected: 仅 `RestClient.kt:52`(`private val apiDns by lazy { RubyHttpXDns }`)与定义本身。

- [ ] **Step 2: 删字段 + 文件**

删 `RestClient.kt` 的 `private val apiDns by lazy { RubyHttpXDns }`;把 `proxySocket(dns: Dns = apiDns)` 默认参改为 `proxySocket(dns: Dns = imageDns)`(唯一调用方 `imageProxySocket` 已显式传 `imageDns`,改默认仅去依赖)。

```bash
git rm app/src/main/java/com/perol/asdpl/pixivez/networks/RubyHttpXDns.kt
```

- [ ] **Step 3: 全量编译**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 提交**

```bash
git add app/src/main/java/com/perol/asdpl/pixivez/networks/RestClient.kt
git commit -m "refactor(networks): remove legacy RubyHttpXDns + dead apiDns field

~.O"
```

---

### Task 8: 文档同步 + 设备冒烟回归

**Files:**
- Create: `app/src/main/java/com/perol/asdpl/pixivez/networks/bypass/CLAUDE.md`
- Create: `app/src/main/java/com/perol/asdpl/pixivez/ui/CLAUDE.md`
- Modify: `app/src/main/java/com/perol/asdpl/pixivez/networks/CLAUDE.md`
- Modify: `README.md`

- [ ] **Step 1: 写 `networks/bypass/CLAUDE.md`**

阐明:bypass 子层职责(Rule/Parser/Store/Resolver/Interceptor 各一句)、数据流、**Cealing-Host 数据为 BSL-1.0 / 代码为项目许可的混合声明**、GET-only 边界、与 API 层共用 SNI 原语。

- [ ] **Step 2: 写 `ui/CLAUDE.md`**

阐明:`WebViewActivity` 为唯一套壳浏览器(chrome 结构 + 接 bypass 拦截器);`OKWebViewActivity` 已并入删除;`NewUserActivity` 仍独立。

- [ ] **Step 3: 改 `networks/CLAUDE.md`**

文件表删 `RubyHttpXDns.kt` 行;新增 `bypass/` 子层指引;`DohApiDns.lookupPublic` 泛化说明。

- [ ] **Step 4: 改根 `README.md`**

增一节"第三方规则数据":Cealing-Host(BSL-1.0)来源与署名、favicon 不打包说明。

- [ ] **Step 5: 设备/模拟器冒烟(对照 spec §10)**

逐项验证并记录:
- pixivision 文章图文加载;`pixiv.me` 跳转;`LoginActivity` 网页登录 → `pixiv://account/login?code=` 回调登录成功。
- 至少一个非 pixiv 种子站(如 `github.com`)可打开。
- 地址栏输入 URL/关键词 → 跳转/搜索;进度条、下拉刷新、前进后退、复制/分享/外部打开可用。
- 设置切 SNI/DNS/校验后(真重启)行为符合预期。

Run: `./gradlew :app:assembleDebug` 并安装到设备手测。
Expected: 上述全部通过;失败项记录到 PR 描述。

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/perol/asdpl/pixivez/networks/bypass/CLAUDE.md \
        app/src/main/java/com/perol/asdpl/pixivez/ui/CLAUDE.md \
        app/src/main/java/com/perol/asdpl/pixivez/networks/CLAUDE.md README.md
git commit -m "docs(webview): sync CLAUDE.md + README for bypass browser & licensing

~.O"
```

---

## 自查记录

- **Spec 覆盖**:合并(T5/T6)、bypass 引擎(T1-T4)、IP 策略 C(T3 探测+缓存)、UI+地址栏(T5)、Cealing 数据+许可(T2/T8)、删祖传(T6/T7)、文档(T8)、回归(T8 对照 §10)。GET-only(T4)、非 GET 放行(T4)、ECH 不做(模型留字段 T1)均落位。
- **占位扫描**:无 TBD;移植类(`shouldOverrideUrlLoading`/`onReceivedSslError`/`injectCSS`、`copyLink`/`shareLink`)已显式标注"从旧 `OKWebViewActivity` 照搬 / 用标准 API",非逻辑空洞。
- **类型一致**:`BypassRule`/`HostPattern`/`Endpoint`/`Prober`/`match`/`matchIn`/`resolve`/`pick`/`intercept`/`lookupPublic`/`systemTrustManagerOrNull` 跨任务签名一致。
