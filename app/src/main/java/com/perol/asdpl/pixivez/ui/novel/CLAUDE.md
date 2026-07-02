# ui/novel/ —— 小说查看

用户主页「小说」tab 的列表,与小说阅读页。数据模型复用 `data/model/Novel.kt`
(`Novel` / `NovelResponse` / `NovelDetailResponse` / `NovelWebResponse`),
网络经 `PixivApiService` 的 novel 端点 + `RetrofitRepository.getNextNovels`。

## 文件职责

| 文件 | 职责 |
|------|------|
| `NovelListFragment.kt` | 用户小说列表 tab。`BaseVBFragment<FragmentListBinding>`,复用 `fragment_list` 布局(RecyclerView + 下拉刷新),线性排布 + next_url 上拉分页。由 `UserMPagerAdapter` 第 3 个 tab 装载。 |
| `NovelListViewModel.kt` | 列表数据源。`getUserNovels(userid)` 首屏、`getNextNovels(next_url)` 加载更多,数据流照 `core/UserListViewModel`。 |
| `NovelListAdapter.kt` | 列表项适配器 `LBaseQuickAdapter<Novel>`(封面 + 标题 + 作者 + 字数/收藏数),点击进 `NovelActivity`。 |
| `NovelActivity.kt` | 阅读页(`RinkActivity`)。头部标题/作者/标签 + 正文;字号 SharedPreferences 记忆(`novel_text_size`,12~32sp)、收藏 toggle、系列上/下一篇。 |
| `NovelViewModel.kt` | 阅读页数据。并行拉详情(元数据 + 收藏态)与正文;`parseWebNovel` 从 webview HTML 抽 JSON,`renderNovelText` 将 pixiv 自有标记转纯文本。 |

## 正文获取(关键约束)

`/v1/novel/text` 已被 pixiv 废弃。正文走 `GET /webview/v2/novel?id=`,返回 HTML,
内嵌 `pixiv.novel = { novel: {...}, isOwnWork: ... }`。`parseWebNovel` 以
懒惰匹配 `novel:\s*(\{.*?}),\s*isOwnWork`(DOTALL)+ `isOwnWork` 锚点回溯定位
正确闭合括号,取组 1 用 `ServiceFactory.gson` 解析为 `NovelWebResponse`。
正文内联图片(`[pixivimage:]`/`[uploadedimage:]`)MVP 不渲染,标记清洗为纯文本。
系列导航取自同一 JSON 的 `seriesNavigation.{prevNovel,nextNovel}`。
