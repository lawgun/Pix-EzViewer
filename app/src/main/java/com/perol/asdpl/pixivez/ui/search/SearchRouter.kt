package com.perol.asdpl.pixivez.ui.search

import android.app.Activity
import android.content.Intent
import com.perol.asdpl.pixivez.services.PxEZApp
import com.perol.asdpl.pixivez.ui.novel.NovelSearchResultActivity

// 搜索词统一分派入口:所有"按词搜索"一律走这里,按 main_mode 落地
// 小说/插画结果页。各调用点不得自建 Intent,否则小说模式路由会漏
object SearchRouter {
    // 插画结果页经 setResult 回传编辑后的词,SearchActivity 以此回填搜索框
    const val REQUEST_WORD = 775

    fun startWordSearch(caller: Activity, word: String, initialTab: Int = 0) {
        if (PxEZApp.instance.pre.getString("main_mode", "illust") == "novel") {
            NovelSearchResultActivity.start(caller, word)
        } else {
            caller.startActivityForResult(
                Intent(caller, SearchResultActivity::class.java)
                    .setAction("search.result")
                    .putExtra("keyword", word)
                    .putExtra("type", initialTab),
                REQUEST_WORD
            )
        }
    }
}
