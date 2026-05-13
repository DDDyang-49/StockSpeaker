# MEM_010 AI 配置联动与日志

## 铁律

**UI 控件联动必须显式处理。** 下拉选择器切换时，关联的输入框/状态变量必须在回调中同步更新，不能依赖 `ifBlank` 或默认值链。

## v1.1.1.2 实踩案例

### Bug A：提供商切换后模型不更新

用户从 EdgeFn 切换到 DeepSeek，模型名输入框仍显示 EdgeFn 的 `Qwen3-235B-A22B-2507`。`ifBlank` 判断非空直接采用，导致 DeepSeek API 收到不存在的模型名返回 403/400。

**修复**：`onAiProvider` / `onAiTwoProvider` 回调中显式赋值 `aiModel = AI_PROVIDERS.find{...}?.model ?: ""`

### Bug B：AI 静默失败无日志

`generateSummary`、`generatePostAlertAnalysis`、`generateDualAnalysis` 三个函数在以下情况直接 `return` 不输出任何日志：
- 配置禁用（`!config.enabled`）
- 缺 API Key（`config.apiKey.isBlank()`）
- 数据不足（`prompt.isEmpty()`）

用户看到 AI 不工作但完全不知道原因。

**修复**：所有 early return 路径添加 `onLog` 诊断日志。`generateDualAnalysis` 增加启动日志标明 modelA/modelB。

## 403 错误日志改进

403 响应原来只显示 "无权访问"，不显示 API 返回的具体错误。修复后显示 `body?.take(80)` 原始内容。

## 检查清单

- [ ] 切换 AI 提供商芯片时，模型输入框是否同步更新？
- [ ] 所有 AI 函数的 early return 路径是否有诊断日志？
- [ ] HTTP 错误日志是否包含响应体内容？
