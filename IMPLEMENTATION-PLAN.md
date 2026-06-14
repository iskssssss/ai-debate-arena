# ai-debate-arena 实施任务计划

> **状态：Phase 1~6 全部完成（2026-06-15）**
>
> 本文档为早期实施规划，保留原始任务拆分供回溯参考。
> 当前功能与 API 以 [README.md](../README.md)、[docs/WORKFLOW.md](WORKFLOW.md)、[docs/OUTPUT-DOCUMENTS.md](OUTPUT-DOCUMENTS.md) 为准。
>
> 原始预估：基于三 AI 4 轮辩论共识方案拆分 | 16 个任务 | 预计 20 天

---

## 已完成功能摘要（2026-06-15）

| 模块 | 状态 | 说明 |
|------|:----:|------|
| 基础设施 | ✅ | Spring Boot 3.2.5、Lucene 9.10、Spring AI 模板、YAML 选择器 |
| 浏览器层 | ✅ | PlaywrightManager（独立实例 + ShutdownHook）、ProfileManager、三平台适配器、BrowserProcessCleaner 残留进程清理 |
| 研讨编排 | ✅ | DebateOrchestrator（状态机 + 异步并行）、收敛检测（中英混合 TF-IDF）、快照持久化、通道参与方选择 |
| 整理与产出 | ✅ | ApiJudgeService / ChannelJudgeService 双模式、OutputDocumentService 13 种产出文档、兼容 `/report` |
| REST API | ✅ | 研讨 CRUD、通道注册表、Profile 管理、三方 API 配置（`/api/profiles/api-config`） |
| Web 控制台 | ✅ | Vue 3 ESM 组件化架构（App.js + components/ + composables/）、三步向导、研讨历史 master-detail |
| 通道系统 | ✅ | ChannelRegistryService + ChannelRegistryStore 持久化；内置浏览器通道 + 自定义 API 通道（`api-{uuid}`）；apiVerified 检测门槛；最多 8 个自定义通道 |
| 三方 API 配置 | ✅ | ThirdPartyApiSettingsService + ThirdPartyApiSettingsStore；全局 DeepSeek API Key / BaseURL / Model 配置与连通性检测；ApiParticipantService 接入层 |
| 桌面客户端 | ✅ | Electron 42.x，三页签快捷键（Ctrl+1/2/3），移除废弃的「研讨详情」菜单项 |

---

## 依赖关系图

```
Phase 1: 基础设施 ─────────────────────────────────────────────────────
#1  [x] pom + 资源文件     ──── ✅ 已完成
#2  [x] 配置类 + 启动类     ──── ✅ 已完成
#3  [x] 数据模型           ──── ✅ 已完成
#4  [x] 收敛检测器         ──── ✅ 已完成
#5  [x] 模板渲染服务       ──── ✅ 已完成
#6  [x] 状态持久化         ──── ✅ 已完成
     │
Phase 1-B: 浏览器层（严格串行）─────────────────────────────────────────
#7  [x] PlaywrightManager   ──── ✅ 已完成
#8  [x] ProfileManager      ──── ✅ 已完成
#9  [x] PlatformAdapter接口 ──── ✅ 已完成
#10 [x] 三平台适配器实现    ──── ✅ 已完成
     │
Phase 2-3: 辅助组件 ───────────────────────────────────────────────────
#11 [x] ConversationManager  ──── ✅ 已完成
     │
Phase 3: 核心编排 ─────────────────────────────────────────────────────
#12 [x] DebateOrchestrator   ──── ✅ 已完成（核心引擎）
     │
Phase 4-5: 输出 + API ─────────────────────────────────────────────────
#13 [x] 报告生成器           ──── ✅ 已完成
#14 [x] per-platform 队列    ──── ✅ 已完成
#15 [x] REST API + 异常处理  ──── ✅ 已完成
     │
Phase 6: 验证 ─────────────────────────────────────────────────────────
#16 [x] 端到端测试           ──── ✅ 已完成
```

---

## Phase 1: 基础设施搭建

### #1 pom + 资源文件（90% 已完成）

