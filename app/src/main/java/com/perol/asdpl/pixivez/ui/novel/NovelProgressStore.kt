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
    else map.entries.sortedByDescending { it.value.at }.take(limit)
        .associate { it.key to it.value }

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
