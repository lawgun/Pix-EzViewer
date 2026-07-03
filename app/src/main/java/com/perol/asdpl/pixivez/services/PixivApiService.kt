/*
 * MIT License
 *
 * Copyright (c) 2020 ultranity
 * Copyright (c) 2019 Perol_Notsfsssf
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE
 */

package com.perol.asdpl.pixivez.services

import com.perol.asdpl.pixivez.data.model.BookMarkDetailResponse
import com.perol.asdpl.pixivez.data.model.BookMarkTagsResponse
import com.perol.asdpl.pixivez.data.model.CommentsResponse
import com.perol.asdpl.pixivez.data.model.IllustDetailResponse
import com.perol.asdpl.pixivez.data.model.IllustNext
import com.perol.asdpl.pixivez.data.model.IllustRecommendResponse
import com.perol.asdpl.pixivez.data.model.ListUserResponse
import com.perol.asdpl.pixivez.data.model.NovelDetailResponse
import com.perol.asdpl.pixivez.data.model.NovelResponse
import com.perol.asdpl.pixivez.data.model.NovelTextResponse
import com.perol.asdpl.pixivez.data.model.NovelTrendingtagResponse
import com.perol.asdpl.pixivez.data.model.PostCommentsResponse
import com.perol.asdpl.pixivez.data.model.RestrictedModeSettingsResponse
import com.perol.asdpl.pixivez.data.model.SearchIllustResponse
import com.perol.asdpl.pixivez.data.model.UserAISettingsResponse
import com.perol.asdpl.pixivez.data.model.SearchUserResponse
import com.perol.asdpl.pixivez.data.model.SpotlightResponse
import com.perol.asdpl.pixivez.data.model.TagsListResponse
import com.perol.asdpl.pixivez.data.model.TrendingtagResponse
import com.perol.asdpl.pixivez.data.model.UgoiraMetadataResponse
import com.perol.asdpl.pixivez.data.model.UserDetail
import com.perol.asdpl.pixivez.data.model.UserFollowDetail
import com.perol.asdpl.pixivez.data.model.UserIllustNext
import okhttp3.MultipartBody
import okhttp3.ResponseBody
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming
import retrofit2.http.Url

interface PixivApiService { //TODO: check filter=for_android

    @GET("/v1/spotlight/articles?filter=for_android")
    suspend fun getPixivisionArticles(
        @Query("category") category: String = "all"
    ): SpotlightResponse

    @GET("v1/walkthrough/illusts")
    suspend fun walkthroughIllusts(
        @Query("offset") offset: Int? = null,
    ): IllustNext

    //https://app-api.pixiv.net/v1/illust/recommended?content_type=illust&filter=for_ios
    // &include_ranking_label=true&include_ranking_illusts=false
    // &min_bookmark_id_for_recent_illust=4078859313
    // &max_bookmark_id_for_recommend=4068770682&offset=0
    @GET("/v1/illust/recommended?filter=for_ios&include_ranking_label=true")
    suspend fun getIllustRecommend(
    ): IllustRecommendResponse

    @GET("/v1/illust/new")
    suspend fun getIllustNew(
        @Query("content_type") contentType: String? = null //illust manga
    ): IllustNext

    @GET("/v1/illust/ranking?filter=for_android")
    suspend fun getIllustRanking(
        @Query("mode") mode: String,
        @Query("date") date: String?
    ): IllustNext

    @GET("/v2/illust/follow")
    suspend fun getFollowIllusts(
        @Query("restrict") restrict: String
    ): IllustNext


    // @FormUrlEncoded
    // @POST("/v1/mute/edit")
    // public abstract l<PixivResponse> postMuteSetting(@Header("Authorization") String paramString, @Field("add_user_ids[]") List<Long> paramList1, @Field("delete_user_ids[]") List<Long> paramList2, @Field("add_tags[]") List<String> paramList3, @Field("delete_tags[]") List<String> paramList4);
    @GET("/v2/illust/bookmark/detail")
    suspend fun getIllustBookmarkDetail(
        @Query("illust_id") pid: Int
    ): BookMarkDetailResponse

    @GET("v1/user/bookmark-tags/illust")
    suspend fun getIllustBookmarkTags(
        @Query("user_id") uid: Int,
        @Query("restrict") restrict: String
    ): BookMarkTagsResponse

