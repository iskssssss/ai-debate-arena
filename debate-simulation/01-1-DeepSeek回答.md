## 1. Architecture Design

### Overall System Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              CLI / REST Client                              │
└─────────────────────────────────────────────────────────────────────────────┘
                                       │
                                       ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                           Spring Boot REST API                              │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌───────────────────┐  │
│  │ DebateController│ │ ReportController│ │ HealthController│ │ DebateStateStore│  │
│  └─────────────┘  └─────────────┘  └─────────────┘  └───────────────────┘  │
└─────────────────────────────────────────────────────────────────────────────┘
                                       │
                                       ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                          DebateOrchestrator                                 │
│  ┌──────────────────────────────────────────────────────────────────────┐  │
│  │  State Machine (Round → Critique → Rebuttal → Convergence Check)    │  │
│  └──────────────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────────────┘
          │                    │                    │
          ▼                    ▼                    ▼
┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐
│  GeminiAdapter  │  │  ChatGPTAdapter │  │ DeepSeekAdapter │
│  (Playwright)   │  │  (Playwright)   │  │  (Playwright)   │
└─────────────────┘  └─────────────────┘  └─────────────────┘
          │                    │                    │
          └────────────────────┼────────────────────┘
                               ▼
                    ┌─────────────────────┐
                    │   BrowserManager    │
                    │ ┌─────────────────┐ │
                    │ │Persistent Context││
                    │ │ (Chromium profiles)││
                    │ └─────────────────┘ │
                    └─────────────────────┘
```

### Component Responsibilities

| Component | Responsibility |
|-----------|----------------|
| **DebateController** | REST endpoints: `POST /debate/start`, `GET /debate/{id}/status`, `GET /debate/{id}/report` |
| **DebateOrchestrator** | Implements the multi‑round debate state machine. Coordinates adapters, checks convergence, handles timeouts/retries. |
| **PlatformAdapter (interface)** | Uniform abstraction for sending prompts and reading responses from a specific AI web UI. Each adapter handles login, selectors, and message extraction. |
| **BrowserManager** | Singleton managing Playwright `Browser` instances. Provides persistent `BrowserContext` per AI platform (isolated user data directories). |
| **DebateStateStore** | In‑memory (or optionally file‑based) store for ongoing debate state: round history, answers, critiques, convergence score. |
| **PromptTemplateService** | Uses Spring AI’s `PromptTemplate` to render system instructions for critique and rebuttal phases (no API calls). |
| **ConvergenceDetector** | Computes similarity between the three agents’ latest answers using TF‑IDF + cosine similarity or Jaccard overlap. |
| **ReportGenerator** | Builds structured Markdown report from stored debate transcripts. |

### Data Flow

1. **Start Debate** → Client sends `POST /debate/start` with technical question.
2. **Round 1 (Initial Answers)** → Orchestrator asks each adapter (in parallel) to answer the question. Each adapter navigates to the AI’s chat UI, types the prompt, waits for the response, and returns the text.
3. **Round 2 (Critiques)** → Orchestrator, for each AI, constructs a critique prompt (using template) that includes the other two AIs’ answers. Adapters submit that prompt and capture critiques.
4. **Round 3+ (Rebuttals)** → Orchestrator feeds each AI its own previous answer plus the critiques received, prompting for rebuttal. After each rebuttal round, convergence detector runs.
5. **Convergence Check** → Compute pairwise similarity between the three latest answers. If all pairwise similarities > threshold (e.g., 0.85) → consensus reached. Else if max rounds reached → stop.
6. **Report Generation** → On completion, orchestration triggers `ReportGenerator` to write Markdown to disk and store the report path in the state.

## 2. Project Structure

### Package Layout

```
com.aidebatearena
├── AiDebateArenaApplication.java          # Spring Boot main
├── config
│   ├── PlaywrightConfig.java               # Bean for BrowserManager
│   └── AsyncConfig.java                    # For parallel adapter calls
├── api
│   ├── DebateController.java
│   ├── ReportController.java
│   └── dto
│       ├── StartDebateRequest.java
│       └── DebateStatusResponse.java
├── orchestration
│   ├── DebateOrchestrator.java
│   ├── DebateState.java                    # Mutable state for a debate
│   ├── DebateStateStore.java
│   └── RoundType.java                      # Enum: INITIAL, CRITIQUE, REBUTTAL
├── browser
│   ├── BrowserManager.java
│   ├── BrowserProfile.java                 # Wrapper around Playwright Context
│   └── CredentialsProvider.java            # Reads from secure config (not code)
├── adapters
│   ├── PlatformAdapter.java                # Interface
│   ├── GeminiAdapter.java
│   ├── ChatGPTAdapter.java
│   ├── DeepSeekAdapter.java
│   └── common
│       ├── SelectorConfig.java             # Locators loaded from properties
│       ├── WaitStrategy.java
│       └── ResponseCaptor.java             # DOM observation helpers
├── prompts
│   ├── PromptTemplateService.java          # Uses Spring AI PromptTemplate
│   └── templates
│       ├── critique-prompt.st
│       └── rebuttal-prompt.st
├── convergence
│   ├── ConvergenceDetector.java
│   └── TextSimilarityCalculator.java       # TF-IDF + cosine
├── reporting
│   ├── ReportGenerator.java
│   └── MarkdownFormatter.java
└── util
    ├── FileUtils.java
    └── RetryUtils.java