| 项 | 状态 | 产出 |
|----|------|------|
| `pom.xml` | ✅ 已完成 | Spring Boot 3.2.5 / Playwright 1.54.0 / Spring AI 1.0.0 / Hutool / Guava |
| **Lucene 依赖** | ✅ 已完成 | `org.apache.lucene:lucene-core` + `lucene-analysis-common` 9.10.0 |
| `application.yml` | ✅ 已完成 | debate / browser / ai-platforms 配置段 |
| `application-debug.yml` | ✅ 已完成 | headless=false / slowMo=500 |
| 选择器 YAML ×3 | ✅ 已完成 | gemini / chatgpt / deepseek 选择器配置 |
| 辩论模板 .st ×4 | ✅ 已完成 | initial / critique / rebuttal / convergence-check |
| 报告模板 .st ×1 | ✅ 已完成 | final-report.st |

**（Lucene 依赖已在 pom.xml 中配置完成。）**

---

### #2 配置类 + 启动类

| 文件 | 职责 |
|------|------|
| `config/DebateConfig.java` | `@ConfigurationProperties("debate")` — maxRounds=6, convergenceThreshold=0.85, aiTimeout=120s, executor pool |
| `config/BrowserConfig.java` | `@ConfigurationProperties("browser")` — headless, channel, profileBaseDir, viewport, slowMo |
| `config/AiPlatformProperties.java` | `@ConfigurationProperties("ai-platforms")` — 三平台 URL / name / enabled |
| `config/AsyncConfig.java` | `@Configuration` + `@EnableAsync` — 配置 `TaskExecutor`（CachedThreadPool） |
| `DebateArenaApplication.java` | `@SpringBootApplication` 启动类 |

**配置设计要点：**
- 所有硬编码阈值外部化到 `application.yml`
- `@ConfigurationProperties` 自动绑定，IDE 有提示
- Async 线程池大小：core=4, max=8

---

### #3 数据模型

| 文件 | 字段 |
|------|------|
| `model/AiPlatform.java` | 枚举 `GEMINI("gemini.google.com"), CHATGPT("chatgpt.com"), DEEPSEEK("chat.deepseek.com")` |
| `model/DebateStatus.java` | 枚举 `CREATED, RUNNING, INITIAL_ANSWER, CRITIQUE, REBUTTAL, CONSENSUS, CONVERGED, MAX_ROUNDS, FAILED` |
| `model/DebateSession.java` | sessionId, topic, status, rounds `List<DebateRound>`, createdAt, updatedAt, maxRounds, convergenceThreshold |
| `model/DebateRound.java` | roundNumber, roundType, responses `Map<AiPlatform, ParticipantResponse>`, critiques `Map<AiPlatform, List<Critique>>` |
| `model/ParticipantResponse.java` | platform, content, timestamp, responseTimeMs |
| `model/Critique.java` | fromPlatform, targetPlatform, agreementPoints, disagreementPoints, omissions, qualityRating |
| `model/ConvergenceResult.java` | converged `boolean`, averageSimilarity `double`, minPairwiseSimilarity `double`, agreedPoints `List<String>` |
| `model/DebateRequest.java` | topic, maxRounds `@Min(2) @Max(10)`, convergenceThreshold `@DecimalMin("0.5") @DecimalMax("1.0")` |
| `model/DebateResult.java` | sessionId, status, totalRounds, markdownReport, completedAt |

**要点：**
- Lombok `@Data @Builder @NoArgsConstructor @AllArgsConstructor`
- `DebateSession` 用 `synchronized` 保护状态变更（简单场景，暂不引入锁框架）
- 所有时间戳用 `LocalDateTime`

---

### #4 收敛检测器

| 文件 | 职责 |
|------|------|
| `convergence/ConvergenceDetector.java` | 接口：`ConvergenceResult check(List<ParticipantResponse> responses)` |
| `convergence/TextSimilarityConvergenceDetector.java` | 实现：Lucene EnglishAnalyzer → TF-IDF 向量 → min(pairwise cosine) |

