# ui/novel/ —— 小说模式

小说的独立浏览模式:首页(推荐/排行/动态)+ 搜索 + 用户小说列表 + 阅读页。
体验对齐插画模式但展示小说。数据模型复用 `data/model/Novel.kt`
(`Novel` / `NovelResponse` / `NovelDetailResponse` / `NovelWebResponse` /
`NovelTextResponse`),网络经 `PixivApiService` 的 novel 端点 +
`RetrofitRepository.getNextNovels` 分页。

## 模式装配(入口在 ui/MainActivity)

`main_mode` pref("illust"/"novel",`PxEZApp.instance.pre`)决定 MainActivity
装配哪个 pager:`NovelMainViewPager` 或 `HelloMainViewPager`。抽屉项
`nav_novel_mode` 翻转 pref 后 `recreate()` 重装;三个 tab 图标(home/rank/user)
两模式共用,语义上对应 推荐/排行/动态。搜索入口(SearchActivity)按 `main_mode`
路由:novel 模式落地 `NovelSearchResultActivity`。

## 文件职责

| 文件 | 职责 |
|------|------|
| `NovelMainViewPager.kt` | 小说首页三 tab(`FragmentPagerAdapter`,与 `HelloMainViewPager` 对齐):推荐=`NovelListFragment(Recommend)`、排行=`NovelRankFragment`、动态=`NovelListFragment(Follow)`。 |
| `NovelRankFragment.kt` | 排行页:`ViewPager2 + TabLayout` 每个 rank mode 一个子 tab(照 `RankingMAdapter`)。mode 全序表在 `buildModes`,r18/r18g 按用户 `x_restrict` + `r18on` 门控;标签取 `R.array.novel_rank_mode`。日期取最新(端点 `date` 可空)。内含私有 `NovelRankAdapter`。 |
| `NovelSearchResultActivity.kt` | 小说模式轻量搜索结果页(`RinkActivity`)。承载 `NovelListFragment(Search)`,不复用插画 `SearchResult` 的 tab 体系。Manifest 已注册。 |
| `NovelListFragment.kt` | 泛化小说列表 `BaseVBFragment<FragmentNovelListBinding>`。`newInstance(tag, extraArgs)` 对齐 `PicListFragment`;线性排布 + next_url 上拉分页。Follow/UserBookmark 顶部提供 公开/非公开 切换。 |
| `NovelListViewModel.kt` | 列表数据源。`NOVEL_TAG`(Recommend/Rank/Follow/UserNovels/UserBookmark/Search)经 `loadFirstRx` 选端点;`restrict` 供 Follow/UserBookmark 切换;`getNextNovels(next_url)` 加载更多。 |
| `NovelListAdapter.kt` | 列表项适配器 `LBaseQuickAdapter<Novel>`(封面 + 标题 + 作者 + 字数/收藏数),点击进 `NovelActivity`。 |
| `NovelActivity.kt` | 阅读页(`RinkActivity`)。头部标题/作者/标签 + 正文;字号 SharedPreferences 记忆(`novel_text_size`,12~32sp)、收藏 toggle、系列上/下一篇。 |
| `NovelViewModel.kt` | 阅读页数据。并行拉详情(元数据 + 收藏态)与正文;`parseWebNovel` 从 webview HTML 抽 JSON,失败时 fallback `/v1/novel/text` 纯文本;`renderNovelText` 将 pixiv 自有标记转纯文本。 |

## 正文获取(关键约束)

正文优先走 `GET /webview/v2/novel?id=`,返回 HTML,内嵌
`pixiv.novel = { novel: {...}, isOwnWork: ... }`。`parseWebNovel` 以懒惰匹配
`novel:\s*(\{.*?}),\s*isOwnWork`(DOTALL)+ `isOwnWork` 锚点定位闭合括号,
取组 1 用 `ServiceFactory.gson` 解析为 `NovelWebResponse`。抽取失败时 fallback
到 `GET /v1/novel/text`(`NovelTextResponse.novel_text`)。正文内联图片
(`[pixivimage:]`/`[uploadedimage:]`)MVP 不渲染,标记清洗为纯文本。
系列导航取自 webview JSON 的 `seriesNavigation.{prevNovel,nextNovel}`。

## 数据源端点(NOVEL_TAG → PixivApiService)

| NOVEL_TAG | 端点 |
|-----------|------|
| Recommend | `/v1/novel/recommended` |
| Rank | `/v1/novel/ranking`(mode,date) |
| Follow | `/v1/novel/follow`(restrict) |
| UserNovels | `/v1/user/novels` |
| UserBookmark | `/v1/user/bookmarks/novel`(restrict) |
| Search | `/v1/search/novel`(word,sort,search_target,date) |
