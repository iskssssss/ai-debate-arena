# AI 技术方案辩论报告

> **辩论主题：** 如何设计和实现 ai-debate-arena —— 一个使用 Playwright 浏览器自动化编排多 AI 辩论的 Java 应用
> **生成时间：** 2026-06-14
> **辩论状态：** 高度收敛
> **总轮数：** 4 轮
> **参与专家：** Gemini / ChatGPT / DeepSeek

---

## 📋 目录

1. [辩论概述](#辩论概述)
2. [各专家初始方案](#各专家初始方案)
3. [辩论过程](#辩论过程)
4. [收敛分析](#收敛分析)
5. [最终共识方案](#最终共识方案)
6. [争议与分歧](#争议与分歧)
7. [完整实施计划](#完整实施计划)
8. [风险与缓解](#风险与缓解)
9. [开发者决策参考](#开发者决策参考)

---

## 辩论概述

三个 AI 在核心架构（Adapter 模式、持久化 Profile、状态机编排、选择器外部化、对话重置、崩溃恢复）上达成高度共识；在收敛检测算法复杂度、并发调度策略、Headless/Headed 模式、上下文压缩层等实现细节上存在分歧。经过 4 轮辩论，最终产出了一个以 ChatGPT 平台化架构为主干、融合 Gemini 浏览器自动化经验与 DeepSeek 工程简化策略的完整实现方案。

### 参与专家

| 专家 | 平台 | 角色 |
|------|------|------|
| Gemini | gemini.google.com | 方案设计 + 批判 + 反驳 |
| ChatGPT | chatgpt.com | 方案设计 + 批判 + 反驳 |
| DeepSeek | chat.deepseek.com | 方案设计 + 批判 + 反驳 |

---

## 各专家初始方案

### Gemini 初始方案摘要

采用 **六边形架构（Ports & Adapters）+ 状态机工作流**，将核心编排逻辑与平台 UI 自动化严格解耦。`DebateOrchestrator` 驱动多轮辩论，`AIAgentAdapter` 接口隔离平台差异，`PlaywrightBrowserManager` 管理三个独立的 `launchPersistentContext` 持久化 Profile。

**架构思路：** 六边形架构 + 显式状态机 + 本地 Lucene TF-IDF 收敛检测

**核心设计决策：**
- 选择器外部化到 `application.yml`，支持热更新
- Stop Button 消失作为响应完成主信号
- 每轮结束后 JSON 快照持久化，支持崩溃恢复
- 平均 pairwise 余弦相似度 > 0.88 判定收敛

**亮点：** 架构边界清晰，UI 变更不影响核心引擎；`page.waitForFunction()` 浏览器原生谓词评估，避免 JVM 轮询开销

---

### ChatGPT 初始方案摘要

采用 **分层架构**：Spring Boot REST API → DebateEngine/RoundCoordinator → AiAgent 抽象层 → PlaywrightManager/SelectorRegistry → 三个 Chromium 持久化 Context。强调生产级平台化设计。

**架构思路：** 分层服务架构 + 显式状态机 + 语义级共识检测

**核心设计决策：**
- `AiAgent` 接口（GeminiAgent/ChatGPTAgent/DeepSeekAgent）
- `profiles/{chatgpt,gemini,deepseek}` 持久化目录
- Claim Extraction + Similarity + Agreement Classification 共识检测
- SelectorRegistry 多级 fallback（primary/aria/xpath/heuristic）
- Headed 模式优先，防反爬检测

**亮点：** 最完整的平台化设计，包含 DebateScheduler、PromptCompressionLayer、DebateStateStore、cancel/resume API

---

### DeepSeek 初始方案摘要

采用 **务实 Adapter 模式**：Spring Boot REST API → DebateOrchestrator（状态机）→ PlatformAdapter 三实现 → BrowserManager（持久化 Context）。强调快速可落地的工程实现。

**架构思路：** 简洁 Adapter 模式 + 状态机 + TF-IDF 余弦相似度收敛

**核心设计决策：**
- `PlatformAdapter` 接口 + 三平台适配器
- Lucene TF-IDF + `min(pairwise cosine) > 0.85` 收敛检测
- 虚拟线程并发（`newVirtualThreadPerTaskExecutor`）
- 每轮新建 Page 并在 finally 中关闭（后被批判修正）
- 18 天分阶段实施路线图

**亮点：** 工程细节最具体，包含完整包结构、pom.xml 依赖、PromptTemplateService（Spring AI 模板渲染）

---

## 辩论过程

### 第 1 轮：初始方案设计

| 专家 | 核心主张 | 独特观点 |
|------|---------|---------|
| Gemini | 六边形架构 + Stop Button 检测 + 持久化 Profile | 严格端口/适配器分离；`ConvergenceEngine` 用 Lucene 本地 NLP；动态 CSS 定位器提取当前轮响应气泡 |
| ChatGPT | 分层平台化架构 + 语义共识检测 | `DebateScheduler` 任务生命周期管理；`PromptCompressionLayer` 防上下文膨胀；Claim Extraction 替代纯文本相似度 |
| DeepSeek | 务实 Adapter + TF-IDF 收敛 + 18 天路线图 | `PromptTemplateService` 用 Spring AI 渲染批判/反驳模板；`page.waitForFunction()` 浏览器端等待；最完整的包结构和工时估算 |

### 第 2 轮：交叉批判

| 批判者 | 被批判者 | 主要赞同 | 主要反对 | 指出遗漏 |
|--------|---------|---------|---------|---------|
| Gemini | ChatGPT | 选择器外部化、状态机、持久化 Profile | 中期注入"总结5条要点"污染对话上下文；5秒文本稳定轮询太慢；强制 Headed 模式资源消耗大 | 缺少"新建对话"重置机制；REST 线程到 Playwright 单线程映射策略未说明 |
| Gemini | DeepSeek | `page.waitForFunction()` 浏览器原生等待；`--disable-blink-features` 反检测 | Java 17 使用虚拟线程无法编译；每轮关闭 Page 破坏对话连续性；响应长度 > 100 字符假设脆弱 | 多辩论并发时共享 Profile 导致消息交叉污染 |
| ChatGPT | Gemini | 六边形/Adapter 隔离、选择器外部化、State JSON 快照、Stop Button 检测 | 平均相似度掩盖离群分歧；`allTextContents().get(last)` 可能抓取旧消息 | 缺少对话重置、并发辩论队列、预辩论健康检查 |
| ChatGPT | DeepSeek | 性能导向等待策略、Lucene 语言学预处理 | 虚拟线程 Java 17 不兼容；Page 生命周期致命缺陷 | 多租户 Profile 隔离、对话重置 |
| DeepSeek | Gemini | 持久化 Profile、选择器外部化、Stop Button 监控、状态机 | 六边形过度工程；平均相似度有缺陷；Headless/Headed 切换不一致 | 缺少 prompt 模板示例、重试退避、对话重置、健康检查、报告格式 |
| DeepSeek | ChatGPT | 显式状态机阶段、选择器 fallback 链、硬限制（最大轮次+时长） | 额外 position extraction prompt 过度工程；Headed 模式偏好不当 | 缺少 per-platform 限速检测、并发隔离、对话重置、选择器热重载机制 |

### 第 3 轮：反驳回应

| 专家 | 被批判后坚持的 | 被批判后修改的 | 从他人吸收的 |
|------|--------------|--------------|-------------|
| Gemini | 坚持六边形架构（UI 频繁变化需要严格边界隔离） | 平均相似度 → Min(Pairwise) > 0.88；Headless 配置外部化为 `arena.playwright.headless=false`；动态 CSS 定位器替代数组索引 | ChatGPT 的预辩论 DOM 健康检查；DeepSeek 的 `page.waitForFunction()`；显式 `resetConversation()` |
| ChatGPT | 坚持平台化架构（DebateScheduler、State Store 为核心） | 放弃向 AI 发送"总结5条"的 meta-prompt，改为本地 PositionExtractor；新增 DebateStateStore、ConversationManager、PromptCompressionLayer | Gemini 的 Stop Button 检测；DeepSeek 的浏览器原生谓词评估；每轮 JSON 快照 |
| DeepSeek | 坚持务实 Adapter 架构和 TF-IDF 收敛（工程优先） | 虚拟线程 → `newCachedThreadPool()`；单 Page 跨轮保持 + `resetConversation()`；Stop Button + 文本稳定性双阶段等待；健康检查改为纯 DOM | Gemini 的 Stop Button 信号；ChatGPT 的 Selector fallback 链和 20 分钟硬时限；Gemini 的每轮快照持久化 |

### 第 4 轮：收敛确认

**完全共识点（三方一致）：**
1. Adapter/Platform 抽象层隔离平台差异（`PlatformAdapter` / `AiAgent`）
2. Playwright `launchPersistentContext` 持久化 Profile（`profiles/` 或 `user-data-dir/`）
3. 显式辩论状态机（INITIAL_ANSWER → CRITIQUE → REBUTTAL → CONSENSUS → COMPLETED）
4. 选择器 YAML 外部化配置
5. 显式 `resetConversation()` 重置对话（不能依赖 `new Page()`）
6. 多重完成检测：Stop Button + DOM 状态 + Text Stability（放弃"长度 > N"）
7. 每轮 DebateState JSON 快照持久化，支持崩溃恢复
8. 纯浏览器自动化，不调用官方 API
9. Spring Boot REST API 暴露辩论控制端点
10. 硬限制：最大轮次（如 6 轮）+ 最大时长（如 20 分钟）
11. Java 17 兼容（放弃虚拟线程）

**部分共识点：**
- **收敛检测**：DeepSeek + Gemini 支持 `min(pairwise cosine) > threshold`；ChatGPT 坚持 Claim Extraction + Agreement Classification（语义层）
- **并发模型**：ChatGPT + DeepSeek 支持队列/调度（DebateScheduler / per-platform FIFO）；Gemini 弱化任务调度，聚焦浏览器编排
- **健康检查**：Gemini + DeepSeek 坚持纯 DOM 检查（不发测试 Prompt）；ChatGPT 倾向 Pre-flight validation
- **上下文压缩**：ChatGPT + DeepSeek 接受 PromptCompression/PromptTruncator；Gemini 未纳入架构级组件

**持续分歧点：**
- **共识检测复杂度**：ChatGPT（语义 Claim Extraction）vs Gemini/DeepSeek（TF-IDF + Min Pairwise）
- **执行器策略**：ChatGPT（DebateScheduler）vs DeepSeek（CachedThreadPool + 平台队列）vs Gemini（未明确）
- **Headless vs Headed**：ChatGPT 坚持 Headed 防反爬；DeepSeek 倾向 Headless + 固定 UA；Gemini 外部化配置，默认 Headed
- **上下文防膨胀层**：ChatGPT（PromptCompressionLayer）vs DeepSeek（PromptTruncator）vs Gemini（动态定位器提取当前轮）
- **MVP vs 生产范围**：DeepSeek 偏快速上线；Gemini 偏稳定自动化；ChatGPT 偏长期平台化

---

## 收敛分析

### 各轮收敛趋势

```
第 1 轮相似度: 55%   ← 独立方案，差异最大（架构风格、收敛算法、并发模型分歧明显）
第 2 轮相似度: 72%   ← 批判后开始靠近（共识：Adapter、Profile、状态机、选择器外部化）
第 3 轮相似度: 85%   ← 反驳后进一步融合（修正 Page 生命周期、放弃 meta-prompt、统一完成检测）
第 4 轮相似度: 92%   ← 明确共识（11 项完全共识，剩余为实现复杂度分歧）
```

### 最终收敛状态

| 类别 | 数量 | 说明 |
|------|------|------|
| 完全共识点 | 11 | Adapter 抽象、持久化 Profile、状态机、选择器外部化、对话重置、多重完成检测、崩溃恢复、纯浏览器自动化、REST API、硬限制、Java 17 |
| 部分共识点 | 4 | 收敛检测算法、并发调度、健康检查方式、上下文压缩 |
| 持续分歧点 | 5 | 共识检测复杂度、执行器策略、Headless/Headed、上下文防膨胀层、MVP vs 生产范围 |

---

## 最终共识方案

> 以下为三个 AI 经过 4 轮辩论后达成的共识方案。综合三方最终声明，推荐以 **ChatGPT 平台化架构为主干**，吸收 **Gemini 浏览器自动化经验** 与 **DeepSeek 工程简化策略**。

### 架构设计

```
Spring Boot REST API
        │
        ▼
DebateScheduler（队列 / 取消 / 恢复）
        │
        ▼
DebateOrchestrator（State Machine）
  INITIAL_ANSWER → CRITIQUE → REBUTTAL → CONSENSUS → COMPLETED
        │
        ▼
ConversationManager（resetConversation / 对话生命周期）
        │
        ▼
PlatformAdapter（AiAgent 接口）
  ├─ ChatGPTAdapter
  ├─ GeminiAdapter
  └─ DeepSeekAdapter
        │
        ▼
Playwright + Persistent Profiles
  profiles/chatgpt | profiles/gemini | profiles/deepseek
        │
        ▼
辅助组件：
  DebateStateStore（每轮 JSON 快照）
  SelectorRegistry（YAML + fallback 链）
  PromptCompressionLayer（上下文管理）
  ConvergenceDetector（共识检测）
```

### 项目结构

```
src/main/java/com/arena/aidebate/
├── ArenaApplication.java
├── config/
│   ├── PlaywrightConfig.java          # headless、profile 目录、启动参数
│   └── AppConfig.java
├── controller/
│   ├── DebateController.java          # POST/GET/cancel/resume/report
│   ├── ProfileController.java         # 登录状态查看
│   └── dto/
├── model/
│   ├── DebateSession.java
│   ├── DebateRound.java
│   ├── ParticipantResponse.java
│   └── DebateState.java               # 状态机枚举
├── orchestrator/
│   ├── DebateOrchestrator.java        # 核心编排器
│   ├── DebateScheduler.java           # 任务队列与生命周期
│   └── ConversationManager.java       # 对话重置管理
├── adapter/
│   ├── PlatformAdapter.java           # 统一接口
│   ├── ChatGPTAdapter.java
│   ├── GeminiAdapter.java
│   ├── DeepSeekAdapter.java
│   └── FallbackSelector.java          # 多级选择器 fallback
├── browser/
│   ├── PlaywrightManager.java         # Playwright 生命周期
│   └── ProfileManager.java            # 持久化 profile 管理
├── convergence/
│   ├── ConvergenceDetector.java
│   └── TextSimilarityCalculator.java  # Phase 1: TF-IDF; Phase 2: Claim Extraction
├── prompts/
│   ├── PromptTemplateService.java
│   └── PromptCompressionLayer.java
├── persistence/
│   └── DebateStateStore.java          # JSON 快照读写
├── reporting/
│   ├── SynthesisGenerator.java
│   └── MarkdownRenderer.java
└── util/
    └── RetryUtils.java

src/main/resources/
├── application.yml
├── selectors/
│   ├── chatgpt.yml
│   ├── gemini.yml
│   └── deepseek.yml
└── templates/
    ├── critique-prompt.st
    ├── rebuttal-prompt.st
    └── final-report.st
```

### 技术栈

| 技术 | 版本 | 用途 | 共识度 |
|------|------|------|--------|
| Java | 17 | 运行环境 | 100% |
| Maven | 3.6+ | 构建工具 | 100% |
| Spring Boot | 3.2.5 | 应用框架 | 100% |
| Playwright | 1.54.0 | 浏览器自动化 | 100% |
| Spring AI | 1.0.x | Prompt 模板渲染（非 API 调用） | 90% |
| Apache Lucene | 9.x | TF-IDF 文本预处理与相似度 | 85% |
| Jackson | 2.x | JSON 状态快照序列化 | 100% |
| Caffeine | 3.x | 选择器/状态缓存（ChatGPT 建议） | 70% |

### 核心实现策略

**1. 浏览器自动化层：**
- 使用 `launchPersistentContext` 为每个平台创建独立 `user-data-dir`
- 每个辩论保持单一 `Page` 跨轮存活，辩论结束后才关闭
- 默认 Headed 模式（`arena.playwright.headless=false`），附加 `--disable-blink-features=AutomationControlled`
- 完成检测三级链：Stop Button 消失 → DOM 稳定 → Text Stability（500ms 轮询，连续 2 次不变）
- 选择器 YAML 外部化 + FallbackSelector（primary → secondary → XPath → aria-label）
- 纯 DOM 健康检查（不发测试 Prompt），验证登录状态和输入框可见性

**2. 辩论编排引擎：**
- 显式状态机驱动：INITIAL_ANSWER → CRITIQUE → REBUTTAL → CONSENSUS → COMPLETED
- DebateScheduler 管理队列、取消（`POST /api/debates/{id}/cancel`）、恢复（`POST /api/debates/{id}/resume`）
- 每轮通过 `ConversationManager.resetConversation()` 点击"New Chat"隔离上下文
- 硬限制：最大 6 轮 + 最大 20 分钟，防止无限循环
- 单平台故障优雅降级：标记 FAILED，最少 2 个 AI 可继续辩论
- `Executors.newCachedThreadPool()` 并行驱动三平台适配器（Java 17 兼容）

**3. Profile 持久化管理：**
```
profiles/
 ├─ chatgpt/    # launchPersistentContext user-data-dir
 ├─ gemini/
 └─ deepseek/
```
- 首次运行 Headed 模式手动登录，Cookie 和风控状态持久化
- 固定真实 User-Agent（不轮换），与 Profile 指纹一致
- ProfileController 提供登录状态查询接口

**4. 收敛检测机制：**
- **Phase 1（MVP）**：Lucene EnglishAnalyzer 预处理 → TF-IDF 向量 → `min(pairwise cosine) > 0.85`
- **Phase 2（增强）**：Claim Extraction + Semantic Similarity + Agreement Classification（ChatGPT 方案）
- 绝不向 AI 发送额外的"总结共识" meta-prompt
- 本地正则启发式检测否定词（"disagree"、"incorrect"）惩罚相似度分数

**5. 最终报告生成：**
- 每轮结束后 DebateStateStore 持久化完整 JSON 快照
- 辩论完成后 SynthesisGenerator 渲染 `final-report.st` 模板
- MarkdownRenderer 格式化输出：轮次标题、时间戳、各 AI 回答、批判、反驳、共识分析
- `GET /api/debates/{id}/report` 返回最终 Markdown 报告

---

## 争议与分歧

> 以下为辩论结束后仍未完全统一的分歧点。

### 分歧 1：共识检测算法复杂度

| 立场 | 支持者 | 理由 |
|------|--------|------|
| 方案 A：Claim Extraction + 语义分类 | ChatGPT | 纯文本相似度无法识别逻辑反转（"X 高效" vs "X 不高效"），真正目标是观点一致而非文字相似 |
| 方案 B：TF-IDF + Min Pairwise Cosine | DeepSeek、Gemini | 简单、可解释、实现快；Lucene 本地预处理已足够应对技术辩论场景 |

**建议：** Phase 1 采用方案 B 快速上线验证；若实测中 min(cosine) 频繁误判共识，再引入方案 A 作为可选增强层。核心原则：绝不向 AI 发送额外分析 Prompt。

### 分歧 2：Headless vs Headed 执行模式

| 立场 | 支持者 | 理由 |
|------|--------|------|
| 方案 A：强制 Headed | ChatGPT | 最小化 Canvas/网络/生物特征反爬指纹触发 |
| 方案 B：Headless + 反检测参数 | DeepSeek | 服务器/CI 环境更安静，持久化 Profile + 固定 UA 已足够 |
| 方案 C：外部化配置，默认 Headed | Gemini | `arena.playwright.headless=false` 作为安全基线，可按环境切换 |

**建议：** 采用方案 C，默认 Headed 用于本地开发和首次登录；提供配置项允许切换。若 Headless 在实测中稳定运行数百轮无封号，可逐步迁移。

### 分歧 3：并发调度策略

| 立场 | 支持者 | 理由 |
|------|--------|------|
| 方案 A：DebateScheduler 统一调度 | ChatGPT | 生产级任务管理：队列、取消、恢复、生命周期完整 |
| 方案 B：per-platform FIFO 队列 | DeepSeek | 实现简单，序列化同平台并发辩论，避免消息交叉 |
| 方案 C：编排层线程池，适配器轻量 | Gemini | 保持适配器层单会话聚焦，线程协调上移到编排层 |

**建议：** MVP 采用方案 B（per-platform FIFO 队列）；若需 cancel/resume 和多辩论管理，升级为方案 A 的 DebateScheduler。

### 分歧 4：上下文防膨胀策略

| 立场 | 支持者 | 理由 |
|------|--------|------|
| 方案 A：PromptCompressionLayer | ChatGPT | 架构级组件，显式压缩历史轮次到 MAX_CONTEXT_SIZE |
| 方案 B：PromptTruncator 工具类 | DeepSeek | 保留最近 N 字符或可选摘要，轻量实用 |
| 方案 C：动态定位器提取当前轮响应 | Gemini | 不压缩 Prompt，通过 DOM 定位器只抓取当前轮气泡 |

**建议：** 短期采用方案 C + 方案 B 组合（定位器隔离 + 可选截断）；长期辩论场景引入方案 A。

### 分歧 5：架构风格（六边形 vs 简洁 Adapter）

| 立场 | 支持者 | 理由 |
|------|--------|------|
| 方案 A：六边形 Ports & Adapters | Gemini | UI 频繁变化需要严格边界，核心引擎与适配器完全隔离 |
| 方案 B：简洁 Adapter 层 | DeepSeek、ChatGPT | 三平台场景下六边形增加不必要样板代码，Adapter 接口已足够 |

**建议：** 采用方案 B 的简洁 Adapter 接口，但保留 Gemini 强调的边界原则（所有 Playwright 逻辑严格限制在 Adapter 实现内，核心编排器不直接操作 DOM）。

---

## 完整实施计划

### Phase 1：基础设施搭建
- [ ] 初始化 Maven 项目，配置依赖（Spring Boot 3.2.5、Playwright 1.54.0、Lucene、Spring AI）
- [ ] 创建 Spring Boot 启动类和基础配置（`application.yml`）
- [ ] 实现 PlaywrightManager（Playwright 生命周期管理）
- [ ] 实现 ProfileManager（浏览器 profile 持久化，三个平台独立目录）
- **预计工时：** 3 天

### Phase 2：浏览器适配器
- [ ] 定义 PlatformAdapter 接口（initialize、sendPrompt、resetConversation、healthCheck、close）
- [ ] 实现 ChatGPTAdapter（首个验证平台）
- [ ] 实现 GeminiAdapter
- [ ] 实现 DeepSeekAdapter
- [ ] 创建选择器 YAML 配置文件（chatgpt.yml、gemini.yml、deepseek.yml）
- [ ] 实现 SelectorProvider + FallbackSelector（多级 fallback 链）
- [ ] 实现 Stop Button + Text Stability 完成检测
- **预计工时：** 5 天

### Phase 3：辩论引擎
- [ ] 创建数据模型（DebateSession、DebateRound、ParticipantResponse、DebateState）
- [ ] 实现 DebateOrchestrator（状态机核心编排器）
- [ ] 实现 ConversationManager（对话重置）
- [ ] 实现 TextSimilarityConvergenceDetector（TF-IDF + min pairwise）
- [ ] 实现 PromptTemplateService（Spring AI 模板渲染批判/反驳 Prompt）
- [ ] 实现 DebateStateStore（每轮 JSON 快照）
- [ ] 实现 per-platform FIFO 队列
- **预计工时：** 5 天

### Phase 4：报告生成
- [ ] 实现 SynthesisGenerator（final-report.st 模板渲染）
- [ ] 实现 MarkdownRenderer（格式化处理，含轮次标题和时间戳）
- **预计工时：** 2 天

### Phase 5：REST API
- [ ] 实现 DebateController（POST 启动 / GET 状态 / GET 报告 / POST 取消 / POST 恢复）
- [ ] 实现 ProfileController（登录状态查看 / 手动登录引导）
- [ ] 实现 GlobalExceptionHandler
- [ ] 实现 DOM 健康检查 API
- **预计工时：** 2 天

### Phase 6：测试与文档
- [ ] 端到端集成测试（单平台 → 三平台完整辩论）
- [ ] 调试模式验证（headless=false，观察浏览器行为）
- [ ] 各平台选择器验证与 fallback 测试
- [ ] 崩溃恢复测试（中断后 resume）
- [ ] README 完善（首次登录流程、配置说明）
- **预计工时：** 3 天

**总预计工时：** 20 天

---

## 风险与缓解

| 风险 | 严重度 | 概率 | 缓解措施 |
|------|--------|------|---------|
| 平台 UI 变化导致选择器失效 | 🔴 高 | 🟡 中 | 选择器 YAML 外部化 + 多级 fallback + 启动时 DOM 健康检查 |
| 平台反爬检测导致封号 | 🔴 高 | 🟢 低 | 持久化 profile 模拟正常用户 + Headed 模式 + 固定 UA + 合理操作间隔 |
| 单平台故障导致辩论中断 | 🟡 中 | 🟡 中 | 优雅降级，标记 FAILED，最少 2 AI 可继续 |
| AI 响应超时 | 🟡 中 | 🟡 中 | 120s 超时 + 重试 3 次 + 指数退避 |
| 登录 Cookie 过期 | 🟡 中 | 🟡 中 | 辩论前 DOM 健康检查 + 提示用户重新登录 |
| Page 生命周期管理错误 | 🔴 高 | 🟢 低 | 单 Page 跨轮保持 + resetConversation() 隔离 + 代码审查 |
| 上下文窗口溢出 | 🟡 中 | 🟡 中 | PromptTruncator + 每轮 resetConversation + 硬限制最大轮次 |
| Java 17 兼容性 | 🟢 低 | 🟢 低 | 使用 CachedThreadPool，不使用虚拟线程 |

---

## 开发者决策参考

### 决策矩阵

| 设计维度 | 推荐方案 | 置信度 | 替代方案 |
|---------|---------|--------|---------|
| 浏览器自动化库 | Playwright Java 1.54.0 | 100% | Selenium（更慢，等待机制弱） |
| 架构模式 | 简洁 Adapter + 状态机编排 | 90% | 六边形架构（Gemini，更严格但样板多） |
| 持久化方案 | launchPersistentContext + profiles/ 目录 | 100% | 每次手动登录（不可接受） |
| 收敛算法 | Phase 1: TF-IDF min-pairwise; Phase 2: Claim Extraction | 85% | 纯平均相似度（已否决） |
| 错误处理策略 | 优雅降级 + 每轮快照 + resume API | 95% | 全有或全无（已否决） |
| 完成检测 | Stop Button → DOM Stable → Text Stable | 95% | 纯文本长度阈值（已否决） |
| 并发模型 | per-platform FIFO 队列 → DebateScheduler | 85% | 无队列直接并发（消息交叉风险） |
| 执行模式 | 默认 Headed，配置化切换 | 80% | 强制 Headless（反爬风险） |

### 实施建议

1. **核心资产是"状态"而非"浏览器"** — State Machine + Snapshot Recovery + Conversation Lifecycle 必须成为架构中心，而非将 Playwright 操作散落各处
2. **先单平台验证再扩展** — 从 ChatGPTAdapter 开始，验证持久化登录、Stop Button 检测、resetConversation 后再实现 Gemini 和 DeepSeek
3. **选择器 fallback 从第一天开始** — 外部化所有选择器并支持多级 fallback，这是应对 UI 频繁变化的最佳防御
4. **绝不向 AI 发送分析 Prompt** — 共识检测、位置提取全部在本地完成，避免污染对话上下文
5. **MVP 用 DeepSeek 简化策略，生产用 ChatGPT 平台化架构** — TF-IDF + CachedThreadPool + JSON Snapshot 快速验证；验证通过后逐步引入 DebateScheduler 和 Claim Extraction

### 踩坑预警

> 以下是从辩论中识别出的最容易出问题的地方：

1. **Page 生命周期** — 每轮关闭 Page 会销毁对话历史，Round 2 将从空白聊天开始。必须保持单 Page 跨轮存活，用 `resetConversation()` 重置
2. **虚拟线程 Java 17** — `newVirtualThreadPerTaskExecutor()` 在 Java 17 无法编译，必须使用 `newCachedThreadPool()`
3. **Meta-Prompt 污染** — 向运行中的聊天发送"总结你的立场"会永久污染上下文，后续轮次 AI 将回应总结而非辩论内容
4. **平均相似度陷阱** — 两个 AI 高度一致、第三个严重分歧时，平均分数仍可能超过阈值。必须使用 `min(pairwise similarity)`
5. **并发消息交叉** — 多个辩论共享同一 BrowserContext 会导致 Prompt 交叉污染。必须 per-platform 队列序列化

### 延伸阅读方向

- Playwright Java 官方文档：https://playwright.dev/java/
- Spring AI 参考指南：https://docs.spring.io/spring-ai/reference/
- Chromium Persistent Context：https://playwright.dev/java/docs/api/class-browsertype#browser-type-launch-persistent-context
- Apache Lucene Analyzer 文档：https://lucene.apache.org/core/9_0_0/core/org/apache/lucene/analysis/Analyzer.html
- Playwright waitForFunction API：https://playwright.dev/java/docs/api/class-page#page-wait-for-function

---

> 📝 本报告由人工模拟 AI Debate Arena 流程生成。
> 辩论流程：初始方案 → 交叉批判 → 反驳 → 收敛确认 → 综合合成。
> 此报告作为编码参考，实际实现时应结合具体环境验证。