**算法流程：**
```
输入: 三个 AI 的本轮回答
  ↓
Step 1: EnglishAnalyzer 预处理（tokenize + stemming + stopword removal）
  ↓
Step 2: 构建 TF-IDF 向量（三份回答共享词典）
  ↓
Step 3: 计算所有 pairwise cosine similarity
  ↓
Step 4: 否定词启发式惩罚（检测 "disagree"、"incorrect"、"but" 等转折词）
  ↓
Step 5: 取 min(pairwise) → 与 threshold(0.85) 比较
  ↓
输出: ConvergenceResult（converged, averageSimilarity, minPairwise, agreedPoints）
```

**关键约束：**
- 绝不向 AI 发送额外的"总结共识" meta-prompt
- 否定词检测用本地正则，不依赖外部 NLP 服务
- 预留接口扩展点，便于 Phase 2 升级为 Claim Extraction

---

### #5 模板渲染服务

| 文件 | 职责 |
|------|------|
| `prompts/PromptTemplateService.java` | 加载 `.st` 文件，Spring AI `PromptTemplate` 渲染，返回 String |
| `prompts/CompressionService.java` | 截断历史轮次：保留最近 N 字符（默认 4000），超出部分用 `[已截断...]` 标记 |

**方法签名：**
```java
// PromptTemplateService
String renderInitialPrompt(String topic);
String renderCritiquePrompt(String topic, String expertAName, String expertAResponse,
                              String expertBName, String expertBResponse, String yourPreviousResponse);
String renderRebuttalPrompt(String topic, String yourPrevious, String critiques);
String renderConvergencePrompt(String topic, String yourPosition, String othersPositions);

// CompressionService
String compress(List<DebateRound> history, int maxChars);
```

---

### #6 状态持久化

| 文件 | 职责 |
|------|------|
| `persistence/DebateStateStore.java` | 每轮结束序列化 DebateSession → JSON → 写入磁盘。支持反序列化恢复 |

**存储结构：**
```
~/.ai-debate-arena/sessions/{sessionId}/
├── round-1.json
├── round-2.json
├── round-3.json
└── ...
```

**方法：**
```java
void saveSnapshot(String sessionId, int roundNumber, DebateSession session);
DebateSession loadSnapshot(String sessionId, int roundNumber);
List<Integer> listSavedRounds(String sessionId);
void deleteSession(String sessionId);
```

---

## Phase 1-B: 浏览器自动化层

### #7 PlaywrightManager

| 文件 | 职责 |
|------|------|
| `browser/PlaywrightManager.java` | `@Component` 单例。`@PostConstruct` 创建 Playwright，`@PreDestroy` 销毁 |

**方法：**
```java
BrowserContext launchPersistentContext(AiPlatform platform, Path userDataDir);
void closeContext(BrowserContext context);
Playwright getPlaywright();  // 获取底层实例
```

**配置项：**
```java
BrowserType.LaunchPersistentContextOptions options = new ...
    .setHeadless(config.isHeadless())
    .setViewportSize(config.getViewportWidth(), config.getViewportHeight())
    .setSlowMo(config.getSlowMo())
    .setArgs(Arrays.asList(
        "--disable-blink-features=AutomationControlled",
        "--disable-features=DevToolsDebuggingRestrictions"
    ));
// 如果配置使用系统 Chrome
if ("chrome".equals(config.getChannel())) options.setChannel("chrome");
```

---

### #8 ProfileManager

| 文件 | 职责 |
|------|------|
| `browser/ProfileManager.java` | 管理 `profiles/{chatgpt,gemini,deepseek}` 目录 |

**方法：**
```java
Path getProfilePath(AiPlatform platform);          // 返回 profiles/chatgpt 等
boolean isProfileReady(AiPlatform platform);       // 目录存在且包含 Default/
LoginStatus checkLoginStatus(BrowserContext ctx, AiPlatform platform, SelectorProvider sp);
void runInteractiveSetup(Playwright pw, AiPlatform platform);  // 打开可见浏览器供登录
void deleteProfile(AiPlatform platform);
```

