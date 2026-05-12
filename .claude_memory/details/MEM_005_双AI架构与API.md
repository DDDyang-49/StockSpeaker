# MEM_005 双AI架构与API配置

## 双AI分工

- **主AI**：定时点评 + 异动复盘（技术面分析）
- **辅AI**：混沌/长间隔时资金面深度分析
- 两个 AI 的 provider/key/model 独立配置，辅AI Key 留空则复用主AI

## API 提供商

- `deepseek` — `api.deepseek.com`（支持 reasoner 思考模式）
- `edgefn` — `api.edgefn.net`（用户主力平台，支持 qwen/GLM 等多模型）

## API 请求格式铁律

请求体只含 `model` + `messages`，**不得**包含 `max_tokens` 或 `temperature`。edgefn 平台上 qwen 能容错这些多余参数，但 GLM 等模型会直接拒绝。

## HTTP 403 处理

4 处 AI 调用点统一拦截：`✗ HTTP 403: API Key无权访问该模型，请检查设置。`

## 模型名称

UI 提供模型名称输入框，留空则用提供商默认模型。用户可自由输入 `GLM-5.1` 等任意模型名。
