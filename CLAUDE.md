# StockSpeaker

Kotlin 原生 Android 股票盯盘语音播报 App。核心场景：**持仓防守**——对已买入股票实时监控、捕捉日内做 T 买卖点、提示风险，绝不做选股推荐。含双 AI 分析、TTS 语音播报、GitHub Actions 自动打包。

## Code Style

- Kotlin Android 原生 + Compose UI，不用跨平台框架
- OkHttp 网络请求，connect/read 超时 3 秒，所有请求在子线程执行
- TTS 用 Android 内置 `TextToSpeech`，不引入第三方 SDK
- 中文用户交互

## 文件结构

```
app/src/main/java/com/stockspeaker/
├── StockMonitorService.kt     # 前台 Service，2 秒轮询 + 异动检测 + 三轨道播报调度
├── AIAnalyzer.kt              # 双 AI（主 AI 定时点评 + 辅 AI 资金面深析，独立配置）
├── StockFetcher.kt            # 腾讯行情 API（实时价/大单/K线/上证指数/流通市值）
├── FundFlowFetcher.kt         # 东财资金流向(日级累计)，30 秒轮询
├── ConceptBlockFetcher.kt     # 百度概念板块归属 + Alpha 差值计算
├── DragonTigerFetcher.kt      # 东方财富龙虎榜，静态标签 + SharedPreferences 日期缓存
├── MarketSentimentFetcher.kt  # 全市场情绪（涨跌比/涨停数/炸板率）
├── MainActivity.kt            # Compose UI 设置页 + 实时行情面板
├── ConfigManager.kt           # SharedPreferences 配置持久化
├── TtsEngine.kt               # TTS 播报队列管理
└── NotificationHelper.kt      # 通知栏控制
```

## 重要约定

### 版本号命名

- 功能优化 / 新功能：递增末位 — `1.0.11` → `1.0.12`
- 纯 Bug 修复（无功能变更）：加子版本号 — `1.0.11` → `1.0.11.1`
- 大版本更新（架构/数据源/AI 框架等重大变更）：递增次版本号 — `1.0.x` → `1.1.0`

### 息屏保活（Doze 下不能停）

- 定时循环必须用后台 **HandlerThread**，Doze 模式下主线程 `Handler.postDelayed` 消息队列被挂起，必然停摆
- **定时调度必须在阻塞工作之前**：`loopHandler.postDelayed` 必须在 `netExecutor.execute` 之前调用。Doze 会挂起网络 I/O（OkHttp 超时线程也被暂停），`fetch()` 可能无限期阻塞。如果把 `postDelayed` 放在 `fetch()` 之后，定时链会因网络阻塞而断裂——这是息屏停播的根因
- **所有业务逻辑（processStockData / TTS 播报）必须在主线程外执行**——`uiHandler.post {}` 内的代码息屏后不执行，整个播报停摆
- **TTS 超时 Handler 必须用后台线程**——主线程 Handler 的 `postDelayed` 息屏后不触发，`isSpeaking` 卡死无法重置
- **TTS 音频属性用 `USAGE_MEDIA`**——`USAGE_ASSISTANT` 在息屏/锁屏后可能被系统限制
- **WakeLock** 防御性强申：每轮循环无条件 `acquire(600000L)`（10分钟超时），不检查 `isHeld`——部分国产 ROM `isHeld` 返回值不可靠，且 `setReferenceCounted(false)` 下重复 acquire 是安全的
- **WifiLock** 同样无条件 `acquire()`，不检查 `isHeld`
- **电池优化白名单**：`MainActivity.onCreate` 中检查 `isIgnoringBatteryOptimizations`，不在白名单则弹系统原生申请框——国产 ROM 靠这个决定是否杀后台
- **TTS 防卡死检查必须在 `runLoop()` 内执行**——不能放在 `processStockData()` 内。`processStockData` 依赖网络 fetch 成功才执行，Doze 期间网络阻塞 → `fetchInFlight` 永久为 true → 防卡死检查被跳过 → `isSpeaking` 卡死，整个播报停摆
- **`fetchInFlight` 看门狗**：超过 60 秒未复位则强制重置。Doze 唤醒后 OkHttp socket 可能处于半死状态（不返回数据也不抛超时），导致 `fetchInFlight` 永久卡 true
- 音频焦点丢失（`AUDIOFOCUS_LOSS`）时仅重新请求 `MAY_DUCK` 共存，不中断播报——短时金融语音不应被切歌打断