**LoginStatus 枚举：** `LOGGED_IN, LOGIN_REQUIRED, ERROR`

**checkLoginStatus 流程：**
```
1. context.newPage() → 导航到平台 URL
2. waitForLoadState(NETWORKIDLE)
3. 检测登录指示器选择器是否存在
4. close page → 返回状态
```
**关键约束：不发测试 Prompt，纯 DOM 检查。**

---

### #9 PlatformAdapter 接口 + FallbackSelector

#### PlatformAdapter 接口

```java
public interface PlatformAdapter {
    /** 辩论开始时调用一次，绑定 Page */
    void initialize(Page page);

    /** 发送 Prompt 并等待完整响应 */
    CompletableFuture<String> sendPrompt(String prompt);

    /** 点击"New Chat"按钮重置对话 */
    void resetConversation();

    /** 纯 DOM 健康检查（不发 Prompt） */
    HealthStatus healthCheck();

    /** 关闭适配器资源 */
    void close();

    /** 所属平台 */
    AiPlatform getPlatform();
}
```

#### FallbackSelector

```java
public class FallbackSelector {
    // 从 YAML 加载多级选择器链
    private final List<String> chains;  // [primary, secondary, xpath, aria-label]

    /** 依次尝试选择器，找到第一个可见且可交互的元素 */
    public Locator resolve(Page page);

    /** 返回 LocatorWaitOptions（超时配置） */
    public Locator.WaitForOptions waitOptions();
}
```

---

### #10 三平台适配器实现

#### ChatGPTAdapter

| 关注点 | 实现 |
|--------|------|
| 输入选择器 | `#prompt-textarea` (primary) → `div.ProseMirror[contenteditable]` (secondary) |
| 发送 | `button[data-testid='send-button']` → Enter key dispatch |
| 完成检测 | Stop Button `[data-testid='stop-button']` 消失 → 500ms 轮询 textContent 连续 2 次不变 |
| 提取响应 | `article[data-testid^='conversation-turn-']:last-of-type` → innerText |
| 对话重置 | 点击 New Chat 按钮 → 等待输入框重新出现 |
| 健康检查 | 导航到首页 → 检测 `[data-testid='profile-button']` + 输入框可见 |

#### GeminiAdapter

| 关注点 | 实现 |
|--------|------|
| 输入选择器 | `div.ql-editor[contenteditable='true']` (Quill 富文本) |
| 发送 | `button[aria-label='Send message']` |
| 完成检测 | Stop button `[aria-label='Stop']` 消失 → Copy button `[aria-label='Copy']` 出现 |
| 提取响应 | 动态 CSS 定位器（chronological order）提取最新响应气泡 |
| 对话重置 | `button[aria-label='New chat']` |
| 健康检查 | `div[data-test-id='side-nav']` 存在 |

#### DeepSeekAdapter

| 关注点 | 实现 |
|--------|------|
| 输入选择器 | `#chat-input` (最稳定的 textarea ID) |
| 发送 | `button[class*='send']` |
| 完成检测 | Stop button 消失 → 文本稳定性 |
| 提取响应 | `[class*='Message_bot']:last-of-type` |
| 对话重置 | `button[class*='new-chat']` |
| 健康检查 | `[class*='Sidebar']` 存在 |

**共同重试策略（RetryUtil）：**
```
操作失败 → 等待 1s → 重试 → 等待 2s → 重试 → 等待 4s → 失败则抛 BrowserAutomationException
```

---

## Phase 2-3: 辅助组件

### #11 ConversationManager

| 文件 | 职责 |
|------|------|
| `orchestrator/ConversationManager.java` | 管理各平台对话生命周期 |

**方法：**
```java
void startNewConversation(AiPlatform platform);    // 调用 adapter.resetConversation()
void endConversation(AiPlatform platform);         // 清理当前对话
boolean isConversationActive(AiPlatform platform); // 是否有活跃对话
void resetAll();                                   // 全部重置（新辩论开始前）
```

**职责：**
- 每轮开始前隔离上下文（避免上一轮对话污染）
- 防止上下文窗口溢出（配合 CompressionService）
- 维护每个平台当前对话状态

