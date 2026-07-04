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
        assertEquals("https://i/m.jpg", img1.url)
        assertEquals(55, img1.illustId)
        val img2 = c[3] as NovelChunk.Image
        assertEquals("https://i/u.jpg", img2.url)
        assertNull(img2.illustId)
        assertEquals(
            listOf("前文", "中文", "后文"),
            c.filterIsInstance<NovelChunk.Text>().map { it.text }
        )
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