```

### Key Classes and Interfaces

```java
public interface PlatformAdapter {
    CompletableFuture<String> sendPrompt(String prompt, String debateId);
    void resetConversation();   // Clear chat context (new thread)
    void close();
}

public class DebateOrchestrator {
    private final Map<String, PlatformAdapter> adapters;
    private final DebateStateStore stateStore;
    private final ConvergenceDetector convergenceDetector;
    private final PromptTemplateService promptService;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public String startDebate(String question, int maxRounds) { ... }
    private CompletableFuture<Map<String,String>> roundInitial(String question) { ... }
    private void roundCritiques(String debateId, Map<String,String> previousAnswers) { ... }
    private boolean roundRebuttalsAndCheckConvergence(String debateId, int roundNum) { ... }
}
```

### `pom.xml` Highlights

```xml
<properties>
    <java.version>17</java.version>
    <spring-boot.version>3.2.5</spring-boot.version>
    <playwright.version>1.54.0</playwright.version>
</properties>

<dependencies>
    <!-- Spring Boot -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>
    <!-- Spring AI (Prompt Templates only) -->
    <dependency>
        <groupId>org.springframework.ai</groupId>
        <artifactId>spring-ai-core</artifactId>
        <version>1.0.0-SNAPSHOT</version>  <!-- or stable 0.8.x -->
    </dependency>
    <!-- Playwright -->
    <dependency>
        <groupId>com.microsoft.playwright</groupId>
        <artifactId>playwright</artifactId>
        <version>1.54.0</version>
    </dependency>
    <!-- NLP for similarity (Apache OpenNLP or simple Lucene) -->
    <dependency>
        <groupId>org.apache.lucene</groupId>
        <artifactId>lucene-analyzers-common</artifactId>
        <version>9.10.0</version>
    </dependency>
    <!-- Utilities -->
    <dependency>
        <groupId>org.projectlombok</groupId>
        <artifactId>lombok</artifactId>
        <optional>true</optional>
    </dependency>
</dependencies>
```

## 3. Core Implementation Strategy

### Browser Automation per Platform (Adapter Pattern)

Each adapter implements `PlatformAdapter` and is responsible for:

- **Login & session persistence** – Uses `BrowserManager` to obtain a `BrowserContext` bound to a unique user data directory for that platform (e.g., `./browser-profiles/gemini`). The first time, the user must log in manually; subsequent runs reuse the authenticated session.
- **Selector configuration** – Store CSS/XPath selectors in `application-{platform}.properties`. Use a `SelectorConfig` class that reloads on change (allows hot‑fixing UI updates without code change).
- **Message sending & response capture**:
    - Locate textarea / input field → `fill()` → press `Enter`.
    - Wait for “Stop generating” button to disappear or a dynamic response container to stabilise.
    - Use `page.waitForFunction()` with a JavaScript predicate that detects when the “thinking” or “typing” indicator is gone and the final answer content has a stable length for >1 second.
- **Error recovery** – On timeout or stale element, retry up to 3 times with exponential backoff. If persistent failure, mark the adapter as dead for this debate and log a placeholder error message.

**Example snippet inside `GeminiAdapter`**:

```java
public CompletableFuture<String> sendPrompt(String prompt, String debateId) {
    return CompletableFuture.supplyAsync(() -> {
        Page page = context.newPage();
        try {
            page.navigate("https://gemini.google.com");
            page.locator(selectorConfig.getTextarea()).fill(prompt);
            page.locator(selectorConfig.getSendButton()).click();
            // Wait for response
            page.waitForFunction(
                "() => document.querySelector('" + selectorConfig.getResponseContainer() + "')?.innerText?.length > 100",
                new Page.WaitForFunctionOptions().setTimeout(60000)
            );
            return page.locator(selectorConfig.getResponseContainer()).innerText();
        } catch (Exception e) {
            log.error("Gemini failed", e);
            return "[Error: Gemini unavailable]";
        } finally {
            page.close();
        }
    }, executor);
}
```

### Managing Persistent Browser Profiles

`BrowserManager` uses Playwright’s `BrowserType.launchPersistentContext(userDataDir, options)`. Each platform gets its own context.

```java
public class BrowserManager {
    private final Map<Platform, BrowserContext> contexts = new ConcurrentHashMap<>();
    private final Playwright playwright = Playwright.create();

