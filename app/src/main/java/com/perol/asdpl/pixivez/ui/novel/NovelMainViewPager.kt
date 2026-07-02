package com.perol.asdpl.pixivez.ui.novel

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentPagerAdapter

// 小说独立模式首页三件套,与 HelloMainViewPager 三 tab 一一对应:
// 推荐(novel/recommended)/ 排行(mode 可切)/ 动态(novel/follow)
class NovelMainViewPager(fragmentManager: FragmentManager) :
    FragmentPagerAdapter(fragmentManager, BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT) {
    override fun getCount(): Int = 3

    override fun getItem(position: Int): Fragment = when (position) {
        0 -> NovelListFragment.newInstance(NOVEL_TAG.Recommend)
        1 -> NovelRankFragment.newInstance()
        else -> NovelListFragment.newInstance(NOVEL_TAG.Follow)
    }
}
