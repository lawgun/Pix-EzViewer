package com.perol.asdpl.pixivez.ui.novel

import android.os.Bundle
import android.view.View
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.perol.asdpl.pixivez.R
import com.perol.asdpl.pixivez.base.BaseVBFragment
import com.perol.asdpl.pixivez.base.MaterialDialogs
import com.perol.asdpl.pixivez.databinding.FragmentNovelListBinding
import com.perol.asdpl.pixivez.objects.argument
import com.perol.asdpl.pixivez.objects.argumentNullable

// 泛化小说列表:按 NOVEL_TAG 选数据源,next_url 上拉分页。
// Follow / UserBookmark 顶部提供 公开/非公开 切换。
class NovelListFragment : BaseVBFragment<FragmentNovelListBinding>() {
    private var novelTag: String by argument(NOVEL_TAG.UserNovels.name)
    private var extraArgs: MutableMap<String, Any?>? by argumentNullable()
    private lateinit var novelListAdapter: NovelListAdapter
    private val viewModel: NovelListViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.setup(NOVEL_TAG.valueOf(novelTag), extraArgs)
    }

    override fun loadData() {
        viewModel.onLoadFirst()
    }

    override fun onResume() {
        isLoaded = novelListAdapter.data.isNotEmpty()
        super.onResume()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        novelListAdapter = NovelListAdapter(R.layout.view_novel_item)
        initViewModel()
        binding.recyclerview.apply {
            adapter = novelListAdapter
            layoutManager = LinearLayoutManager(requireContext())
        }
        novelListAdapter.setOnLoadMoreListener { viewModel.onLoadMore() }
        binding.swipeRefreshLayout.setOnRefreshListener { viewModel.onLoadFirst() }
        configRestrict()
    }

    // 仅 Follow / UserBookmark 显示公开/非公开切换,照插画动态页的做法
    private fun configRestrict() {
        val toggleable =
            viewModel.tag == NOVEL_TAG.Follow || viewModel.tag == NOVEL_TAG.UserBookmark
        if (!toggleable) {
            binding.btnRestrict.visibility = View.GONE
            return
        }
        binding.btnRestrict.visibility = View.VISIBLE
        renderRestrict()
        val items = arrayOf(getString(R.string.publics), getString(R.string.privates))
        binding.btnRestrict.setOnClickListener {
            MaterialDialogs(requireContext()).show {
                setSingleChoiceItems(
                    items,
                    if (viewModel.restrict.value == "public") 0 else 1
                ) { _, index ->
                    viewModel.restrict.value = if (index == 0) "public" else "private"
                    renderRestrict()
                    viewModel.onLoadFirst()
                }
            }
        }
    }

    private fun renderRestrict() {
        binding.btnRestrict.setText(
            if (viewModel.restrict.value == "public") R.string.publics else R.string.privates
        )
    }

    private fun initViewModel() {
        viewModel.data.observe(viewLifecycleOwner) {
            if (it != null) novelListAdapter.setList(it) else novelListAdapter.loadMoreFail()
        }
        viewModel.nextUrl.observe(viewLifecycleOwner) {
            if (it != null) novelListAdapter.loadMoreComplete() else novelListAdapter.loadMoreEnd()
        }
        viewModel.dataAdded.observe(viewLifecycleOwner) {
            if (it != null) novelListAdapter.addData(it) else novelListAdapter.loadMoreFail()
        }
        viewModel.isRefreshing.observe(viewLifecycleOwner) {
            binding.swipeRefreshLayout.isRefreshing = it
        }
    }

    companion object {
        fun newInstance(
            tag: NOVEL_TAG,
            extraArgs: MutableMap<String, Any?>? = null
        ) = NovelListFragment().apply {
            this.novelTag = tag.name
            this.extraArgs = extraArgs
        }
    }
}
