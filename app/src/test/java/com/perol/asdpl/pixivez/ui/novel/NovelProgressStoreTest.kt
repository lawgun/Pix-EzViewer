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
