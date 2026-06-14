# Consensus Assessment

## Points of Full Agreement

三位专家已经达成一致的核心设计决策：

### 1. Adapter/Platform Abstraction 是正确方向

都认可：

```java
PlatformAdapter
```

或等价的：

```java
AiAgent
```

抽象层。

平台差异（ChatGPT、Gemini、DeepSeek）必须隔离到适配器内部。

---

### 2. 持久化 Browser Profile

都支持：

```text
profiles/
 ├─ chatgpt
 ├─ gemini
 └─ deepseek
```

或：

```text
user-data-dir/*
```

原因：

* 避免反复登录
* 保留 Cookie
* 保留风控状态
* 提高稳定性

这是 Playwright 自动化的关键工程实践。

---

### 3. 显式 Debate State Machine

都认为辩论流程必须状态化：

```text
INITIAL_ANSWER
CRITIQUE
REBUTTAL
CONSENSUS
COMPLETED
```

而不是用嵌套循环硬编码。

---

### 4. Selector 外部配置

都支持：

```yaml
selectors:
```

外部化管理。

原因：

* AI 网站 UI 经常变化
* 避免重新编译
* 便于快速修复

---

### 5. Conversation Reset 必须显式实现

都认可：

```java
resetConversation()
```

或：

```java
createNewConversation()
```

概念。

不能依赖：

```java
new Page()
```

来获得新对话。

---

### 6. Completion Detection 需要多重判断

虽然细节不同，但三人都放弃了：

```text
长度 > N
```

这种粗暴方案。

都转向：

```text
Stop Button
+
DOM 状态
+
Text Stability
```

组合检测。

---

### 7. Crash Recovery 必须存在

都接受：

```java
DebateState Snapshot
```

持久化。

无论叫：

```java
DebateStateStore
```

还是：

```java
JSON Snapshot
```

本质一致：

```text
每轮落盘
支持恢复
```

---

## Points of Partial Agreement

## 1. Convergence / Consensus Detection

### ChatGPT + Gemini

支持：

```text
Claim Extraction
+
Similarity
+
Agreement Classification
```

强调：

```text
语义层一致性
```

而不是简单文本相似度。

---

### DeepSeek（异议）

坚持：

```text
TF-IDF
+
Cosine Similarity
```

原因：

* 简单
* 可解释
* 实现快

---

### 结论

ChatGPT 与 Gemini 更接近。

DeepSeek 方案适合作为 Phase 1 MVP。

---

## 2. Concurrency Model

### ChatGPT + DeepSeek

明确认为需要：

```text
Queue
Scheduling
Cancellation
Resume
```

生产级任务管理。

---

### Gemini（弱化）

没有把调度系统作为核心组件。

更关注：

```text
Browser orchestration
```

而不是：

```text
Task orchestration
```

---

### 结论

对于 REST 服务化系统：

```text
ChatGPT + DeepSeek
```

明显更完整。

---

## 3. Health Check

### Gemini + DeepSeek

支持：

```text
DOM-based health check
```

绝不发送测试 Prompt。

---

### ChatGPT

虽然最终方案没有明确写出，但也倾向：

```text
Pre-flight validation
```

检查页面状态。

差异不大。

---

## 4. Context Compression

### ChatGPT + DeepSeek

都接受：

```text
PromptCompression
PromptTruncator
```

只是复杂度不同。

---

### Gemini（未明确纳入）

没有把 Context 管理提升到架构级组件。

---

### 结论

长期运行系统必须有 Context 管理层。

因此更偏向 ChatGPT/DeepSeek。

---

# Points of Persistent Disagreement

## 1. Consensus Detection 的复杂度

### ChatGPT

坚持：

```text
Claim Extraction
+
Agreement Classification
```

语义共识。

---

### Gemini

坚持：

```text
Pairwise Similarity
```

但增强预处理。

---

### DeepSeek

坚持：

```text
TF-IDF
Cosine
Threshold
```

工程优先。

---

这是最大的理论分歧。

本质问题：

```text
共识 = 文字相似？
还是观点一致？
```

我认为：

```text
观点一致
```

才是真正目标。

---

## 2. Executor Strategy

### ChatGPT

引入：

```java
DebateScheduler
```

统一任务生命周期管理。

---

### DeepSeek

```java
newCachedThreadPool()
```

* 平台队列。

---

### Gemini

基本未涉及。

---

这是：

```text
系统架构层
vs
实现层
```

的分歧。

---

## 3. MVP vs Production Scope

### DeepSeek

明显偏：

```text
尽快上线
```

方案。

---

### Gemini

偏：

```text
稳定自动化
```

方案。

---

### ChatGPT

偏：

```text
长期平台化
```

方案。

---

这是路线分歧，不是技术正确性分歧。

---

# Final Statement

## Definitive Architecture Recommendation

我最终推荐：

**以 ChatGPT 方案作为主架构，吸收 Gemini 的浏览器自动化经验，以及 DeepSeek 的工程简化策略。**

最终架构：

```text
Spring Boot

        ↓

REST API

        ↓

DebateScheduler

        ↓

DebateOrchestrator
(State Machine)

        ↓

ConversationManager

        ↓

PlatformAdapter
 ├─ ChatGPT
 ├─ Gemini
 └─ DeepSeek

        ↓

Playwright
Persistent Profiles
```

辅以：

```text
DebateStateStore
PromptCompressionLayer
SelectorRegistry
```

以及：

```text
StopButton
+
DOM Stable
+
Text Stable
```

完成检测。

共识检测采用：

```text
Claim Extraction
+
Semantic Similarity
+
Agreement Classification
```

而非纯 TF-IDF。

---

## Confidence Level

**HIGH**

因为经过三轮辩论后，三位专家已经在约 80% 的关键架构决策上收敛，剩余分歧主要集中在实现复杂度而非总体方向。

---

## The Single Strongest Reason

**AI 辩论系统的核心资产是“状态”，不是“浏览器”。**

因此：

```text
State Machine
+
Snapshot Recovery
+
Conversation Lifecycle
```

必须成为架构中心。

这正是 ChatGPT 最终方案最完整、最系统化的地方。

---

## What Would Change My Mind

如果出现以下证据，我会转向 DeepSeek 的简化方案：

```text
1. 辩论规模始终 < 10 并发
2. 每场辩论 < 5 轮
3. 不需要恢复运行
4. 不需要长期历史存储
5. 不需要自动共识分析
```

在这种场景下：

```text
TF-IDF
+
CachedThreadPool
+
JSON Snapshot
```

的轻量架构会拥有更高的性价比。

但只要目标是：

```text
“AI Debate Arena Platform”
```

而不是：

```text
“AI Debate Demo”
```

我仍然会选择 ChatGPT 最终方案作为基线架构。
