package com.perol.asdpl.pixivez.ui.novel

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import androidx.activity.viewModels
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import com.perol.asdpl.pixivez.R
import com.perol.asdpl.pixivez.base.RinkActivity
import com.perol.asdpl.pixivez.data.model.Novel
import com.perol.asdpl.pixivez.data.model.NovelNaviItem
import com.perol.asdpl.pixivez.databinding.ActivityNovelBinding
import com.perol.asdpl.pixivez.databinding.ViewNovelChunkBinding
import com.perol.asdpl.pixivez.databinding.ViewNovelHeaderBinding
import com.perol.asdpl.pixivez.services.PxEZApp

// 小说阅读页:头部(标题/作者/标签)+ 分块正文,字号记忆、收藏 toggle、系列上/下一篇
class NovelActivity : RinkActivity() {
    private lateinit var binding: ActivityNovelBinding
    private val viewModel: NovelViewModel by viewModels()
    private val adapter = NovelReaderAdapter()
    private var novelId: Int = 0
    private var prev: NovelNaviItem? = null
    private var next: NovelNaviItem? = null
    private var textSize: Float = DEFAULT_TEXT_SIZE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNovelBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.novel)

        novelId = intent.getIntExtra(EXTRA_NOVEL_ID, 0)
        textSize = PxEZApp.instance.pre.getFloat(PREF_TEXT_SIZE, DEFAULT_TEXT_SIZE)
        adapter.textSize = textSize
        binding.novelRecycler.adapter = adapter

        initObserver()
        viewModel.load(novelId)
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun initObserver() {
        viewModel.novel.observe(this) {
            adapter.novel = it
            adapter.notifyItemChanged(0)
        }
        viewModel.bookmarked.observe(this) { invalidateOptionsMenu() }
        viewModel.chunks.observe(this) {
            adapter.chunks = it.orEmpty()
            adapter.notifyDataSetChanged()
        }
        viewModel.web.observe(this) { web ->
            prev = web?.seriesNavigation?.prevNovel
            next = web?.seriesNavigation?.nextNovel
            invalidateOptionsMenu()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_novel, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        // 收藏态用图标透明度区分(仅有一枚心形图标)
        menu.findItem(R.id.action_novel_bookmark).icon?.alpha =
            if (viewModel.bookmarked.value == true) 255 else 90
        menu.findItem(R.id.action_novel_prev).isVisible = prev != null
        menu.findItem(R.id.action_novel_next).isVisible = next != null
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> finish()
            R.id.action_novel_bookmark -> viewModel.toggleBookmark(novelId)
            R.id.action_novel_font_larger -> changeFont(2f)
            R.id.action_novel_font_smaller -> changeFont(-2f)
            R.id.action_novel_prev -> prev?.let(::openNovel)
            R.id.action_novel_next -> next?.let(::openNovel)
            R.id.action_novel_share -> share()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun changeFont(delta: Float) {
        textSize = (textSize + delta).coerceIn(MIN_TEXT_SIZE, MAX_TEXT_SIZE)
        adapter.textSize = textSize
        adapter.notifyItemRangeChanged(1, adapter.chunks.size)
        PxEZApp.instance.pre.edit().putFloat(PREF_TEXT_SIZE, textSize).apply()
    }

    private fun openNovel(item: NovelNaviItem) {
        if (!item.viewable) return
        start(this, item.id)
        finish()
    }

    private fun share() {
        val url = "https://www.pixiv.net/novel/show.php?id=$novelId"
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, url)
                },
                getString(R.string.share)
            )
        )
    }

    companion object {
        private const val EXTRA_NOVEL_ID = "novel_id"
        private const val PREF_TEXT_SIZE = "novel_text_size"
        private const val DEFAULT_TEXT_SIZE = 16f
        private const val MIN_TEXT_SIZE = 12f
        private const val MAX_TEXT_SIZE = 32f

        fun start(context: Context, novelId: Int) {
            context.startActivity(
                Intent(context, NovelActivity::class.java).putExtra(EXTRA_NOVEL_ID, novelId)
            )
        }
    }
}

// 正文分块适配器:position 0 = 头部,之后每项一块正文。
// 块级懒渲染让 TextView 测量摊到滚动过程,长文不再整篇阻塞主线程
private class NovelReaderAdapter : RecyclerView.Adapter<NovelReaderAdapter.VH>() {
    class VH(val binding: ViewBinding) : RecyclerView.ViewHolder(binding.root)

    var novel: Novel? = null
    var chunks: List<String> = emptyList()
    var textSize: Float = 16f

    override fun getItemViewType(position: Int) =
        if (position == 0) TYPE_HEADER else TYPE_CHUNK

    override fun getItemCount() = 1 + chunks.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val inflater = LayoutInflater.from(parent.context)
        return VH(
            if (viewType == TYPE_HEADER) {
                ViewNovelHeaderBinding.inflate(inflater, parent, false)
            } else {
                ViewNovelChunkBinding.inflate(inflater, parent, false)
            }
        )
    }

    @SuppressLint("SetTextI18n")
    override fun onBindViewHolder(holder: VH, position: Int) {
        when (val b = holder.binding) {
            is ViewNovelHeaderBinding -> {
                val n = novel ?: return
                b.novelTitle.text = n.title
                b.novelAuthor.text = n.user.name
                b.novelMeta.text = "${n.text_length} · ♥ ${n.total_bookmarks}"
                b.novelTags.text = n.tags.joinToString(" ") { "#${it.name}" }
            }
            is ViewNovelChunkBinding -> {
                b.novelChunk.textSize = textSize
                // 复用后的可选中 TextView 长按会失灵,重置一次可选中态恢复
                b.novelChunk.setTextIsSelectable(false)
                b.novelChunk.text = chunks[position - 1]
                b.novelChunk.setTextIsSelectable(true)
            }
        }
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_CHUNK = 1
    }
}