### 数据防污染

- 东方财富 API 用 `2147483647`（Int.MAX_VALUE）作为数据缺失标记，所有解析到的数值必须过滤此值并归零
- 行情 API 返回 0 可能是「无数据」而非「0 手」，必须区分 `== 0`（跳过）和 `> 0`（有效）
- 高标股/转债污染：取全市场涨幅榜时扩大候选并过滤「转债/ST/退/N」关键字

### 业务逻辑铁律

- **大单方向**：绝对禁止用 `changePct` 判断买卖方向。必须用「当前价 vs 上一秒价格」对比，`price > prevPrice` = 扫货，`price < prevPrice` = 砸盘
- **AI 四选一输出**：【坚定格局】/【逢高减仓·倒T】/【逢低吸筹·正T】/【危险清仓】。绝不做买入推荐
- **四象限资金防伪**：净流入+涨速>0.5%=主力真拉升 / 净流入+涨速<0=主力暗中派发 / 净流出+涨速>0.5%=散户推升 / 净流出+涨速<-0.5%=主力真砸盘
- **Alpha 差值**：个股涨跌幅 - 板块涨跌幅 = Alpha。差值>0 给予更多容忍度，差值<0 判定跟风，遇阻坚决减仓/清仓
- **播报三轨道**：实时异动（最高优先级，可打断一切）→ 定时常规播报 → 双 AI 插播
- **箱体静默**：当前价与上次播报价差 < 0.3% 且量比 < 2.0 时，跳过完整播报
- **大单阶梯报警**：当前手数 ≥ 上次报警手数 × 1.5 时，无视 30 秒冷却立即再次播报
- **异动冷却独立**：大单、涨速、AI 异动三类各自独立 30 秒冷却，不同类型互不阻塞

### 防御性编程与状态机铁律 (Critical)

- **涨速滑动窗口**：禁止用 2 秒轮询差值算涨速！必须维护 `ArrayDeque` (size=8) 实现 15 秒滑动窗口，`windowSpeed = (当前价 - 窗口最早价) / 窗口最早价 * 100`，解决连续中小单脉冲漏报问题。
- **双模自适应**：基于换手率(>5.0)或量比(>1.8)判定股票为【热门】或【潜伏】状态。热门股必须调高异动触发门槛（防噪），冷门股降低门槛（抓启动）。
- **早盘豁免机制**：09:30-10:00 期间换手率未累积，动态大单/涨速阈值必须仅依赖 `volRatio` 缩放，10:00 之后再结合 `turnover`。
- **严禁 Mock 数据**：所有 Fetcher 发生网络/解析异常时，必须返回 `null` 或空对象。**绝对禁止**硬编码 Mock 数据（如假造龙头股/涨停数）兜底，宁可无数据降级，不可假数据误导。
- **API 异常防爆**：AI 接口若返回 HTTP 403（模型无权限）等非 200 状态码，必须在内部 catch 并简报异常，严禁将超长 JSON 报错堆栈输出至前端导致 UI 崩溃。

### v1.1.0 架构决策

- **北向资金已砍**：A 股盘中不再披露实时北向资金，`NorthboundFetcher.kt` 不存在
- **龙虎榜是静态标签**：盘后数据，SharedPreferences 日期缓存（附带 cacheDate），每次访问检查过期自动刷新 HTTP
- **短线不看 PE/PB，看流通市值**：腾讯 API arr[45] 提取。规则——<50 亿游资可操控，>200 亿需板块级别资金共振
- **数据源调度**：资金流向 30s / 概念板块 5min / 龙虎榜按需 / 全市场情绪 5min
- **双 AI 独立配置**：主 AI（定时点评+异动复盘）和辅 AI（资金面深度分析）各自独立的 provider/key/model

### 详细参考

业务逻辑、架构决策、踩坑案例的完整文档在 `.claude_memory/INDEX.md` 索引目录下。
