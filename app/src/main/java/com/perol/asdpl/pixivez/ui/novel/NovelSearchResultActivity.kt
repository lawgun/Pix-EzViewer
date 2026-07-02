package com.perol.asdpl.pixivez.ui.novel

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import androidx.fragment.app.commit
import com.perol.asdpl.pixivez.R
import com.perol.asdpl.pixivez.base.RinkActivity
import com.perol.asdpl.pixivez.databinding.ActivityNovelSearchBinding

// 小说模式的轻量搜索结果页:直接承载泛化 NovelListFragment(Search),
// 不复用插画 SearchResult 的 tab 体系,保持清晰。
class NovelSearchResultActivity : RinkActivity() {
    private lateinit var binding: ActivityNovelSearchBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNovelSearchBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        val word = intent.getStringExtra(EXTRA_KEYWORD).orEmpty()
        supportActionBar?.title = word
        if (savedInstanceState == null) {
            supportFragmentManager.commit {
                replace(
                    R.id.container,
                    NovelListFragment.newInstance(
                        NOVEL_TAG.Search,
                        mutableMapOf("keyword" to word)
                    )
                )
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    companion object {
        private const val EXTRA_KEYWORD = "keyword"

        fun start(context: Context, keyword: String) {
            context.startActivity(
                Intent(context, NovelSearchResultActivity::class.java)
                    .putExtra(EXTRA_KEYWORD, keyword)
            )
        }
    }
}