    public BrowserContext getContext(Platform platform) {
        return contexts.computeIfAbsent(platform, p -> {
            Path userDataDir = Paths.get("./browser-profiles", p.name().toLowerCase());
            return playwright.chromium().launchPersistentContext(userDataDir,
                new BrowserType.LaunchPersistentContextOptions()
                    .setHeadless(false)   // visible for debugging; can be true after stability
                    .setViewportSize(1280, 720)
            );
        });
    }
}
```

- **Headless vs headed** – Use `headless=false` initially to allow manual login on first run. For production (or after login), `headless=true` works because sessions are persisted.
- **Security** – Store credentials nowhere; the browser profile saves cookies/localStorage.

### Debate Orchestration (State Machine)

The `DebateOrchestrator` implements a non‑blocking state machine using `CompletableFuture` chaining. Rounds are executed sequentially, but within a round the three adapters run in parallel.

**Pseudo‑workflow**:

1. `startDebate()` creates a `DebateState` with `status = RUNNING`.
2. `roundInitial()` → `CompletableFuture.allOf(adapter1.sendPrompt(...), ...)`.
3. After initial answers, store them and call `roundCritiques()`.
4. `roundCritiques()`: for each adapter A, build prompt: *“Here are the answers from B and C. Provide a critique of their answers, pointing out flaws and missing points.”* → send to A. Store critiques.
5. `roundRebuttals()`: for each adapter A, build prompt: *“Your previous answer was … The critiques you received are … Provide a rebuttal or revised answer.”* → send to A.
6. After rebuttals, call `convergenceDetector.isConverged(answers)`. If true, set `status = COMPLETED`. Else if `roundNum < maxRounds`, loop to step 5.
7. On max rounds reached, set `status = MAX_ROUNDS_REACHED`.

### Convergence Detection (No API Keys)

Use lightweight TF‑IDF + cosine similarity:

- Build a corpus from the three current answers.
- Compute term frequency vectors (using Lucene’s `EnglishAnalyzer` for stopword removal and stemming).
- For each pair of answers, compute cosine similarity.
- Define convergence when `min(pairwise similarities) > 0.85`.

**Alternative simpler** (less accurate but zero‑dependencies): Jaccard similarity on word sets after lowercasing and removing punctuation. Threshold 0.7 may work for technical debates.

Implementation class `TextSimilarityCalculator` with a pluggable strategy (default = TF‑IDF).

## 4. Risk Analysis

### Biggest Technical Risks

| Risk | Impact | Mitigation |
|------|--------|-------------|
| **UI changes break selectors** | High | Load selectors from external config; implement a “selector health check” endpoint that tries to locate elements and reports failures. Provide quick property override. |
| **Bot detection / CAPTCHAs** | High | Use persistent profiles (reduces suspicion), add random delays between actions (`Thread.sleep(200-800)`), rotate user agents, and run in headed mode with `--disable-blink-features=AutomationControlled`. If blocked, fallback to notifying the user to manually complete CAPTCHA once. |
| **Platform rate limiting** | Medium | Implement per‑adapter rate limiter (e.g., 5 requests/minute). If `429` or “Too many requests” detected, exponentially backoff and retry. Stagger debate rounds by 2‑3 seconds. |
| **Adapter failure mid‑debate** | Medium | Each adapter call is wrapped in a retry with fallback (return error placeholder). Debate can continue with partial responses; final report indicates which AI failed. Orchestrator marks that adapter as `DEGRADED` and avoids further prompts to it. |
| **Long‑running debates block API** | Low | Use `@Async` with virtual threads (Java 21+, but Java 17 with platform threads works). Expose `GET /debate/{id}/status` for polling. Provide cancellation endpoint. |
| **Concurrent debates interfering with each other** | Medium | Each debate gets its own `Page` instance from the shared persistent context, but Playwright’s context can handle multiple pages concurrently. However, prompts interleave – acceptable. To isolate, create a new incognito context per debate (but loses re‑login benefit). Better: serialize debates per platform with a queue. |

### Handling AI Platform UI Changes

- **Externalized selectors** in `application.properties`:
  ```properties
  gemini.textarea=textarea[aria-label="Message Gemini"]
  gemini.sendButton=button[aria-label="Send"]
  gemini.responseContainer=.markdown-body
  ```
- **Versioned profiles** – When updating selectors, keep old versions and allow runtime switching.
- **Automated selector detector** – A utility that records DOM structure periodically and alerts on mismatch (could be a separate CLI tool).

### Bot Detection Mitigations

- Use **undetected‑chromium** tactics via Playwright’s `launch` arguments:
  ```java
  .setArgs(Arrays.asList("--disable-blink-features=AutomationControlled",
                         "--disable-features=ChromeWhatsNewUI",
                         "--no-sandbox"))
  ```
- Randomise viewport, timezone, and geolocation.
- Use human‑like typing: `page.locator(textarea).type(prompt, new Locator.TypeOptions().setDelay(50))`.

### Platform Failure Mid‑Debate

- **Timeouts** – Set 60s per prompt. If exceeded, return `[Timeout – no response]`.
- **Health checks** – Before starting a debate, ping each platform with a simple “Hello” to verify login and response. If a platform fails, abort with clear error.
- **Partial reports** – Still generate Markdown, marking unavailable participants.

## 5. Implementation Roadmap

### Phase 0 – Foundation (Complexity: Low, 2 days)

- Set up Spring Boot 3.2.5 + Maven project.
- Add Playwright dependency and write a smoke test that launches Chromium.
- Implement `BrowserManager` with persistent profiles (manual login verification).
- Create `PlatformAdapter` interface and a dummy adapter that echoes back.

**Deliverable**: Application starts, Playwright can open browser and save profile.

### Phase 1 – Single Platform Automation (Complexity: Medium, 4 days)

- Implement `GeminiAdapter` with selectors, response capture, wait strategies.
- Repeat for `ChatGPTAdapter` (chatgpt.com) and `DeepSeekAdapter`.
- Write integration test for each adapter sending “What is 2+2?” and capturing answer.
- Add retry logic and error fallbacks.

**Deliverable**: Three working adapters that can individually converse with the AIs.

### Phase 2 – Debate Orchestration Core (Complexity: High, 5 days)

- Implement `DebateState` and `DebateStateStore`.
- Create `PromptTemplateService` with Spring AI templates for critique/rebuttal.
- Build `DebateOrchestrator` with sequential rounds but parallel adapter calls.
- Add convergence detector (start with Jaccard, refine to TF‑IDF).
- Test with a fixed question and 3 rounds; manually verify logs.

**Deliverable**: Full debate flow runs from CLI (without REST API).

### Phase 3 – REST API & Reporting (Complexity: Medium, 3 days)

- Add `DebateController` (start, status, cancel, list).
- Implement `ReportGenerator` that writes a Markdown timeline.
- Expose `GET /debate/{id}/report` to download.
- Add asynchronous debate execution using `@Async` or `CompletableFuture`.

**Deliverable**: API‑driven debates with Markdown report output.

### Phase 4 – Hardening & Observability (Complexity: Medium, 3 days)

- Add health checks and metric logging (Micrometer).
- Implement selector reloading without restart.
- Add per‑platform rate limiting and request staggering.
- Write end‑to‑end tests using Testcontainers + mock HTTP server (for UI simulation).
- Create a simple CLI runner (Spring Boot `CommandLineRunner`) for demo.

**Deliverable**: Production‑ready local service.

### Phase 5 – Documentation & Packaging (Complexity: Low, 1 day)

- Write `README.md` with setup instructions (manual login, run).
- Provide a `run.sh` / `.bat` script.
- Package as a fat JAR.

**Deliverable**: Final deliverable with sample report.

---

### Estimated Total Effort: 18 developer days

**Critical path**: Phase 1 (adapters) → Phase 2 (orchestration) → Phase 3 (API). Begin with a single adapter to prove browser automation works, then parallelise.

**Potential accelerators**: Use existing open‑source selectors (many projects have ChatGPT selectors) and share user data profiles. For convergence, start with a simple similarity threshold and improve later.

**Testing strategy**: Each phase must include unit tests (mocked Playwright) and integration tests with real browsers but only on trusted local network to avoid aggressive bot detection.