---

## Phase 3: 核心编排引擎

### #12 DebateOrchestrator 🔥

| 文件 | 职责 |
|------|------|
| `orchestrator/DebateOrchestrator.java` | 核心引擎。管理辩论全生命周期 |

**注入依赖：**
```java
@Component
public class DebateOrchestrator {
    @Resource private Map<AiPlatform, PlatformAdapter> adapters;  // Spring 自动注入
    @Resource private PlaywrightManager playwrightManager;
    @Resource private ProfileManager profileManager;
    @Resource private ConversationManager conversationManager;
    @Resource private ConvergenceDetector convergenceDetector;
    @Resource private PromptTemplateService promptService;
    @Resource private DebateStateStore stateStore;
    @Resource private SynthesisGenerator synthesisGenerator;
    @Resource private TaskExecutor taskExecutor;
    private final Cache<String, DebateSession> sessionCache =
        CacheBuilder.newBuilder().expireAfterWrite(1, TimeUnit.HOURS).build();
}
```

**主流程（startDebate）：**
```java
@Async
public void startDebate(DebateRequest request, String sessionId) {
    DebateSession session = initSession(request, sessionId);
    stateStore.saveSnapshot(sessionId, 0, session);

    // --- ROUND 1: 初始回答 ---
    session.setStatus(INITIAL_ANSWER);
    DebateRound round1 = executeRound(session, 1, RoundType.INITIAL);
    session.addRound(round1);
    stateStore.saveSnapshot(sessionId, 1, session);

    if (checkConvergence(session)) {
        finishDebate(session, CONVERGED);
        return;
    }

    // --- ROUND 2: 交叉批判 ---
    session.setStatus(CRITIQUE);
    DebateRound round2 = executeCritiqueRound(session, 2);
    session.addRound(round2);
    stateStore.saveSnapshot(sessionId, 2, session);

    if (checkConvergence(session)) {
        finishDebate(session, CONVERGED);
        return;
    }

    // --- ROUND 3+: 反驳循环 ---
    for (int r = 3; r <= session.getMaxRounds(); r++) {
        session.setStatus(REBUTTAL);
        DebateRound round = executeRebuttalRound(session, r);
        session.addRound(round);
        stateStore.saveSnapshot(sessionId, r, session);

        if (checkConvergence(session)) {
            finishDebate(session, CONVERGED);
            return;
        }

        // 硬时间限制检查
        if (elapsed(session) > MAX_DEBATE_TIME_MS) {
            finishDebate(session, MAX_ROUNDS);
            return;
        }
    }

    finishDebate(session, MAX_ROUNDS);
}
```

**单轮执行（executeRound）：**
```java
DebateRound executeRound(DebateSession session, int roundNum, RoundType type) {
    DebateRound round = new DebateRound(roundNum, type);

    // 并行调用三个平台适配器
    Map<AiPlatform, CompletableFuture<String>> futures = new EnumMap<>(AiPlatform.class);
    for (AiPlatform platform : AiPlatform.values()) {
        if (!session.isPlatformActive(platform)) continue;  // 跳过已失败的

        String prompt = buildPrompt(type, platform, session);
        futures.put(platform, adapters.get(platform).sendPrompt(prompt));
    }

    // 等待所有完成（带超时）
    CompletableFuture.allOf(futures.values().toArray(new CompletableFuture[0]))
        .get(session.getAiTimeoutSeconds(), TimeUnit.SECONDS);

    // 收集结果
    for (Map.Entry<AiPlatform, CompletableFuture<String>> entry : futures.entrySet()) {
        try {
            String response = entry.getValue().get();
            round.addResponse(entry.getKey(), new ParticipantResponse(entry.getKey(), response));
        } catch (Exception e) {
            log.error("Platform {} failed in round {}", entry.getKey(), roundNum, e);
            session.markPlatformFailed(entry.getKey());
        }
    }

    // 优雅降级检查
    if (session.getActivePlatformCount() < 2) {
        throw new DebateException("Insufficient platforms available (min 2 required)");
    }

    return round;
}
```

