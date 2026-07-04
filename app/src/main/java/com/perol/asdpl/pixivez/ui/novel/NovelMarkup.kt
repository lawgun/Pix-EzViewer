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
    parts.filter { it.isNotBlank() }.forEach { out += NovelChunk.Text(page, it) }
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
