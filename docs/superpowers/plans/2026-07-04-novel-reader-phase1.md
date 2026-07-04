# 小说阅读页 Phase 0 遗留 + Phase 1 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 固化正文解析行为(单测入 CI),并把阅读页升级为图文混排、
标记渲染、可跳转、带进度记忆与阅读主题的完整体验。

**Architecture:** 解析管线拆三层——`NovelWebResponse`(webview JSON,含
images/illusts 图源映射)→ `chunkNovel`(纯函数,页界分块 + 图片块抽取,
URL 经注入的 resolver 解析)→ `tokenize`(纯函数,文本块内标记 → token,
bind 时转 Spannable)。纯函数全部 JVM 单测覆盖;Android 侧(adapter/
activity)靠 CI 编译 + 真机冒烟。

**Tech Stack:** Kotlin + kotlinx.serialization(`ServiceFactory.gson` 是
Json 实例)、JUnit4、Glide、RecyclerView/FragmentPagerAdapter。

## Global Constraints

- 本机无 Android SDK,`./gradlew` 不可运行:一切编译/测试验证走 CI。
  开发分支 `ci/novel-reader-phase1`(`ci/**` 是 push 触发分支,`feat/**` 不触发)。
- push 需切账号:`gh auth switch --user ultranity` → `git -c credential.helper='!gh auth git-credential' push ...` → 切回 `Lucas-plaud`。
- 新增用户可见字符串必须同时补 4 个 locale:`values/`、`values-en/`、`values-ja/`、`values-zh-rTW/`。
- 注释中文、只写现状(不写变更史);commit 末尾 `~.O`。
- 单文件 ≤1000 行;`ui/novel/NovelMarkup.kt` 保持零 Android import(JVM 可测的根基)。
- 正则解析容错优先:未匹配的标记原样显示,不崩。

---

### Task 1: CI 单测步骤 + 现有解析行为固化

**Files:**
- Modify: `.github/workflows/android-debug.yml`(assemble 步骤前插入)
- Create: `app/src/test/java/com/perol/asdpl/pixivez/ui/novel/NovelParseTest.kt`

**Interfaces:**
- Consumes: `parseWebNovel(html): NovelWebResponse?`、`renderNovelText(raw): String`、`renderNovelChunks(raw): List<String>`(现有,`NovelViewModel.kt` 顶层函数)
- Produces: CI `testGitDebugUnitTest` 步骤;后续任务的测试自动被跑

- [ ] **Step 1: 写测试**(固化现状,作为后续重构的安全网)

```kotlin
package com.perol.asdpl.pixivez.ui.novel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelParseTest {
    // 模拟 /webview/v2/novel HTML 内嵌 JSON(锚点:novel: {...}, isOwnWork)
    private val html = """
        <html><script>pixiv.novel = { novel: {"id":"123","title":"T",
        "text":"第一页[newpage]第二页","seriesNavigation":{
        "prevNovel":{"id":1,"viewable":true,"title":"p"},"nextNovel":null}},
        isOwnWork: false };</script></html>
    """.trimIndent()

    @Test fun parse_extracts_text_and_series() {
        val web = parseWebNovel(html)!!
        assertEquals("123", web.id)
        assertEquals("第一页[newpage]第二页", web.text)
        assertEquals(1, web.seriesNavigation?.prevNovel?.id)
        assertNull(web.seriesNavigation?.nextNovel)
    }

    @Test fun parse_returns_null_without_anchor() {
        assertNull(parseWebNovel("<html>no embedded json</html>"))
    }

    @Test fun render_text_transforms_all_markers() {
        assertEquals(
            "\n标题\n汉字(かんじ)链接",
            renderNovelText("[chapter:标题][[rb:汉字>かんじ]][[jumpuri:链接>https://x.example]]")
        )
        assertEquals("a\n\nb", renderNovelText("a[newpage]b"))
        assertEquals("ab", renderNovelText("a[pixivimage:1-2][uploadedimage:3][jump:4]b"))
    }

    @Test fun chunks_split_by_newpage_and_limit() {
        assertEquals(listOf("a", "b"), renderNovelChunks("a[newpage]b"))
        val long = (1..40).joinToString("\n") { "x".repeat(100) } // 单页 >3000 字符
        val chunks = renderNovelChunks(long)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= 3000 })
    }

    @Test fun chunks_drop_blank_pages() {
        assertEquals(listOf("a"), renderNovelChunks("a[newpage]  \n "))
    }
}
```

- [ ] **Step 2: CI 加单测步骤**(`Grant execute permission` 与 assemble 之间)

```yaml
      - name: Run unit tests
        run: ./gradlew "test${FLAVOR^}DebugUnitTest" --stacktrace
```

- [ ] **Step 3: Commit**

```bash
git add app/src/test .github/workflows/android-debug.yml
git commit -m "test(novel): freeze parse/render behavior; run unit tests in CI

~.O"
```

注意:此步骤会让 CI 首次执行 `networks/bypass` 下已有单测。若它们失败,
那是被固化的既有问题——修测试或上报,不许删测试。

---

### Task 2: NovelWebResponse 扩展 images/illusts 图源映射

**Files:**
- Modify: `app/src/main/java/com/perol/asdpl/pixivez/data/model/Novel.kt`(`NovelWebResponse` 处)
- Modify: `app/src/test/java/com/perol/asdpl/pixivez/ui/novel/NovelParseTest.kt`(追加用例)