**Prompt 构建（buildPrompt）：**
```java
String buildPrompt(RoundType type, AiPlatform platform, DebateSession session) {
    return switch (type) {
        case INITIAL -> promptService.renderInitialPrompt(session.getTopic());
        case CRITIQUE -> {
            // 发给该平台另外两个 AI 的回答
            AiPlatform other1 = getOtherPlatforms(platform).get(0);
            AiPlatform other2 = getOtherPlatforms(platform).get(1);
            String resp1 = session.getLatestResponse(other1);
            String resp2 = session.getLatestResponse(other2);
            String myPrev = session.getLatestResponse(platform);
            yield promptService.renderCritiquePrompt(
                session.getTopic(), other1.name(), resp1, other2.name(), resp2, myPrev);
        }
        case REBUTTAL -> {
            String myPrev = session.getLatestResponse(platform);
            String critiques = session.getCritiquesAbout(platform);
            yield promptService.renderRebuttalPrompt(session.getTopic(), myPrev, critiques);
        }
        case CONVERGENCE -> {
            String myPos = session.getLatestResponse(platform);
            String others = session.getLatestResponsesExcept(platform);
            yield promptService.renderConvergencePrompt(session.getTopic(), myPos, others);
        }
    };
}
```

---

## Phase 4-5: 输出 + API

### #13 报告生成器

| 文件 | 职责 |
|------|------|
| `reporting/SynthesisGenerator.java` | 收集所有轮次 → 渲染 final-report.st → 输出 Markdown |
| `reporting/MarkdownRenderer.java` | Markdown 格式化、转义、表格生成、代码块处理 |

**SynthesisGenerator 关键逻辑：**
```java
public String generate(DebateSession session) {
    Map<String, Object> params = new HashMap<>();
    params.put("topic", session.getTopic());
    params.put("generated_at", LocalDateTime.now().toString());
    params.put("debate_status", session.getStatus().name());
    params.put("total_rounds", session.getRounds().size());
    params.put("participants", buildParticipantsString(session));
    params.put("debate_summary", buildSummary(session));
    params.put("initial_positions", buildInitialPositions(session));
    params.put("debate_rounds", buildRoundDetails(session));
    params.put("convergence_analysis", buildConvergenceAnalysis(session));
    params.put("final_consensus", buildConsensus(session));
    params.put("persistent_disagreements", buildDisagreements(session));
    params.put("synthesis_recommendation", buildRecommendation(session));
    params.put("decision_matrix", buildDecisionMatrix(session));
    params.put("implementation_advice", buildImplementationAdvice(session));
    params.put("risk_warnings", buildRiskWarnings(session));
    params.put("further_reading", buildFurtherReading(session));

    return promptTemplateService.render("final-report", params);
}
```

---

### #14 平台并发队列

| 文件 | 职责 |
|------|------|
| `orchestrator/PlatformQueueManager.java` | 每个平台独立 FIFO 队列，序列化同平台并发辩论 |

**设计：**
```java
@Component
public class PlatformQueueManager {
    // 每个平台一个单线程执行器，保证同平台消息不交叉
    private final Map<AiPlatform, ExecutorService> platformQueues = new EnumMap<>(AiPlatform.class);

    @PostConstruct
    void init() {
        for (AiPlatform p : AiPlatform.values()) {
            platformQueues.put(p, Executors.newSingleThreadExecutor());
        }
    }

    /** 在指定平台的队列中执行任务，返回 Future */
    <T> CompletableFuture<T> submit(AiPlatform platform, Callable<T> task) { ... }

    /** 获取指定平台的队列深度 */
    int getQueueDepth(AiPlatform platform) { ... }
}
```

---

### #15 REST API + 异常处理

#### REST 端点

