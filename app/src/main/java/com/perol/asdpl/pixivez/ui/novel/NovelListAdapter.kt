package com.perol.asdpl.pixivez.ui.novel

import android.annotation.SuppressLint
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade
import com.chad.brvah.viewholder.BaseViewHolder
import com.perol.asdpl.pixivez.R
import com.perol.asdpl.pixivez.base.LBaseQuickAdapter
import com.perol.asdpl.pixivez.data.model.Novel

// 小说列表项:封面 + 标题 + 作者 + 字数/收藏数,点击进阅读页
class NovelListAdapter(layoutResId: Int) :
    LBaseQuickAdapter<Novel, BaseViewHolder>(layoutResId) {

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        setOnItemClickListener { _, _, position ->
            NovelActivity.start(context, data[position].id)
        }
    }

    @SuppressLint("SetTextI18n")
    override fun convert(holder: BaseViewHolder, item: Novel) {
        holder.getView<TextView>(R.id.novel_title).text = item.title
        holder.getView<TextView>(R.id.novel_author).text = item.user.name
        holder.getView<TextView>(R.id.novel_meta).text =
            "${item.text_length} · ♥ ${item.total_bookmarks}"
        val cover = holder.getView<ImageView>(R.id.novel_cover)
        Glide.with(cover).load(item.image_urls.medium)
            .transition(withCrossFade()).into(cover)
    }
}
