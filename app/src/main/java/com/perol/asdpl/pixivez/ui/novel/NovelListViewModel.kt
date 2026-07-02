package com.perol.asdpl.pixivez.ui.novel

import androidx.lifecycle.MutableLiveData
import com.perol.asdpl.pixivez.base.BaseViewModel
import com.perol.asdpl.pixivez.base.DMutableLiveData
import com.perol.asdpl.pixivez.data.model.Novel
import kotlin.properties.Delegates

// 用户主页「小说」tab 数据源:next_url 分页,数据流照 UserListViewModel
class NovelListViewModel : BaseViewModel() {
    val data = MutableLiveData<MutableList<Novel>?>()
    val dataAdded = MutableLiveData<MutableList<Novel>?>()
    val nextUrl = MutableLiveData<String?>()
    val isRefreshing = DMutableLiveData(false)
    var userid by Delegates.notNull<Int>()

    fun onLoadFirst() {
        isRefreshing.value = true
        subscribeNext(
            { retrofit.api.getUserNovels(userid) },
            data,
            nextUrl
        ) { isRefreshing.value = false }
    }

    fun onLoadMore() {
        if (nextUrl.value != null) {
            subscribeNext({ retrofit.getNextNovels(nextUrl.value!!) }, dataAdded, nextUrl)
        }
    }
}
