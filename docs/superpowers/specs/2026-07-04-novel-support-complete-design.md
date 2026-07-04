# 小说支持补全设计(2026-07-04)

## 背景与现状

master `ec19ca0` 已含小说模式骨架并通过 CI:侧栏 `main_mode` 切换、
`NovelMainViewPager` 三 tab 首页(推荐/排行/动态)、排行 mode 子 tab、
`NovelSearchResultActivity` 轻量搜索、用户页小说/收藏 tab、`NovelActivity`
阅读页(分块懒渲染、字号记忆、收藏、系列上下篇、分享)。
API 层近乎完整:评论(get/add)、系列、趋势标签、watchlist 端点均已在
`PixivApiService` 就位,但**无 UI 消费**。真机 E2E 从未做过,
`parseWebNovel` 正则解析是唯一无编译期覆盖的运行时风险。

对照 pixez-flutter(`lib/page/novel/`),当前缺:评论、系列页、搜索过滤/
趋势标签、阅读历史、正文标记渲染(内联图/ruby/章节/链接)、阅读进度记忆、
导出、watchlist 入口。确认非缺口:novel related(App-API 无此端点,
pixez 也没有)。

## 假设与已确认事实

1. **wiring 确认损坏(用户真机复现,根因已定位)**:切换 novel 模式后
   三 tab 与搜索仍展示插画,两个独立 bug,见 Phase 0。修复置顶。
2. **范围假设(待用户确认)**:阅读体验/详情生态/搜索发现/本地能力
   四面全做,按下述 Phase 顺序交付,用户可砍任意 Phase。

## 目标

小说模式体验对齐 pixez-flutter 的 novel 功能面:读得爽(排版/进度/主题)、
逛得开(评论/系列/搜索过滤)、留得下(历史/导出/追更)。

## 非目标

- WebView 整页渲染阅读器(见方案 B,不采用)
- novel related 推荐(无端点)
- 小说下载离线库(导出 txt 即够,不建本地正文库)
- AI 显示/分级模式设置开关页(端点已有,与小说模式无关,另行规划)

## 方案对比

| 方案 | 思路 | 取舍 |
|------|------|------|
| **A. 原生增量补全(采用)** | 在现有分块 RecyclerView 架构上补齐:标记渲染走 Spannable,内联图作为独立 chunk 类型,评论泛化现有 CommentDialog | 增量、每步可合入可验证;ruby 排版受 TextView 能力限制(用括号注音妥协) |
| B. WebView 阅读器 | 正文直接 WebView 渲染 pixiv HTML,CSS/JS 注入控字号主题 | 排版保真但作废刚做的分块优化(`9018b87`),字号/主题控制脆,内存高,与 SNI bypass 拦截器纠缠 |
| C. 最小补全 | 只做评论+系列+搜索过滤 | 达不到"完整"预期,阅读体验与 pixez 差距仍大 |

## 分阶段设计(方案 A)

每个 Phase 独立分支 → CI 绿 → 合入,互不阻塞后续讨论。

### Phase 0 — 修复模式切换 wiring + 真机冒烟 + parseWebNovel 单测

真机已复现两个 wiring bug,根因均已定位(2026-07-04):

- **Bug 1:切换后三 tab 仍是插画**。`nav_novel_mode` 用 `recreate()`
  重装,但 recreate 会经 savedInstanceState 恢复 FragmentManager 里的旧
  fragment;`FragmentPagerAdapter.instantiateItem` 按 tag
  `android:switcher:<containerId>:<itemId>` 复用已有 fragment,两个 pager
  共用容器 `R.id.contentView` 且都是默认 `getItemId()`(=position),
  tag 完全相同 → 恢复的旧模式 fragment 被复用,新 pager 的 `getItem()`
  永不执行。**修复:切换不走 `recreate()`,改
  `finish() + startActivity(MainActivity)`**——新实例无
  savedInstanceState,装配天然干净;无需 itemId 特判、无需清理
  stale fragment(消除特殊情况而非对抗它)。主题/系统触发的常规
  recreate 同模式恢复 tag 匹配,不受影响。
- **Bug 2:搜索仍是插画(部分入口)**。novelMode 路由只覆盖
  `SearchActivity.searchFor`(键盘提交)与 SearchSuggestionFragment
  (联想词);`TrendTagFragment.upToPage`(趋势标签/历史点击)与
  MainActivity 剪贴板搜索对话框直启插画 `SearchResultActivity`。
  **修复:收敛为单一路由入口**(companion `SearchRouter` 或
  `SearchResultActivity.start` 内按 `main_mode` 分派),四处调用点
  全走它,消除重复分支,杜绝未来新入口再漏。
- 补 `parseWebNovel` / `renderNovelChunks` / `renderNovelText` 单测
  (真实 `/webview/v2/novel` HTML fixture,`app/src/test`)。
- 真机冒烟:模式往返切换 → 三 tab 内容正确 → 排行子 tab → 趋势标签/
  历史/键盘提交三路搜索 → 阅读页正文渲染 → 收藏 → 系列上下篇 →
  用户页小说 tab。
- 验收:冒烟清单全绿 + 单测过 CI。wiring 修复可先行单独合入。

