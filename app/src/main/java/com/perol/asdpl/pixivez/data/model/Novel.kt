package com.perol.asdpl.pixivez.data.model

import com.perol.asdpl.pixivez.base.EmptyAsNullJsonTransformingSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.nullable

@Serializable
data class Novel(
    val id: Int,
    val title: String,
    val caption: String,
    val restrict: Int,
    @SerialName("x_restrict")
    val x_restrict: Int,
    @SerialName("image_urls")
    val image_urls: ImageUrls,
    @SerialName("is_original")
    val is_original: Boolean,
    @SerialName("create_date")
    val create_date: String,
    val tags: List<Tag>,
    @SerialName("page_count")
    val page_count: Int,
    @SerialName("text_length")
    val text_length: Int,
    val user: User,
    @Serializable(with = EmptyAsNullSeries::class)
    val series: Series?,
    @SerialName("total_view")
    val totalView: Int,
    @SerialName("total_bookmarks")
    val total_bookmarks: Int,
    @SerialName("is_bookmarked")
    val is_bookmarked: Boolean,
    @SerialName("visible")
    val visible: Boolean,
    @SerialName("is_muted")
    val is_muted: Boolean,
    @SerialName("total_comments")
    val total_comments: Int,
    @SerialName("is_mypixiv_only")
    val is_mypixiv_only: Boolean = false,
    @SerialName("is_x_restricted")
    val is_x_restricted: Boolean = false,
    @SerialName("novel_ai_type")
    val novel_ai_type: Int
)
object EmptyAsNullSeries :
    EmptyAsNullJsonTransformingSerializer<Series?>(Series.serializer().nullable)

// ─── 小说列表 / 详情 / 正文 响应 ─────────────────────────────────

// GET /v1/user/novels 等列表响应,next_url 分页复用 INext 惯例
@Serializable
class NovelResponse(
    val novels: MutableList<Novel>,
    override val next_url: String?
) : INext<Novel> {
    override fun data() = novels
}

// GET /v2/novel/detail
@Serializable
class NovelDetailResponse(
    val novel: Novel
)

// GET /webview/v2/novel 内嵌 JSON 的正文模型:字段为 camelCase、id 为字符串,
// 只取正文与系列导航,其余字段靠 Json.ignoreUnknownKeys 容错
@Serializable
class NovelWebResponse(
    val id: String = "",
    val title: String = "",
    val text: String = "",
    val seriesNavigation: SeriesNavigation? = null
)

@Serializable
class SeriesNavigation(
    val prevNovel: NovelNaviItem? = null,
    val nextNovel: NovelNaviItem? = null
)

@Serializable
class NovelNaviItem(
    val id: Int,
    val viewable: Boolean = true,
    val title: String = ""
)