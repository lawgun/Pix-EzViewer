package com.perol.asdpl.pixivez.ui.novel

import androidx.lifecycle.MutableLiveData
import com.perol.asdpl.pixivez.base.BaseViewModel
import com.perol.asdpl.pixivez.base.DMutableLiveData
import com.perol.asdpl.pixivez.data.model.Novel
import com.perol.asdpl.pixivez.data.model.NovelResponse

// 小说列表数据源类型:决定首屏拉取哪个端点
enum class NOVEL_TAG {
    Recommend, Rank, Follow, UserNovels, UserBookmark, Search
}

// 泛化小说列表数据源:按 NOVEL_TAG 选端点,next_url 分页,数据流照 UserListViewModel。
// Follow / UserBookmark 经 restrict 切换公开/非公开。
class NovelListViewModel : BaseViewModel() {
    val data = MutableLiveData<MutableList<Novel>?>()
    val dataAdded = MutableLiveData<MutableList<Novel>?>()
    val nextUrl = MutableLiveData<String?>()
    val isRefreshing = DMutableLiveData(false)
    val restrict = DMutableLiveData("public")

    lateinit var tag: NOVEL_TAG
    private var args: MutableMap<String, Any?> = mutableMapOf()

    fun setup(tag: NOVEL_TAG, args: MutableMap<String, Any?>?) {
        this.tag = tag
        if (args != null) this.args = args
    }

    private fun loadFirstRx(): suspend () -> NovelResponse = when (tag) {
        NOVEL_TAG.Recommend -> {
            { retrofit.api.getNovelRecommend() }
        }

        NOVEL_TAG.Rank -> {
            { retrofit.api.getNovelRanking(args["mode"] as String, args["date"] as String?) }
        }

        NOVEL_TAG.Follow -> {
            { retrofit.api.getNovelFollow(restrict.value) }
        }

        NOVEL_TAG.UserNovels -> {
            { retrofit.api.getUserNovels(args["userid"] as Int) }
        }

        NOVEL_TAG.UserBookmark -> {
            { retrofit.api.getUserBookmarkNovel(args["userid"] as Int, restrict.value) }
        }

        NOVEL_TAG.Search -> {
            {
                retrofit.api.getSearchNovel(
                    args["keyword"] as String,
                    args["sort"] as? String ?: "date_desc",
                    args["search_target"] as? String ?: "partial_match_for_tags",
                    args["start_date"] as? String,
                    args["end_date"] as? String,
                    null,
                    null
                )
            }
        }
    }

    fun onLoadFirst() {
        isRefreshing.value = true
        subscribeNext(loadFirstRx(), data, nextUrl) { isRefreshing.value = false }
    }

    fun onLoadMore() {
        if (nextUrl.value != null) {
            subscribeNext({ retrofit.getNextNovels(nextUrl.value!!) }, dataAdded, nextUrl)
        }
    }
}
