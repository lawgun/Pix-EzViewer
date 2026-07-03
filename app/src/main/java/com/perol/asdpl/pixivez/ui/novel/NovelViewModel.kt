package com.perol.asdpl.pixivez.ui.novel

import androidx.lifecycle.MutableLiveData
import com.perol.asdpl.pixivez.base.BaseViewModel
import com.perol.asdpl.pixivez.data.model.Novel
import com.perol.asdpl.pixivez.data.model.NovelWebResponse
import com.perol.asdpl.pixivez.networks.ServiceFactory.gson
import com.perol.asdpl.pixivez.objects.CrashHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// 阅读页数据:详情(标题/作者/标签/收藏态)+ 正文(webview HTML 抽 JSON)
class NovelViewModel : BaseViewModel() {
    val novel = MutableLiveData<Novel?>()
    val web = MutableLiveData<NovelWebResponse?>()
    // 正文分块结果,切块含多轮正则清洗,放 Default 线程算完再回主线程
    val chunks = MutableLiveData<List<String>>()
    // is_bookmarked 在 Novel 中不可变,收藏态单独维护以便乐观更新
    val bookmarked = MutableLiveData(false)

    fun load(id: Int) {
        launchUI {
            try {
                val n = retrofit.api.getNovelDetail(id).novel
                novel.value = n
                bookmarked.value = n.is_bookmarked
            } catch (e: Exception) {
                CrashHandler.instance.e("novel", "detail $id failed", e)
                novel.value = null
            }
        }
        launchUI {
            val parsed = try {
                val html = withContext(Dispatchers.IO) {
                    retrofit.api.getNovelText(id).string()
                }
                parseWebNovel(html)
            } catch (e: Exception) {
                CrashHandler.instance.e("novel", "text $id failed", e)
                null
            }
            // webview 抽取失败时 fallback 到 /v1/novel/text 纯文本
            val result = parsed ?: try {
                val text = withContext(Dispatchers.IO) {
                    retrofit.api.getNovelTextApi(id).novel_text
                }
                if (text.isNotBlank()) NovelWebResponse(id = id.toString(), text = text) else null
            } catch (e: Exception) {
                CrashHandler.instance.e("novel", "text fallback $id failed", e)
                null
            }
            web.value = result
            chunks.value = result?.let {
                withContext(Dispatchers.Default) { renderNovelChunks(it.text) }
            } ?: emptyList()
        }
    }

    fun toggleBookmark(id: Int) {
        val cur = bookmarked.value == true
        launchUI {
            try {
                if (cur) retrofit.api.postUnlikeNovel(id)
                else retrofit.api.postLikeNovel(id, "public", null)
                bookmarked.value = !cur
            } catch (e: Exception) {
                CrashHandler.instance.e("novel", "bookmark $id failed", e)
            }
        }
    }
}

// webview/v2/novel 返回 HTML,内嵌 `novel: {...}, isOwnWork`。
// 懒惰匹配 `{.*?}` + isOwnWork 锚点回溯定位到正确的闭合括号;DOTALL 让 `.` 跨行。
private val NOVEL_JSON = Regex("""novel:\s*(\{.*?}),\s*isOwnWork""", RegexOption.DOT_MATCHES_ALL)

fun parseWebNovel(html: String): NovelWebResponse? {
    val json = NOVEL_JSON.find(html)?.groupValues?.getOrNull(1) ?: return null
    return gson.decodeFromString<NovelWebResponse>(json)
}

// pixiv 正文自有标记 → 纯文本(MVP 不渲染内嵌图片,仅保留可读文本)
fun renderNovelText(raw: String): String =
    raw
        .replace(Regex("""\[newpage]"""), "\n\n")
        .replace(Regex("""\[chapter:(.*?)]"""), "\n$1\n")
        .replace(Regex("""\[\[rb:(.*?)>(.*?)]]"""), "$1($2)")
        .replace(Regex("""\[\[jumpuri:(.*?)>.*?]]"""), "$1")
        .replace(Regex("""\[pixivimage:[^\]]*]"""), "")
        .replace(Regex("""\[uploadedimage:[^\]]*]"""), "")
        .replace(Regex("""\[jump:[^\]]*]"""), "")

// 单块字符上限:单个 TextView 的 StaticLayout 测量在主线程,块太大长文必卡
private const val CHUNK_LIMIT = 3000

// 正文 → 分块列表:[newpage] 为天然页界,超限页再按段落切,供 RecyclerView 懒渲染
fun renderNovelChunks(raw: String): List<String> =
    raw.split("[newpage]")
        .map { renderNovelText(it).trim() }
        .flatMap { if (it.length <= CHUNK_LIMIT) listOf(it) else splitByParagraph(it) }
        .filter { it.isNotBlank() }

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
