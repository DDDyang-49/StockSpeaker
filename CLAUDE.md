# StockSpeaker 核心项目记忆

> **处理任何新需求前，必须先检索 `.claude_memory/INDEX.md`，再 `cat` 相关详情文件。否则不得开始编码。**

## 1. 业务逻辑铁律

- **异动大单方向**：绝对禁止用涨跌幅判断大单方向，必须用「当前价 vs 上一秒价格」对比来确定是扫货还是砸盘。
- **热门股双模判定**：isHot = 换手率 > 10% 或 量比 > 1.8（A 股公认活跃分界线），两者满足其一即为热门活跃股。
- **双模自适应阈值**：热门股用更高阈值过滤噪音，底部潜伏股用更低阈值捕捉「平地惊雷」式突然启动。
- **AI 异动检测 5 模式**：SPEED_ALERT（涨速异动）、SHARP_REVERSAL（高位急转）、VOLUME_BREAKOUT（底部放量突破，仅冷门股触发）、FAKE_BULL（诱多派发）、SILENT_DROP（无量空跌）。
- **播报三轨道优先级**：轨道1 实时异动（最高优先级，可打断一切）→ 轨道1b 异动平复等待（连续3轮无异动才进入复盘）→ 轨道1c 异动跟进（简洁播报 + AI复盘）→ 轨道2 定时常规播报 → 轨道3 双AI插播。
- **箱体静默**：当前价与上次播报价差 < 0.3% 且量比 < 2.0 时，跳过完整播报，只说「横盘震荡」。
- **大单阶梯报警**：当前手数 >= 上次报警手数 × 1.5 时，无视 30 秒冷却立即再次播报。
- **异动冷却独立**：大单、涨速、AI 异动三类各自独立 30 秒冷却，不同类型可分别触发互不阻塞。

## 2. 架构与依赖约定

- **Kotlin 原生 Android**：不使用 Flet/Flutter/React Native，原因——Flet APK 打包 `flet_audio` 必然崩溃，且无法实现可靠后台保活。
- **TTS 零依赖**：使用 Android 内置 `TextToSpeech`，支持中文离线，禁止引入第三方 TTS SDK。
- **网络层**：OkHttp 3 秒超时，所有行情请求独立子线程执行，不阻塞 UI 和 TTS。
- **全市场情绪**：强制使用 `MarketSentimentFetcher` 抓取东方财富/新浪真实 API 数据，拼接入 AI Prompt，严禁 Mock 数据兜底。
- **双 AI 架构**：主 AI 负责定时点评和异动复盘，辅 AI 负责混沌/长间隔时的资金面深度分析；两个 AI 的 provider/key/model 独立配置。
- **GitHub Actions 自动打包**：`build_apk.yml` 解析 `build.gradle.kts` 中的 `versionName` 自动创建 Release，无需手动上传 APK。

## 3. 防呆与避坑指南

- **息屏保活核心坑**：`Handler(Looper.getMainLooper()).postDelayed` 在 Doze 模式下主线程消息队列被挂起，定时循环必然停摆。必须用后台 `HandlerThread` 做定时，主线程只负责 UI 更新和 TTS 调用。
- **WakeLock 防御性重申请**：部分国产 ROM（小米/华为/OPPO）会在息屏后偷偷释放 WakeLock，必须在每次循环中检查 `isHeld` 并重新 `acquire(10000)`。
- **TTS 卡死保护**：息屏后 `UtteranceProgressListener.onDone` 可能永远不回调，导致 `isSpeaking` 永久为 true 阻塞整个播报队列。必须加 35 秒超时强制重置。
- **音频焦点不中断播报**：`AUDIOFOCUS_LOSS` 时仅重新请求 `MAY_DUCK` 共存，不停止播报——股票语音是短时金融信息，不应被切歌打断。
- **大单方向判断**：`data.changePct > 0` 代表全天累计涨跌，不能反映当前这一秒的买卖方向。必须用 `data.price > prevPrice`。
- **API 默认值语义**：行情 API 返回 0 可能表示「无数据」而非「0 手」，必须区分 `== 0`（跳过）和 `> 0`（有效），防止误报。
- **东方财富脏数据拦截**：API 使用 `2147483647`（Int.MAX_VALUE）作为数据缺失的 sentinel，所有解析到的数值必须过滤此值并重置为 0，否则 AI 会基于脏数据输出荒谬结论。
- **高标股转债污染**：`fetchTopStock` 不能直接取第一名，必须扩大候选到 pz=15 并遍历过滤「转债/ST/退/N」关键字，取第一个安全 A 股。
- **个股题材降维打击**：用户可手动输入题材标签（如"低空经济"），注入 AI Prompt 开头，强制 AI 结合题材判断个股是否背离大盘情绪，极大提升分析精准度。
