package com.perol.asdpl.pixivez.ui.novel

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.tabs.TabLayoutMediator
import com.perol.asdpl.pixivez.R
import com.perol.asdpl.pixivez.data.AppDataRepo
import com.perol.asdpl.pixivez.databinding.FragmentNovelRankBinding
import com.perol.asdpl.pixivez.services.PxEZApp

// 小说排行:每个 rank mode 一个子 tab(照 RankingMAdapter 的 tab-per-mode),
// 日期取最新(mode 端点 date 可空)。r18 mode 依用户分级门控。
class NovelRankFragment : Fragment() {
    private var _binding: FragmentNovelRankBinding? = null
    private val binding get() = _binding!!

    // 分级:未开 r18 或账号无 r18 权限 -> 只放 SFW;逐级放开 r18 / r18g
    private val restrictLevel by lazy {
        if (!PxEZApp.instance.pre.getBoolean("r18on", false) ||
            AppDataRepo.currentUser.x_restrict == 0
        ) 5
        else if (AppDataRepo.currentUser.x_restrict == 1) 1 else 0
    }
    private val modes by lazy { buildModes(restrictLevel) }
    private val labels by lazy { resources.getStringArray(R.array.novel_rank_mode) }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentNovelRankBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.viewpager.adapter = NovelRankAdapter(this, modes)
        TabLayoutMediator(binding.tablayout, binding.viewpager) { tab, position ->
            tab.text = labels.getOrElse(position) { modes[position] }
        }.attach()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        // 全序模式表(SFW 在前,r18 / r18g 在后),与 novel_rank_mode 标签一一对应
        private fun buildModes(restrictLevel: Int): List<String> = buildList {
            addAll(listOf("day", "day_male", "day_female", "week", "week_ai"))
            if (restrictLevel <= 1) addAll(listOf("day_r18", "week_r18", "week_ai_r18"))
            if (restrictLevel == 0) add("week_r18g")
        }

        fun newInstance() = NovelRankFragment()
    }
}

private class NovelRankAdapter(fragment: Fragment, private val modes: List<String>) :
    FragmentStateAdapter(fragment) {
    override fun getItemCount() = modes.size
    override fun createFragment(position: Int): Fragment =
        NovelListFragment.newInstance(
            NOVEL_TAG.Rank,
            mutableMapOf("mode" to modes[position])
        )
}
