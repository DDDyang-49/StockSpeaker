# StockSpeaker 项目记忆索引

> **处理任何新需求前，必须先用 grep 检索本 INDEX 找相关 ID，再 cat 对应详情文件。否则不得开始编码。**

| ID | 标签 | 摘要 |
|----|------|------|
| MEM_001 | 息屏保活 | HandlerThread 定时 + WakeLock 防御性重申请 + TTS 35s 超时 |
| MEM_002 | 双模异动 | isHot 判定 + 6 种 AI 模式 + 滑动窗口区间涨速 |
| MEM_003 | 播报轨道 | 三层优先级 + 箱体静默 + 大单阶梯 + 异动冷却独立 |
| MEM_004 | 数据防污染 | 东方财富 sentinel 2147483647 + 转债/ST 过滤 + API 零值语义 |
| MEM_005 | 双AI架构 | 主辅AI分工 + edgefn API 格式（禁 max_tokens）+ 模型自定义 |
| MEM_006 | 架构铁律 | Kotlin 原生 + TTS 零依赖 + OkHttp 3s + Foreground Service |
| MEM_007 | Prompt注入 | 全市场情绪 + stockSector 题材标签 + 固定人设 |
| MEM_008 | 大单方向 | price vs prevPrice 判断扫货/砸盘，禁用 changePct |
| MEM_009 | API字段核对 | 东方财富字段编号不可信，必须 curl 验证；f104/f105≠f47/f48 |
| MEM_010 | AI联动日志 | 提供商切换须同步更新模型名；所有 AI early return 必须有诊断日志 |
| MEM_011 | 息屏保活+ | 补充 MEM_001：loopHandler 移出 uiHandler、WakeLock 无超时、WifiLock |