**Interfaces:**
- Produces:`NovelWebResponse.images: Map<String, NovelImage>?`(uploadedimage id → urls)、`NovelWebResponse.illusts: Map<String, NovelIllustRef>?`(pixivimage id → 插画 urls)。
  取图优先级:uploaded `urls.mw480 ?: original`;pixiv `images.medium ?: original`。

- [ ] **Step 1: 追加测试**

```kotlin
    @Test fun parse_resolves_image_maps() {
        val h = """x novel: {"id":"9","text":"t",
            "images":{"77":{"urls":{"480mw":"https://i.pximg.net/u480.jpg","original":"https://i.pximg.net/uo.jpg"}}},
            "illusts":{"55":{"illust":{"images":{"medium":"https://i.pximg.net/m.jpg"}}}}},
            isOwnWork: true"""
        val web = parseWebNovel(h)!!
        assertEquals("https://i.pximg.net/u480.jpg", web.images?.get("77")?.urls?.mw480)
        assertEquals("https://i.pximg.net/m.jpg", web.illusts?.get("55")?.illust?.images?.medium)
    }
```

- [ ] **Step 2: 扩展模型**(`Novel.kt`,`NovelWebResponse` 定义替换为)

```kotlin
// GET /webview/v2/novel 内嵌 JSON 的正文模型:字段为 camelCase、id 为字符串;
// images = 用户上传图([uploadedimage:id]),illusts = 引用插画([pixivimage:id]),
// 其余字段靠 Json.ignoreUnknownKeys 容错
@Serializable
class NovelWebResponse(
    val id: String = "",
    val title: String = "",
    val text: String = "",
    val seriesNavigation: SeriesNavigation? = null,
    val images: Map<String, NovelImage>? = null,
    val illusts: Map<String, NovelIllustRef>? = null
)

@Serializable
class NovelImage(val urls: NovelImageUrls = NovelImageUrls())

@Serializable
class NovelImageUrls(
    @SerialName("480mw") val mw480: String? = null,
    val original: String? = null
)

@Serializable
class NovelIllustRef(val illust: NovelIllustBody? = null)

@Serializable
class NovelIllustBody(val images: NovelIllustImages = NovelIllustImages())

@Serializable
class NovelIllustImages(
    val medium: String? = null,
    val original: String? = null
)
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/perol/asdpl/pixivez/data/model/Novel.kt app/src/test
git commit -m "feat(novel): parse images/illusts url maps from webview json

~.O"
```

---

### Task 3: NovelChunk 模型 + 图文分块器 chunkNovel

**Files:**
- Create: `app/src/main/java/com/perol/asdpl/pixivez/ui/novel/NovelMarkup.kt`(零 Android import)
- Create: `app/src/test/java/com/perol/asdpl/pixivez/ui/novel/NovelMarkupTest.kt`

**Interfaces:**
- Produces:
  - `sealed class NovelChunk { val page: Int }`;`NovelChunk.Text(page, text)`(text 保留 chapter/rb/jumpuri/jump 标记,供 bind 时 tokenize);`NovelChunk.Image(page, url: String?, illustId: Int?)`
  - `fun chunkNovel(raw: String, resolvePixiv: (Int) -> String? = { null }, resolveUploaded: (String) -> String? = { null }): List<NovelChunk>`
  - page 从 0 起,`[newpage]` 递增;`[jump:N]` 的 N 是 1 起页号 → 目标 page = N-1

- [ ] **Step 1: 写测试**

```kotlin
package com.perol.asdpl.pixivez.ui.novel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelMarkupTest {
    @Test fun chunk_tracks_page_index() {
        val c = chunkNovel("p0[newpage]p1[newpage]p2")
        assertEquals(listOf(0, 1, 2), c.map { it.page })
        assertEquals("p1", (c[1] as NovelChunk.Text).text)
    }

    @Test fun chunk_extracts_images_with_resolved_urls() {
        val c = chunkNovel(
            "前文[pixivimage:55]中文[uploadedimage:77]后文",
            resolvePixiv = { if (it == 55) "https://i/m.jpg" else null },
            resolveUploaded = { if (it == "77") "https://i/u.jpg" else null },
        )
        assertEquals(5, c.size)
        val img1 = c[1] as NovelChunk.Image
        assertEquals("https://i/m.jpg", img1.url); assertEquals(55, img1.illustId)
        val img2 = c[3] as NovelChunk.Image
        assertEquals("https://i/u.jpg", img2.url); assertNull(img2.illustId)
        assertEquals(listOf("前文", "中文", "后文"),
            c.filterIsInstance<NovelChunk.Text>().map { it.text })
    }

    @Test fun chunk_keeps_pixivimage_page_suffix_and_unresolved_url() {
        val c = chunkNovel("[pixivimage:55-2]")
        val img = c.single() as NovelChunk.Image
        assertEquals(55, img.illustId)   // -页码后缀取基础 id
        assertNull(img.url)              // 未解析出 url,由 UI 显示占位
    }

    @Test fun chunk_text_keeps_inline_markers() {
        val c = chunkNovel("[chapter:一][[rb:漢>かん]]正文")
        assertEquals("[chapter:一][[rb:漢>かん]]正文", (c.single() as NovelChunk.Text).text)
    }

    @Test fun chunk_splits_long_page_within_same_page_index() {
        val long = (1..40).joinToString("\n") { "x".repeat(100) }
        val c = chunkNovel("short[newpage]$long")
        assertTrue(c.size > 2)
        assertTrue(c.drop(1).all { it.page == 1 })
    }

    @Test fun chunk_drops_blank_but_keeps_images_of_blank_page() {
        val c = chunkNovel("a[newpage][uploadedimage:1][newpage]b")
        assertEquals(3, c.size)
        assertTrue(c[1] is NovelChunk.Image)
    }
}
```