### Phase 1 — 阅读页体验闭环

改动集中在 `NovelActivity` / `NovelViewModel` / `view_novel_header` /
`view_novel_chunk`。

- **header 增强**:简介(caption,`HtmlCompat` 渲染)、标签点击 →
  `NovelSearchResultActivity`、作者行点击 → `UserMActivity`、
  系列名展示(点击入口留给 Phase 2 系列页)。
- **正文标记渲染**:`renderNovelText` 升级为产出结构化块
  `List<NovelChunk>`(sealed:Text/Image),Text 块内用 Spannable 处理:
  - `[chapter:标题]` → 加粗放大居中
  - `[[rb:汉字>注音]]` → `汉字(注音)` 括号注音
  - `[[jumpuri:标题>URL]]` → ClickableSpan 开 WebViewActivity
  - `[jump:页码]` → 滚到对应 chunk(newpage 序号→chunk index 映射)
- **内联图片**:`[pixivimage:id(-p)]` / `[uploadedimage:id]` → Image 块,
  URL 取 webview JSON 的 `images` map(uploaded)或拉 illust 详情(pixivimage,
  失败降级为占位文本);Glide 加载,点击 pixivimage 跳插画详情。
- **阅读进度**:`onPause` 存 `novelId → (chunkIndex, offset)`
  (SharedPreferences JSON,LRU 上限 200 条);重进恢复滚动位置。
- **阅读主题**:阅读页菜单加 背景(跟随主题/纸白/羊皮/黑)+ 行距(3 档)
  pref,只影响阅读页。
- 验收:标记 fixture 单测;真机验证长文滚动、图文混排、进度恢复。

### Phase 2 — 详情生态:评论 + 系列页

- **评论泛化**:`CommentDialog`/`CommentAdapter` 从 illust 专用改为
  `(id, type)` 参数化,fetch/add 按 type 分派
  `getIllustComments|getNovelComments` 与对应 add 端点,UI 完全复用。
  阅读页菜单 + header 加评论入口。回归:插画评论不受影响(手测)。
- **系列页**:`NovelSeriesActivity`(`RinkActivity`)= 系列标题 header +
  `NovelListFragment(Series)`;`NOVEL_TAG` 加 `Series`,数据源
  `/v2/novel/series`(复用 `NovelResponse` 分页)。入口:阅读页 header
  系列名点击、列表卡片系列名点击。
- 验收:评论查看/发表/回复两类型各过一遍;系列页分页到底。

### Phase 3 — 搜索与发现

- **趋势标签**:novel 模式下搜索起始页趋势标签取
  `/v1/trending-tags/novel`(复用 `TrendTagFragment`/`TrendingTagAdapter`,
  按 `main_mode` 切数据源)。
- **搜索过滤器**:`NovelSearchResultActivity` 加过滤面板:排序
  (日期升降/热度)、目标(标签/关键词/正文)、日期区间——对齐
  `/v1/search/novel` 已支持的 query;UI 照搬插画搜索过滤样式。
- **卡片增强**:`NovelListAdapter` 加 AI 生成角标、R-18 角标、系列名行。
- 验收:各过滤组合请求参数正确(日志核对);角标与 pixiv 官方一致。

### Phase 4 — 本地能力

- **阅读历史**:`HistoryEntity` 主键 `(id, isUser)` 改造为加
  `type` 列(0=illust,1=user,2=novel),Room migration + 主键
  `(id, type)`;`isUser` 语义并入 type(迁移时 isUser=true → type=1)。
  阅读页打开时写历史;历史页加类型过滤 chip。**这是唯一的 schema
  migration,单独 commit,迁移测试必须有。**
- **导出 txt**:阅读页分享菜单加"导出",`renderNovelText` 纯文本经
  SAF `CREATE_DOCUMENT` 写出(标题+作者+正文)。
- **watchlist 追更**:系列页菜单加 追更/取消(端点已有);侧栏或动态页
  入口加"追更列表"(`NOVEL_TAG.Watchlist` → `/v1/watchlist/novel`)。
- 验收:migration 升级路径测试(旧库→新库数据不丢);导出文件可读;
  watchlist 增删列表一致。

## 错误处理

- 正文:webview 解析失败 fallback `/v1/novel/text`(已有);两者皆败
  显示重试按钮而非白屏。
- 内联图:加载失败显示占位 + 原始标记文本,不阻塞正文。
- 评论发表失败:toast + 保留输入。
- migration 失败:Room `fallbackToDestructiveMigration` 不启用——历史表
  宁可崩早(开发期)也不静默清库。

## 测试策略

- 单测:parseWebNovel/renderNovelChunks/标记渲染(真实 HTML fixture)、
  Room migration。
- CI:每 Phase 编译 + 单测绿才合入(workflow_dispatch 触发,产物装真机)。
- 真机冒烟:每 Phase 附带一条冒烟清单,合入前过一遍。

## 交付顺序与依赖

Phase 0 → 1 → 2 → 3 → 4 顺序交付;2/3/4 之间无硬依赖,可按用户反馈重排。
locale:新增字符串四语言(zh/en/ja/zh-rTW)一次补齐,不再欠翻译债。