    @GET("/v1/ugoira/metadata")
    suspend fun getUgoiraMetadata(
        @Query("illust_id") pid: Int
    ): UgoiraMetadataResponse

    @GET("/v1/user/browsing-history/illusts")
    suspend fun getIllustBrowsingHistory(
    ): IllustNext

    @FormUrlEncoded
    @POST("/v2/user/browsing-history/illust/add")
    suspend fun postAddIllustBrowsingHistory(
        @Field("illust_ids[]") illust_idList: List<Int>
    ): ResponseBody

    @GET("/v1/user/bookmarks/illust")
    suspend fun getLikeIllust(
        @Query("user_id") uid: Int,
        @Query("restrict") restrict: String,
        @Query("tag") tag: String?
    ): IllustNext

    @FormUrlEncoded
    @POST("/v2/illust/bookmark/add")
    suspend fun postLikeIllust(
        @Field("illust_id") illust_id: Int,
        @Field("restrict") restrict: String,
        @Field("tags[]") tagList: List<String>?
    ): ResponseBody

    @FormUrlEncoded
    @POST("/v1/illust/bookmark/delete")
    suspend fun postUnlikeIllust(
        @Field("illust_id") illust_id: Int
    ): ResponseBody

    @GET("/v2/search/autocomplete?merge_plain_keyword_results=true")
    suspend fun getSearchAutoCompleteKeywords(
        @Query("word") word: String?
    ): TagsListResponse

    @GET("/v1/trending-tags/illust?filter=for_android")
    suspend fun getIllustTrendTags(
    ): TrendingtagResponse

    //    &start_date=2019-11-24&end_date=2019-12-01
    @GET("/v1/search/illust?filter=for_android&merge_plain_keyword_results=true")
    suspend fun getSearchIllust(
        @Query("word") word: String,
        @Query("sort") sort: String,
        @Query("search_target") search_target: String?,
        @Query("start_date") start_date: String?,
        @Query("end_date") end_date: String?,
        @Query("bookmark_num_min") bookmark_num_min: Int?,
        @Query("bookmark_num_max") bookmark_num_max: Int? = null,
        @Query("search_ai_type") search_ai_type: Int? = null
    ): SearchIllustResponse

    @GET("/v1/search/popular-preview/illust?include_translated_tag_results=true&merge_plain_keyword_results=true")
    suspend fun getSearchIllustPreview(
        @Query("word") word: String,
        @Query("sort") sort: String,
        @Query("search_target") search_target: String?,
        @Query("bookmark_num") paramInteger: Int?,
        @Query("duration") duration: String?
    ): SearchIllustResponse

    @GET("/v1/search/popular-preview/illust?include_translated_tag_results=true&merge_plain_keyword_results=true")
    suspend fun getPopularPreviewIllust(
        @Query("word") word: String,
        @Query("search_target") search_target: String,
        @Query("duration") duration: String
    ): TagsListResponse

    @GET("/v1/search/user?filter=for_android")
    suspend fun getSearchUser(
        @Query("word") word: String
    ): SearchUserResponse

    @GET("/v1/search/novel?filter=for_android&merge_plain_keyword_results=true")
    suspend fun getSearchNovel(
        @Query("word") word: String,
        @Query("sort") sort: String,
        @Query("search_target") search_target: String?,
        @Query("start_date") start_date: String?,
        @Query("end_date") end_date: String?,
        @Query("bookmark_num") paramInteger: Int?,
        @Query("duration") duration: String?
    ): NovelResponse

    @Multipart
    @POST("/v1/user/profile/edit")
    suspend fun postUserProfileEdit(
        @Part paramRequestBody: MultipartBody.Part
    ): ResponseBody

    @GET("/v1/user/recommended?filter=for_android")
    suspend fun getUserRecommended(
        @Query("offset") offset: Int? = null,
    ): SearchUserResponse

    @GET("/v1/user/follower?filter=for_android")
    suspend fun getUserFollower(
        @Query("user_id") uid: Int,
        @Query("restrict") restrict: String = "public"
    ): SearchUserResponse

    @GET("/v1/user/following?filter=for_android")
    suspend fun getUserFollowing(
        @Query("user_id") uid: Int,
        @Query("restrict") restrict: String
    ): SearchUserResponse

