# 研讨手动模拟（历史参考）

> 本目录为早期手动模拟流程的提示词样例，**非当前产品主流程**。
> 当前系统说明见项目根目录 [README.md](../README.md)、[docs/WORKFLOW.md](../docs/WORKFLOW.md)、[docs/OUTPUT-DOCUMENTS.md](../docs/OUTPUT-DOCUMENTS.md)。

本目录包含手动模拟研讨流程所需的提示词模板，可用于不启动服务时的人工走查。

## 研讨议题

> **如何设计和实现 ai-debate-arena：一个使用 Playwright 浏览器自动化来编排多 AI 辩论的 Java 应用**

## 流程概览

```
第 1 轮 ─── 三个 AI 各自给出初始方案设计
   │
   ▼
第 2 轮 ─── 每个 AI 收到另外两个的回答，进行交叉批判
   │
   ▼
第 3 轮 ─── 每个 AI 回应别人对自己的批判（反驳）
   │
   ▼
收敛检查 ─── 确认各 AI 的共识与分歧
   │
   ▼
最终合成 ─── 将所有回答汇总为结构化 Markdown 方案文档
```

## 使用方式

### 第 1 轮
1. 打开 `01-round1-prompts.md`
2. 将 **Prompt for Gemini** 复制到 Gemini 对话框，提交
3. 将 **Prompt for ChatGPT** 复制到 ChatGPT 对话框，提交
4. 将 **Prompt for DeepSeek** 复制到 DeepSeek 对话框，提交
5. 将三个 AI 的回答分别填入对应位置

### 第 2 轮（需要第 1 轮的回答）
1. 打开 `02-round2-critique-prompts.md`
2. 在模板中填入第 1 轮各 AI 的实际回答
3. 将填好的 Prompt 分别发给三个 AI
4. 收集批判结果

### 第 3 轮（需要第 2 轮的结果）
1. 打开 `03-round3-rebuttal-prompts.md`
2. 在模板中填入前两轮的实际内容
3. 将填好的 Prompt 分别发给三个 AI
4. 收集反驳结果

### 收敛检查
1. 打开 `04-convergence-check-prompts.md`
2. 填入各 AI 的最终立场
3. 发给三个 AI，确认共识和分歧

### 最终合成（已由产出文档取代）

当前产品研讨结束后由整理服务按勾选类型生成多份产出文档（见 `docs/OUTPUT-DOCUMENTS.md`）。
本目录 `05-final-synthesis-template.md` 仅作手动模拟参考。

1. 打开 `05-final-synthesis-template.md`
2. 将所有轮次的回答填入对应位置
3. 得到完整的结构化 Markdown 方案文档

## 文件说明

| 文件 | 内容 |
|------|------|
| `01-round1-prompts.md` | 第 1 轮初始提问（三个 AI 各自的 Prompt） |
| `02-round2-critique-prompts.md` | 第 2 轮交叉批判模板 |
| `03-round3-rebuttal-prompts.md` | 第 3 轮反驳模板 |
| `04-convergence-check-prompts.md` | 收敛检查模板 |
| `05-final-synthesis-template.md` | 最终 Markdown 合成模板 |