- [ ] **Step 2: 实现 NovelMarkup.kt(第一部分)**

```kotlin
package com.perol.asdpl.pixivez.ui.novel

// ============================================================
// 正文标记解析:纯 Kotlin、零 Android 依赖,JVM 单测直跑。
// 管线:chunkNovel 切块(页界/图片/长页)→ Text 块 bind 时 tokenize
// ============================================================

// 单块字符上限:单个 TextView 的 StaticLayout 测量在主线程,块太大长文必卡
private const val CHUNK_LIMIT = 3000

// page 为 [newpage] 页号(0 起),[jump:N] 目标 = 首个 page==N-1 的块
sealed class NovelChunk {
    abstract val page: Int
    data class Text(override val page: Int, val text: String) : NovelChunk()
    // illustId 非空 = [pixivimage:](可点跳插画);url 空 = 未解析,UI 给占位
    data class Image(override val page: Int, val url: String?, val illustId: Int?) : NovelChunk()
}

private val IMAGE_MARK = Regex("""\[pixivimage:(\d+)(?:-\d+)?]|\[uploadedimage:(\d+)]""")

fun chunkNovel(
    raw: String,
    resolvePixiv: (Int) -> String? = { null },
    resolveUploaded: (String) -> String? = { null },
): List<NovelChunk> {
    val chunks = mutableListOf<NovelChunk>()
    raw.split("[newpage]").forEachIndexed { page, body ->
        var last = 0
        for (m in IMAGE_MARK.findAll(body)) {
            addTextChunks(chunks, page, body.substring(last, m.range.first))
            val pixivId = m.groups[1]?.value?.toInt()
            chunks += if (pixivId != null) {
                NovelChunk.Image(page, resolvePixiv(pixivId), pixivId)
            } else {
                NovelChunk.Image(page, resolveUploaded(m.groups[2]!!.value), null)
            }
            last = m.range.last + 1
        }
        addTextChunks(chunks, page, body.substring(last))
    }
    return chunks
}

// 文本段过滤空白后按 CHUNK_LIMIT 切段落,页号透传
private fun addTextChunks(out: MutableList<NovelChunk>, page: Int, text: String) {
    val t = text.trim()
    if (t.isBlank()) return
    val parts = if (t.length <= CHUNK_LIMIT) listOf(t) else splitByParagraph(t)
    parts.filterTo(mutableListOf()) { it.isNotBlank() }
        .forEach { out += NovelChunk.Text(page, it) }
}

private fun splitByParagraph(page: String): List<String> {
    val chunks = mutableListOf<String>()
    val sb = StringBuilder()
    for (line in page.lineSequence()) {
        if (sb.isNotEmpty() && sb.length + line.length > CHUNK_LIMIT) {
            chunks += sb.toString().trim()
            sb.clear()
        }
        sb.appendLine(line)
    }
    if (sb.isNotBlank()) chunks += sb.toString().trim()
    return chunks
}
```

同时从 `NovelViewModel.kt` 删除旧的 `CHUNK_LIMIT`、`splitByParagraph`、
`renderNovelChunks`(职责移交本文件;`renderNovelText`、`parseWebNovel`
留在原处——前者仍是纯文本兜底,后者贴着网络层)。`NovelParseTest` 中
`chunks_*` 两个用例改为断言 `chunkNovel` 等价行为后删除(Task 3 的
`NovelMarkupTest` 已覆盖),`render_text_*`、`parse_*` 用例保留。

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/perol/asdpl/pixivez/ui/novel/ app/src/test
git commit -m "feat(novel): page-aware chunker with inline image extraction

~.O"
```

---

### Task 4: 文本标记 tokenizer

**Files:**
- Modify: `app/src/main/java/com/perol/asdpl/pixivez/ui/novel/NovelMarkup.kt`(追加)
- Modify: `app/src/test/java/com/perol/asdpl/pixivez/ui/novel/NovelMarkupTest.kt`(追加)

**Interfaces:**
- Produces:
  - `sealed class NovelToken`:`Plain(text)` / `Chapter(title)` / `Ruby(base, rt)` / `JumpUri(title, url)` / `JumpPage(page)`(page 为 1 起原值)
  - `fun tokenize(text: String): List<NovelToken>`;未识别标记留在 Plain 原样显示

- [ ] **Step 1: 追加测试**

```kotlin
    @Test fun tokenize_plain_only() {
        assertEquals(listOf(NovelToken.Plain("纯文本")), tokenize("纯文本"))
    }

    @Test fun tokenize_all_marker_kinds() {
        val t = tokenize("A[chapter:章]B[[rb:漢>かん]]C[[jumpuri:标>https://e.x]]D[jump:3]E")
        assertEquals(
            listOf(
                NovelToken.Plain("A"), NovelToken.Chapter("章"),
                NovelToken.Plain("B"), NovelToken.Ruby("漢", "かん"),
                NovelToken.Plain("C"), NovelToken.JumpUri("标", "https://e.x"),
                NovelToken.Plain("D"), NovelToken.JumpPage(3),
                NovelToken.Plain("E"),
            ),
            t
        )
    }

    @Test fun tokenize_keeps_malformed_marker_as_plain() {
        assertEquals(listOf(NovelToken.Plain("[chapter:未闭合")), tokenize("[chapter:未闭合"))
    }