| 方法 | 路径 | 请求体 | 响应 |
|------|------|--------|------|
| `POST` | `/api/debates` | `DebateRequest` | `{ sessionId, status, message }` |
| `GET` | `/api/debates/{id}` | — | `DebateStatusResponse` |
| `GET` | `/api/debates/{id}/report` | — | `text/markdown` |
| `POST` | `/api/debates/{id}/cancel` | — | `{ sessionId, status }` |
| `POST` | `/api/debates/{id}/resume` | — | `{ sessionId, status }` |
| `GET` | `/api/profiles` | — | `Map<platform, LoginStatus>` |
| `POST` | `/api/profiles/{platform}/setup` | — | `{ platform, status, message }` |
| `DELETE` | `/api/profiles/{platform}` | — | `204` |
| `GET` | `/api/profiles/{platform}/health` | — | `{ healthy, details }` |

#### 异常映射

| 异常 | HTTP | 错误码 |
|------|------|--------|
| `DebateSessionNotFoundException` | 404 | `SESSION_NOT_FOUND` |
| `LoginRequiredException` | 401 | `LOGIN_REQUIRED` |
| `BrowserAutomationException` | 502 | `BROWSER_ERROR` |
| `DebateAlreadyCompleteException` | 409 | `ALREADY_COMPLETE` |
| `InsufficientPlatformsException` | 503 | `INSUFFICIENT_PLATFORMS` |
| `IllegalArgumentException` | 400 | `BAD_REQUEST` |

---

## Phase 6: 验证

### #16 端到端测试

**测试场景：**

| # | 场景 | 输入 | 预期 |
|---|------|------|------|
| 1 | 单平台冒烟 | ChatGPT 单次 sendPrompt | 成功返回非空响应 |
| 2 | Profile 检测 | 三平台健康检查 | 返回 LOGGED_IN 或 LOGIN_REQUIRED |
| 3 | 最小辩论 | topic="Java vs Kotlin", maxRounds=2 | 2 轮完成，输出 Markdown 报告 |
| 4 | 单平台故障 | 关闭一个浏览器，启动辩论 | 标记 FAILED，其余 2 AI 继续 |
| 5 | 崩溃恢复 | 辩论中途 kill 进程，resume | 从最近快照恢复继续 |
| 6 | 选择器 fallback | 修改 YAML primary 为无效值 | 自动 fallback 到 secondary |
| 7 | 超时处理 | 设置 aiTimeout=5s | 超时后重试，3 次后标记 FAILED |
| 8 | 并发隔离 | 同时启动 2 个辩论 | 各平台消息不交叉 |

**调试模式验证：** `mvn spring-boot:run -Dspring-boot.run.profiles=debug`
- 浏览器窗口可视化
- slowMo=500ms 观察每步操作
- 日志级别 DEBUG

---

## 执行建议

### 推荐执行顺序

```
第 1 天:  #1 补 Lucene → #2 配置类 → #3 数据模型
第 2 天:  #4 收敛检测器 → #5 模板服务 → #6 状态持久化
第 3-4 天: #7 PlaywrightManager → #8 ProfileManager
第 5-6 天: #9 PlatformAdapter 接口 → #10 ChatGPTAdapter（首个验证）
第 7-8 天: #10 GeminiAdapter + DeepSeekAdapter
第 9 天:   #11 ConversationManager
第 10-12 天: #12 DebateOrchestrator 🔥 ← 最复杂，需充分调试
第 13-14 天: #13 报告生成器 + #14 平台队列
第 15-16 天: #15 REST API
第 17-19 天: #16 端到端测试 + 修复
第 20 天:   文档完善 + 收尾
```

### 每步验证方式

```bash
# 代码提交前
mvn clean compile     # 编译通过
mvn test              # 单元测试通过

# 调试模式手动验证浏览器自动化
mvn spring-boot:run -Dspring-boot.run.profiles=debug

# 启动后验证
curl http://localhost:8080/api/profiles          # 检查登录状态
curl -X POST http://localhost:8080/api/debates \  # 启动测试辩论
  -H "Content-Type: application/json" \
  -d '{"topic":"test","maxRounds":2}'
```

---

> 📝 基于 05-final-synthesis.md 共识方案生成。所有 Phase 已于 2026-06-15 完成。实施中如有偏差以代码实际运行结果为准。
