package com.perol.asdpl.pixivez.ui.novel

import android.os.Bundle
import android.view.View
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.perol.asdpl.pixivez.R
import com.perol.asdpl.pixivez.base.BaseVBFragment
import com.perol.asdpl.pixivez.databinding.FragmentListBinding
import com.perol.asdpl.pixivez.objects.argumentNullable

// 用户主页「小说」tab:独立线性列表,复用 fragment_list 布局与 next_url 分页
class NovelListFragment : BaseVBFragment<FragmentListBinding>() {
    private var userid: Int? by argumentNullable()
    private lateinit var novelListAdapter: NovelListAdapter
    private val viewModel: NovelListViewModel by viewModels()

    override fun loadData() {
        viewModel.onLoadFirst()
    }

    override fun onResume() {
        isLoaded = novelListAdapter.data.isNotEmpty()
        super.onResume()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.userid = userid!!
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
        fun newInstance(userid: Int) = NovelListFragment().apply {
            this.userid = userid
        }
    }
}