```

- [ ] **Step 2: 实现(NovelMarkup.kt 追加)**

```kotlin
// Text 块 bind 时的行内标记;Ruby 显示为 base(rt),Jump* 转 ClickableSpan
sealed class NovelToken {
    data class Plain(val text: String) : NovelToken()
    data class Chapter(val title: String) : NovelToken()
    data class Ruby(val base: String, val rt: String) : NovelToken()
    data class JumpUri(val title: String, val url: String) : NovelToken()
    data class JumpPage(val page: Int) : NovelToken()
}

private val TOKEN_MARK = Regex(
    """\[chapter:(.*?)]|\[\[rb:(.*?)>(.*?)]]|\[\[jumpuri:(.*?)>(.*?)]]|\[jump:(\d+)]""",
    RegexOption.DOT_MATCHES_ALL
)

fun tokenize(text: String): List<NovelToken> {
    val tokens = mutableListOf<NovelToken>()
    var last = 0
    for (m in TOKEN_MARK.findAll(text)) {
        if (m.range.first > last) tokens += NovelToken.Plain(text.substring(last, m.range.first))
        val g = m.groups
        tokens += when {
            g[1] != null -> NovelToken.Chapter(g[1]!!.value.trim())
            g[2] != null -> NovelToken.Ruby(g[2]!!.value, g[3]?.value ?: "")
            g[4] != null -> NovelToken.JumpUri(g[4]!!.value.trim(), g[5]?.value?.trim() ?: "")
            else -> NovelToken.JumpPage(g[6]!!.value.toInt())
        }
        last = m.range.last + 1
    }
    if (last < text.length) tokens += NovelToken.Plain(text.substring(last))
    return tokens
}
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/perol/asdpl/pixivez/ui/novel/ app/src/test
git commit -m "feat(novel): tokenize inline markers for span rendering

~.O"
```

- [ ] **Step 4: 推分支跑 CI(纯 JVM 部分的验证点)**

```bash
git checkout -b ci/novel-reader-phase1
gh auth switch --user ultranity
git -c credential.helper='!gh auth git-credential' push -u origin ci/novel-reader-phase1
gh auth switch --user Lucas-plaud
gh run list --repo ultranity/Pix-EzViewer --limit 1   # 确认触发,记下 run id
```

等待 run 完成(`gh run view <id> --json status,conclusion`),
期望 conclusion=success(含新增单测)。失败 → 读日志修复后再推,绿灯前不进 Task 5。

---

### Task 5: 阅读页图文渲染 + 跳转

**Files:**
- Modify: `app/src/main/java/com/perol/asdpl/pixivez/ui/novel/NovelViewModel.kt`
- Modify: `app/src/main/java/com/perol/asdpl/pixivez/ui/novel/NovelActivity.kt`(NovelReaderAdapter 在此文件内)
- Create: `app/src/main/res/layout/view_novel_image.xml`

**Interfaces:**
- Consumes: `chunkNovel` / `tokenize` / `NovelChunk` / `NovelToken`(Task 3/4);`NovelWebResponse.images/illusts`(Task 2);`PictureActivity.start(context, id: Int)`;`IntentActivity.start(context, string: String)`
- Produces: `NovelViewModel.chunks: MutableLiveData<List<NovelChunk>>`(类型变更);adapter 回调 `onJumpToPage: (Int) -> Unit`

- [ ] **Step 1: ViewModel 产出 NovelChunk**(`load` 中 chunks 赋值处替换)

```kotlin
            web.value = result
            chunks.value = result?.let { w ->
                withContext(Dispatchers.Default) {
                    chunkNovel(
                        w.text,
                        resolvePixiv = { pid ->
                            w.illusts?.get(pid.toString())?.illust?.images
                                ?.let { it.medium ?: it.original }
                        },
                        resolveUploaded = { iid ->
                            w.images?.get(iid)?.urls?.let { it.mw480 ?: it.original }
                        },
                    )
                }
            } ?: emptyList()
```

同文件 LiveData 声明改:`val chunks = MutableLiveData<List<NovelChunk>>()`。

- [ ] **Step 2: 图片块布局 `view_novel_image.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:paddingStart="16dp"
    android:paddingEnd="16dp"
    android:paddingBottom="24dp">

    <ImageView
        android:id="@+id/novel_image"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:adjustViewBounds="true"
        android:contentDescription="@string/novel"
        android:minHeight="120dp" />

    <TextView
        android:id="@+id/novel_image_fallback"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:gravity="center"
        android:padding="24dp"
        android:textColor="?attr/colorAccent"
        android:visibility="gone"
        tools:text="[pixivimage:123]"
        xmlns:tools="http://schemas.android.com/tools" />