    @GET("/v1/user/detail?filter=for_android")
    suspend fun getUserDetail(
        @Query("user_id") id: Int
    ): UserDetail

    @GET("/v1/user/illusts?filter=for_android")
    suspend fun getUserIllusts(
        @Query("user_id") uid: Int,
        @Query("type") type: String, //illust manga novel
    ): UserIllustNext

    @GET("/v1/user/related") //?filter=for_android
    suspend fun getUserRelated(
        @Query("seed_user_id") seedUserId: Int,
        @Query("filter") filter: String = "for_android",
        @Query("offset") offset: Int? = null,
    ): SearchUserResponse

    //获取好P友
    @GET("v1/user/mypixiv?filter=for_android")
    suspend fun getMyPixivFriend(
        @Query("user_id") user_id: Int
    ): SearchUserResponse

    @GET("v1/user/follow/detail")
    suspend fun getFollowDetail(
        @Query("user_id") user_id: Int
    ): UserFollowDetail

    @FormUrlEncoded
    @POST("/v1/user/follow/add")
    suspend fun postFollowUser(
        @Field("user_id") user_id: Int,
        @Field("restrict") restrict: String
    ): ResponseBody

    @FormUrlEncoded
    @POST("/v1/user/follow/delete")
    suspend fun postUnfollowUser(
        @Field("user_id") user_id: Int
    ): ResponseBody


    @GET("/v1/illust/detail?filter=for_android")
    suspend fun getIllust(
        @Query("illust_id") pid: Int
    ): IllustDetailResponse

    @GET("/v2/illust/related?filter=for_android")
    suspend fun getIllustRelated(
        @Query("illust_id") pid: Int
    ): IllustNext

    @GET("/v1/illust/bookmark/users")
    suspend fun getIllustBookmarkUsers(
        @Query("illust_id") illust_id: Int,
        @Query("offset") offset: Int? = null,
    ): ListUserResponse

    @GET("/v3/illust/comments")
    suspend fun getIllustComments(
        @Query("illust_id") pid: Int,
        @Query("offset") offset: Int? = null,
        @Query("include_total_comments") include_total_comments: Boolean? = false
    ): CommentsResponse

    @GET("/v3/novel/comments")
    suspend fun getNovelComments(
        @Query("novel_id") novel_id: Int,
        @Query("offset") offset: Int? = null,
        @Query("include_total_comments") include_total_comments: Boolean? = false
    ): CommentsResponse

    @GET("/v2/{type}/comment/replies")
    suspend fun getReplyComments(
        @Path("type") type: String,
        @Query("comment_id") comment_id: Long,
    ): CommentsResponse

    @FormUrlEncoded
    @POST("/v1/illust/comment/add")
    suspend fun postIllustComment(
        @Field("illust_id") pid: Int,
        @Field("comment") comment: String,
        @Field("parent_comment_id") parent_comment_id: Int? = null,
    ): PostCommentsResponse

    @FormUrlEncoded
    @POST("/v1/novel/comment/add")
    suspend fun postNovelComment(
        @Field("novel_id") novel_id: Int,
        @Field("comment") comment: String,
        @Field("parent_comment_id") parent_comment_id: Int? = null,
    ): PostCommentsResponse

    @GET("/v1/user/novels?filter=for_android")
    suspend fun getUserNovels(
        @Query("user_id") uid: Int
    ): NovelResponse

    @GET("/v1/novel/recommended?include_privacy_policy=true&filter=for_android&include_ranking_novels=true")
    suspend fun getNovelRecommend(
    ): NovelResponse

    @GET("/v1/novel/ranking?filter=for_android")
    suspend fun getNovelRanking(
        @Query("mode") mode: String,
        @Query("date") date: String? = null
    ): NovelResponse

    @GET("/v1/novel/follow")
    suspend fun getNovelFollow(
        @Query("restrict") restrict: String
    ): NovelResponse

    @GET("/v1/user/bookmarks/novel")
    suspend fun getUserBookmarkNovel(
        @Query("user_id") uid: Int,
        @Query("restrict") restrict: String
    ): NovelResponse

    @GET("/v1/trending-tags/novel?filter=for_android")
    suspend fun getNovelTrendTags(
    ): NovelTrendingtagResponse