</FrameLayout>
```

- [ ] **Step 3: NovelReaderAdapter 三 viewType + Spannable 渲染**

`NovelActivity.kt` 中 `NovelReaderAdapter` 整体替换为:

```kotlin
// 正文分块适配器:0=头部,其后 Text/Image 两种块。
// Text 块 bind 时 tokenize→Spannable,测量摊到滚动过程,长文不阻塞主线程
private class NovelReaderAdapter(
    private val onJumpToPage: (Int) -> Unit
) : RecyclerView.Adapter<NovelReaderAdapter.VH>() {
    class VH(val binding: ViewBinding) : RecyclerView.ViewHolder(binding.root)

    var novel: Novel? = null
    var chunks: List<NovelChunk> = emptyList()
    var textSize: Float = 16f

    override fun getItemViewType(position: Int) = when {
        position == 0 -> TYPE_HEADER
        chunks[position - 1] is NovelChunk.Image -> TYPE_IMAGE
        else -> TYPE_CHUNK
    }

    override fun getItemCount() = 1 + chunks.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val inflater = LayoutInflater.from(parent.context)
        return VH(
            when (viewType) {
                TYPE_HEADER -> ViewNovelHeaderBinding.inflate(inflater, parent, false)
                TYPE_IMAGE -> ViewNovelImageBinding.inflate(inflater, parent, false)
                else -> ViewNovelChunkBinding.inflate(inflater, parent, false)
            }
        )
    }

    @SuppressLint("SetTextI18n")
    override fun onBindViewHolder(holder: VH, position: Int) {
        when (val b = holder.binding) {
            is ViewNovelHeaderBinding -> bindHeader(b)
            is ViewNovelImageBinding -> bindImage(b, chunks[position - 1] as NovelChunk.Image)
            is ViewNovelChunkBinding -> {
                b.novelChunk.textSize = textSize
                // 复用后的可选中 TextView 长按会失灵,重置一次可选中态恢复
                b.novelChunk.setTextIsSelectable(false)
                b.novelChunk.text = renderSpans(b.novelChunk, (chunks[position - 1] as NovelChunk.Text).text)
                b.novelChunk.setTextIsSelectable(true)
                b.novelChunk.movementMethod = LinkMovementMethod.getInstance()
            }
        }
    }

    private fun bindHeader(b: ViewNovelHeaderBinding) {
        val n = novel ?: return
        b.novelTitle.text = n.title
        b.novelAuthor.text = n.user.name
        b.novelMeta.text = "${n.text_length} · ♥ ${n.total_bookmarks}"
        b.novelTags.text = n.tags.joinToString(" ") { "#${it.name}" }
    }

    private fun bindImage(b: ViewNovelImageBinding, chunk: NovelChunk.Image) {
        if (chunk.url == null) {
            // url 未解析:显示原始标记占位,不阻塞正文
            b.novelImage.visibility = View.GONE
            b.novelImageFallback.visibility = View.VISIBLE
            b.novelImageFallback.text =
                chunk.illustId?.let { "[pixivimage:$it]" } ?: "[uploadedimage]"
        } else {
            b.novelImage.visibility = View.VISIBLE
            b.novelImageFallback.visibility = View.GONE
            Glide.with(b.novelImage).load(chunk.url)
                .transition(withCrossFade()).into(b.novelImage)
        }
        b.root.setOnClickListener {
            chunk.illustId?.let { id -> PictureActivity.start(b.root.context, id) }
        }
    }

    // token → Spannable:chapter 加粗放大居中,ruby 括注,jumpuri/jump 可点
    private fun renderSpans(view: View, text: String): CharSequence {
        val sb = SpannableStringBuilder()
        for (t in tokenize(text)) when (t) {
            is NovelToken.Plain -> sb.append(t.text)
            is NovelToken.Ruby -> sb.append("${t.base}(${t.rt})")
            is NovelToken.Chapter -> {
                val start = sb.length
                sb.append("\n${t.title}\n")
                sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(RelativeSizeSpan(1.25f), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(
                    AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER),
                    start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            is NovelToken.JumpUri -> {
                val start = sb.length
                sb.append(t.title.ifBlank { t.url })
                sb.setSpan(object : ClickableSpan() {
                    override fun onClick(widget: View) = IntentActivity.start(widget.context, t.url)
                }, start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            is NovelToken.JumpPage -> {
                val start = sb.length
                sb.append("▶ P${t.page}")
                sb.setSpan(object : ClickableSpan() {
                    override fun onClick(widget: View) = onJumpToPage(t.page)
                }, start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return sb
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_CHUNK = 1
        private const val TYPE_IMAGE = 2
    }
}
```

新增 import(`NovelActivity.kt`):

```kotlin
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.AlignmentSpan
import android.text.style.ClickableSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.View
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade
import com.perol.asdpl.pixivez.IntentActivity
import com.perol.asdpl.pixivez.databinding.ViewNovelImageBinding
import com.perol.asdpl.pixivez.ui.pic.PictureActivity
```

- [ ] **Step 4: NovelActivity 接跳转回调**

构造处 `private val adapter = NovelReaderAdapter()` 改为:

```kotlin
    private val adapter = NovelReaderAdapter(onJumpToPage = ::scrollToPage)
```

类内新增:

```kotlin
    // [jump:N] N 为 1 起页号;目标 = 首个 page==N-1 的块(header 占位 +1)
    private fun scrollToPage(page: Int) {
        val idx = adapter.chunks.indexOfFirst { it.page == page - 1 }
        if (idx < 0) return
        (binding.novelRecycler.layoutManager as LinearLayoutManager)
            .scrollToPositionWithOffset(1 + idx, 0)
    }
```

import 追加 `androidx.recyclerview.widget.LinearLayoutManager`。
字号变更处 `notifyItemRangeChanged(1, adapter.chunks.size)` 保持不变(Image 块 rebind 无副作用)。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/perol/asdpl/pixivez/ui/novel/ app/src/main/res/layout/
git commit -m "feat(novel): render text markers and inline images in reader

~.O"
```

---

### Task 6: 头部增强(简介/标签跳搜索/作者跳主页/系列名)

**Files:**
- Modify: `app/src/main/res/layout/view_novel_header.xml`
- Modify: `app/src/main/java/com/perol/asdpl/pixivez/ui/novel/NovelActivity.kt`(bindHeader)

**Interfaces:**
- Consumes: `Novel.caption/series/user/tags`;`NovelSearchResultActivity.start(context, keyword: String)`;`UserMActivity.start(context, id: Int)`

- [ ] **Step 1: 布局加 简介 与 系列名**(novel_meta 与 novel_tags 之间插入)

```xml
    <TextView
        android:id="@+id/novel_series"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="4dp"
        android:textSize="13sp"
        android:textStyle="italic"
        android:visibility="gone"
        tools:text="系列:xxx" />

    <TextView
        android:id="@+id/novel_caption"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="8dp"
        android:textSize="14sp"
        android:visibility="gone"
        tools:text="简介" />
```

- [ ] **Step 2: bindHeader 全量替换**

```kotlin
    private fun bindHeader(b: ViewNovelHeaderBinding) {
        val n = novel ?: return
        b.novelTitle.text = n.title
        b.novelAuthor.text = n.user.name
        b.novelAuthor.setOnClickListener {
            UserMActivity.start(b.root.context, n.user.id)
        }
        b.novelMeta.text = "${n.text_length} · ♥ ${n.total_bookmarks}"
        b.novelSeries.visibility = if (n.series != null) View.VISIBLE else View.GONE
        b.novelSeries.text = n.series?.title
        b.novelCaption.visibility = if (n.caption.isBlank()) View.GONE else View.VISIBLE
        b.novelCaption.text = HtmlCompat.fromHtml(n.caption, HtmlCompat.FROM_HTML_MODE_LEGACY)
        // 每个标签一段 ClickableSpan → 小说搜索
        val sb = SpannableStringBuilder()
        for (tag in n.tags) {
            if (sb.isNotEmpty()) sb.append("  ")
            val start = sb.length
            sb.append("#${tag.name}")
            sb.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) =
                    NovelSearchResultActivity.start(widget.context, tag.name)
            }, start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        b.novelTags.text = sb
        b.novelTags.movementMethod = LinkMovementMethod.getInstance()
    }
```

import 追加:`androidx.core.text.HtmlCompat`、`com.perol.asdpl.pixivez.ui.user.UserMActivity`。
(`n.user.id` 若为 Long 则 `UserMActivity.start(b.root.context, n.user.id.toInt())`——
实现时看 `data/model/User.kt` 的 id 类型定夺。)

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/perol/asdpl/pixivez/ui/novel/ app/src/main/res/layout/
git commit -m "feat(novel): caption, clickable tags/author and series name in reader header

~.O"
```

---

### Task 7: 阅读进度记忆

**Files:**
- Create: `app/src/main/java/com/perol/asdpl/pixivez/ui/novel/NovelProgressStore.kt`
- Create: `app/src/test/java/com/perol/asdpl/pixivez/ui/novel/NovelProgressStoreTest.kt`
- Modify: `app/src/main/java/com/perol/asdpl/pixivez/ui/novel/NovelActivity.kt`

**Interfaces:**
- Produces: `NovelProgressStore.save(novelId: Int, position: Int, offset: Int)`、`NovelProgressStore.load(novelId: Int): NovelProgress?`;`@Serializable data class NovelProgress(val position: Int, val offset: Int, val at: Long)`
- 纯函数 `trimLru(map: Map<Int, NovelProgress>, limit: Int): Map<Int, NovelProgress>` 供单测

- [ ] **Step 1: 测试**

```kotlin
package com.perol.asdpl.pixivez.ui.novel

import org.junit.Assert.assertEquals
import org.junit.Test

class NovelProgressStoreTest {
    @Test fun trim_keeps_most_recent_by_timestamp() {
        val m = (1..5).associateWith { NovelProgress(0, 0, at = it.toLong()) }
        val trimmed = trimLru(m, limit = 3)
        assertEquals(setOf(3, 4, 5), trimmed.keys)
    }

    @Test fun trim_noop_under_limit() {
        val m = mapOf(1 to NovelProgress(2, 3, 4L))
        assertEquals(m, trimLru(m, limit = 3))
    }
}
```

- [ ] **Step 2: 实现**

```kotlin
package com.perol.asdpl.pixivez.ui.novel

import com.perol.asdpl.pixivez.networks.ServiceFactory.gson
import com.perol.asdpl.pixivez.services.PxEZApp
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

// 阅读进度:novelId → (块位置, 像素偏移)。JSON 存 SharedPreferences,LRU 上限 200
@Serializable
data class NovelProgress(val position: Int, val offset: Int, val at: Long)

fun trimLru(map: Map<Int, NovelProgress>, limit: Int): Map<Int, NovelProgress> =
    if (map.size <= limit) map
    else map.entries.sortedByDescending { it.value.at }.take(limit).associate { it.key to it.value }

object NovelProgressStore {
    private const val PREF_KEY = "novel_progress"
    private const val LIMIT = 200

    private fun all(): Map<Int, NovelProgress> = runCatching {
        gson.decodeFromString<Map<Int, NovelProgress>>(
            PxEZApp.instance.pre.getString(PREF_KEY, null) ?: return emptyMap()
        )
    }.getOrDefault(emptyMap())

    fun load(novelId: Int): NovelProgress? = all()[novelId]

    fun save(novelId: Int, position: Int, offset: Int) {
        val next = trimLru(
            all() + (novelId to NovelProgress(position, offset, System.currentTimeMillis())),
            LIMIT
        )
        PxEZApp.instance.pre.edit()
            .putString(PREF_KEY, gson.encodeToString(next)).apply()
    }
}
```

- [ ] **Step 3: NovelActivity 存取**

```kotlin
    // 进度恢复只在首次数据就绪时执行一次
    private var progressRestored = false

    override fun onPause() {
        super.onPause()
        val lm = binding.novelRecycler.layoutManager as LinearLayoutManager
        val pos = lm.findFirstVisibleItemPosition()
        if (pos <= 0 || adapter.chunks.isEmpty()) return  // header 处/未加载不记
        val offset = lm.findViewByPosition(pos)?.top ?: 0
        NovelProgressStore.save(novelId, pos, offset)
    }
```

`initObserver` 的 `viewModel.chunks.observe` 回调末尾追加:

```kotlin
            if (!progressRestored && !it.isNullOrEmpty()) {
                progressRestored = true
                NovelProgressStore.load(novelId)?.let { p ->
                    (binding.novelRecycler.layoutManager as LinearLayoutManager)
                        .scrollToPositionWithOffset(p.position, p.offset)
                }
            }
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/perol/asdpl/pixivez/ui/novel/ app/src/test
git commit -m "feat(novel): remember reading position per novel (lru 200)

~.O"
```

---

### Task 8: 阅读主题(背景 + 行距)

**Files:**
- Modify: `app/src/main/res/menu/menu_novel.xml`
- Modify: `app/src/main/java/com/perol/asdpl/pixivez/ui/novel/NovelActivity.kt`
- Modify: `app/src/main/res/values/strings.xml` + `values-en/` + `values-ja/` + `values-zh-rTW/`

**Interfaces:**
- prefs:`novel_bg`(0=跟随主题/1=纸白/2=羊皮/3=纯黑,Int)、`novel_line_space`(Float,默认 1.4f)

- [ ] **Step 1: strings ×4 locale**

`values/strings.xml`(novel 组内追加):

```xml
    <string name="novel_background">阅读背景</string>
    <string name="novel_bg_follow">跟随主题</string>
    <string name="novel_bg_paper">纸白</string>
    <string name="novel_bg_sepia">羊皮</string>
    <string name="novel_bg_black">纯黑</string>
    <string name="novel_line_space">行距</string>
```

`values-en/strings.xml`:

```xml
    <string name="novel_background">Reader background</string>
    <string name="novel_bg_follow">Follow theme</string>
    <string name="novel_bg_paper">Paper</string>
    <string name="novel_bg_sepia">Sepia</string>
    <string name="novel_bg_black">Black</string>
    <string name="novel_line_space">Line spacing</string>
```

`values-ja/strings.xml`:

```xml
    <string name="novel_background">背景色</string>
    <string name="novel_bg_follow">テーマに従う</string>
    <string name="novel_bg_paper">ペーパー</string>
    <string name="novel_bg_sepia">セピア</string>
    <string name="novel_bg_black">ブラック</string>
    <string name="novel_line_space">行間</string>
```

`values-zh-rTW/strings.xml`:

```xml
    <string name="novel_background">閱讀背景</string>
    <string name="novel_bg_follow">跟隨主題</string>
    <string name="novel_bg_paper">紙白</string>
    <string name="novel_bg_sepia">羊皮</string>
    <string name="novel_bg_black">純黑</string>
    <string name="novel_line_space">行距</string>
```

- [ ] **Step 2: 菜单项**(`menu_novel.xml`,share 之前插入)

```xml
    <item
        android:id="@+id/action_novel_bg"
        android:title="@string/novel_background"
        app:showAsAction="never" />
    <item
        android:id="@+id/action_novel_line_space"
        android:title="@string/novel_line_space"
        app:showAsAction="never" />
```

- [ ] **Step 3: NovelActivity 实现**

adapter 增加两个可变染色参数(NovelReaderAdapter 类内):

```kotlin
    // 背景主题联动的正文颜色/行距;null = 跟随 XML 默认
    var chunkTextColor: Int? = null
    var lineSpacing: Float = 1.4f
```

Text 块 bind 时(`b.novelChunk.textSize = textSize` 之后):

```kotlin
                b.novelChunk.setLineSpacing(0f, lineSpacing)
                chunkTextColor?.let { b.novelChunk.setTextColor(it) }
```

NovelActivity:

```kotlin
    // 背景主题:(背景色, 正文色);0=跟随主题走 XML/theme 默认
    private val bgThemes = listOf(
        0x00000000 to null,                       // follow(不覆盖)
        0xFFFAF7EF.toInt() to 0xFF333333.toInt(), // paper
        0xFFF0E4CC.toInt() to 0xFF5B4636.toInt(), // sepia
        0xFF000000.toInt() to 0xFFB0B0B0.toInt(), // black
    )

    private fun applyReaderTheme() {
        val bg = PxEZApp.instance.pre.getInt("novel_bg", 0)
        val (color, textColor) = bgThemes.getOrElse(bg) { bgThemes[0] }
        if (bg != 0) binding.root.setBackgroundColor(color)
        adapter.chunkTextColor = textColor
        adapter.lineSpacing = PxEZApp.instance.pre.getFloat("novel_line_space", 1.4f)
        adapter.notifyItemRangeChanged(1, adapter.chunks.size)
    }
```

`onCreate` 里 `binding.novelRecycler.adapter = adapter` 之后调用 `applyReaderTheme()`
(此时 chunks 为空,notify 无操作,仅设置参数)。

`onOptionsItemSelected` 追加分支:

```kotlin
            R.id.action_novel_bg -> {
                val labels = arrayOf(
                    getString(R.string.novel_bg_follow), getString(R.string.novel_bg_paper),
                    getString(R.string.novel_bg_sepia), getString(R.string.novel_bg_black)
                )
                val cur = PxEZApp.instance.pre.getInt("novel_bg", 0)
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.novel_background)
                    .setSingleChoiceItems(labels, cur) { d, i ->
                        PxEZApp.instance.pre.edit().putInt("novel_bg", i).apply()
                        // 跟随主题需要清掉已染的背景,直接重建最干净
                        recreate()
                        d.dismiss()
                    }.show()
            }
            R.id.action_novel_line_space -> {
                val values = floatArrayOf(1.2f, 1.4f, 1.8f)
                val labels = arrayOf("1.2", "1.4", "1.8")
                val cur = values.indexOfFirst {
                    it == PxEZApp.instance.pre.getFloat("novel_line_space", 1.4f)
                }.coerceAtLeast(0)
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.novel_line_space)
                    .setSingleChoiceItems(labels, cur) { d, i ->
                        PxEZApp.instance.pre.edit().putFloat("novel_line_space", values[i]).apply()
                        applyReaderTheme()
                        d.dismiss()
                    }.show()
            }
```

(此处 `recreate()` 合法:阅读页无 pager,恢复的是同一批 fragment-free 视图,
进度由 Task 7 的 onPause/restore 自然接续。)

import 追加:`com.google.android.material.dialog.MaterialAlertDialogBuilder`。

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/perol/asdpl/pixivez/ui/novel/ app/src/main/res/
git commit -m "feat(novel): reader background themes and line spacing

~.O"
```

---

### Task 9: 文档同步 + CI 验证 + 合入 + 冒烟

**Files:**
- Modify: `app/src/main/java/com/perol/asdpl/pixivez/ui/novel/CLAUDE.md`

- [ ] **Step 1: 更新 CLAUDE.md**——文件职责表加 `NovelMarkup.kt`
  (分块/标记解析纯函数层)与 `NovelProgressStore.kt`(进度 LRU);
  "正文获取"节补 images/illusts 图源映射与图文混排管线描述。

- [ ] **Step 2: Commit + push 分支**

```bash
git add app/src/main/java/com/perol/asdpl/pixivez/ui/novel/CLAUDE.md
git commit -m "docs(novel): markup pipeline and progress store

~.O"
gh auth switch --user ultranity
git -c credential.helper='!gh auth git-credential' push origin ci/novel-reader-phase1
gh auth switch --user Lucas-plaud
```

- [ ] **Step 3: 等 CI 绿**(编译 + 全部单测)。红 → 修 → 再推,循环到绿。

- [ ] **Step 4: 合入 master 并 push**

```bash
git checkout master
git merge --no-ff ci/novel-reader-phase1 -m "Merge ci/novel-reader-phase1: reader markup, images, progress, themes

~.O"
gh auth switch --user ultranity
git -c credential.helper='!gh auth git-credential' push origin master
gh auth switch --user Lucas-plaud
```

- [ ] **Step 5: 真机冒烟清单**(交用户,产物 = master CI run 的 APK)

```text
□ 模式切换:插画↔小说 往返各一次,三 tab 内容正确(上轮修复回归)
□ 含插图小说(如带 [pixivimage:]):图片显示、点击跳插画详情
□ 含章节/ruby/链接标记的小说:样式正确、链接可点
□ 长文(>3 万字):滚动流畅、[jump:] 可跳
□ 头部:简介展示、点标签进小说搜索、点作者进主页、系列名显示
□ 进度:读到中途退出重进,位置恢复
□ 主题:纸白/羊皮/纯黑/跟随 切换生效;行距三档生效
□ 回归:插画阅读页评论、搜索(键盘/趋势标签/联想词三路)
```

---

## Self-Review 记录

- Spec 覆盖:Phase 0 遗留(单测入 CI=Task 1)、Phase 1 全项
  (标记渲染=Task 3/4/5,内联图=Task 2/3/5,header=Task 6,进度=Task 7,
  主题=Task 8)。Phase 1 的"[jump:页码] 滚到对应 chunk"由 Task 5 Step 4 实现。✓
- 占位符扫描:无 TBD;User.id 类型标注了实现时的确认点(Task 6)。✓
- 类型一致:`chunkNovel/tokenize/NovelChunk/NovelToken/NovelProgress`
  签名在 Task 3/4/7 定义、Task 5/7 消费,已互查。✓
- 已知取舍:ruby 用括注不用真排版(TextView 无原生 ruby);
  图片统一 medium/480mw 档不做原图查看;`[jump:]` 目标块因长页再切分
  可能落在页首而非精确锚点——均为 MVP 合理妥协,spec 一致。