    // 系列内小说列表(响应含 novels + next_url,复用 NovelResponse)
    @GET("/v2/novel/series")
    suspend fun getNovelSeries(
        @Query("series_id") series_id: Int
    ): NovelResponse

    // /v1/novel/text 纯文本备选:webview 解析失败时 fallback
    @GET("/v1/novel/text")
    suspend fun getNovelTextApi(
        @Query("novel_id") novel_id: Int
    ): NovelTextResponse

    @GET("/v2/novel/detail")
    suspend fun getNovelDetail(
        @Query("novel_id") novel_id: Int
    ): NovelDetailResponse

    // 正文为 HTML(内嵌 JSON),返回原始 body 交由上层抽取解析
    @GET("/webview/v2/novel")
    suspend fun getNovelText(
        @Query("id") novel_id: Int
    ): ResponseBody

    @FormUrlEncoded
    @POST("/v2/novel/bookmark/add")
    suspend fun postLikeNovel(
        @Field("novel_id") novel_id: Int,
        @Field("restrict") restrict: String,
        @Field("tags[]") tagList: List<String>? = null
    ): ResponseBody

    @FormUrlEncoded
    @POST("/v1/novel/bookmark/delete")
    suspend fun postUnlikeNovel(
        @Field("novel_id") novel_id: Int
    ): ResponseBody

    @FormUrlEncoded
    @POST("/v1/{type}/comment/delete")
    suspend fun deleteComment(
        @Path("type") type: String,
        @Field("comment_id") comment_id: Int,
    )

    // ─── 独立设置 / 系列 / watchlist(暂无 UI 消费方,接口+模型就位)──────

    @GET("/v1/user/ai-show-settings")
    suspend fun getUserAISettings(): UserAISettingsResponse

    @FormUrlEncoded
    @POST("/v1/user/ai-show-settings/edit")
    suspend fun postUserAIShowSettings(
        @Field("show_ai") show_ai: Boolean
    ): ResponseBody

    @GET("/v1/user/restricted-mode-settings")
    suspend fun getRestrictedModeSettings(): RestrictedModeSettingsResponse

    @FormUrlEncoded
    @POST("/v1/user/restricted-mode-settings")
    suspend fun postRestrictedModeSettings(
        @Field("is_restricted_mode_enabled") is_restricted_mode_enabled: Boolean
    ): ResponseBody

    @GET("/v1/manga/recommended?filter=for_ios&include_ranking_label=true")
    suspend fun getMangaRecommend(): IllustRecommendResponse

    @GET("/v1/illust/series")
    suspend fun getIllustSeries(
        @Query("illust_series_id") illust_series_id: Int
    ): IllustNext

    @GET("/v1/illust-series/illust")
    suspend fun getIllustSeriesIllust(
        @Query("illust_id") illust_id: Int
    ): IllustNext

    // watchlist 响应结构随类型而异且无消费方,返回原始 body
    @GET("/v1/watchlist/novel")
    suspend fun getWatchlistNovel(): ResponseBody

    @FormUrlEncoded
    @POST("/v1/watchlist/novel/add")
    suspend fun postWatchlistNovelAdd(
        @Field("series_id") series_id: Int
    ): ResponseBody

    @FormUrlEncoded
    @POST("/v1/watchlist/novel/delete")
    suspend fun postWatchlistNovelDelete(
        @Field("series_id") series_id: Int
    ): ResponseBody

    @GET("/v1/watchlist/manga")
    suspend fun getWatchlistManga(): ResponseBody

    @FormUrlEncoded
    @POST("/v1/watchlist/manga/add")
    suspend fun postWatchlistMangaAdd(
        @Field("series_id") series_id: Int
    ): ResponseBody

    @FormUrlEncoded
    @POST("/v1/watchlist/manga/delete")
    suspend fun postWatchlistMangaDelete(
        @Field("series_id") series_id: Int
    ): ResponseBody

    @GET
    suspend fun getUrl(
        @Url url: String
    ): ResponseBody
    /*@GET
    suspend fun getBody(
        @Url url: String
    ): ResponseBody*/

    /*@GET //retrofit runtime reflection cannot access T
    suspend fun <T> get(
        @Url url: String
    ): T*/
}

interface PixivFileService {
    @Streaming
    @GET
    suspend fun getGIFFile(@Url fileUrl: String): ResponseBody
}