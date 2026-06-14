Here is a comprehensive, production-grade architectural blueprint and implementation plan for the `ai-debate-arena` system.

---

## 1. Architecture Design

The system follows a **Hexagonal (Ports and Adapters) Architecture** combined with a coordinated **State Machine Workflow**. This decouples the core orchestration logic from the volatile UI layer of the target AI platforms.

### System Architecture Diagram

```
+-----------------------------------------------------------------------------------+
|                                 SPRING BOOT APP                                   |
|                                                                                   |
|  +---------------------+        +------------------------+                        |
|  |  DebateRestController| -----> |    DebateOrchestrator  |                        |
|  +---------------------+        | (State Machine / Loop)  |                        |
|                                 +------------------------+                        |
|                                        |          |                               |
|                     +------------------+          +-----------------+             |
|                     |                                               |             |
|                     v                                               v             |
|          +--------------------+                           +--------------------+  |
|          |  AIAgentInterface  |                           | ConvergenceEngine  |  |
|          +--------------------+                           +--------------------+  |
|                     |                                               |             |
|      +--------------+--------------+                                |             |
|      |              |              |                                v             |
|      v              v              v                      +--------------------+  |
| +----------+  +----------+  +------------+                |  ReportGenerator   |  |
| |  Gemini  |  | ChatGPT  |  |  DeepSeek  |                +--------------------+  |
| | Adapter  |  | Adapter  |  |  Adapter   |                          |             |
| +----------+  +----------+  +------------+                          |             |
|      |              |              |                                |             |
+------|--------------|--------------|--------------------------------|-------------+
       |              |              |                                |
       v              v              v                                v
+--------------------------------------------------+        +--------------------+
|                PLAYWRIGHT CORE                   |        |   LOCAL STORAGE    |
|  [Profile 1]     [Profile 2]     [Profile 3]     |        |                    |
|  (Gemini)        (ChatGPT)       (DeepSeek)      |        |  - Markdown Report |
|      |               |               |           |        |  - State JSON      |
|      v               v               v           |        +--------------------+
|  Chromium        Chromium        Chromium        |
| (gemini.google) (chatgpt.com)  (chat.deepseek)   |
+--------------------------------------------------+

```

### Component Responsibilities and Interactions

* **`DebateRestController`**: Exposes HTTP endpoints to trigger a debate request and query execution status asynchronously.
* **`DebateOrchestrator`**: The workflow brain. It tracks state transitions, coordinates data exchange between agents per round, uses Spring AI templates to build cross-critique prompts, and decides when to stop.
* **`AIAgentAdapter` (Interface)**: Defines an abstract contract for browser interaction (`initSession`, `submitPrompt`, `waitForResponse`, `extractLatestResponse`).
* **Concrete Adapters (`GeminiAdapter`, `ChatGPTAdapter`, `DeepSeekAdapter`)**: Contain platform-specific CSS/XPath selectors and custom wait conditions.
* **`PlaywrightBrowserManager`**: Manages the lifecycles of separate, distinct Chromium `BrowserContext` instances using dedicated system data paths to preserve login cookies.
* **`ConvergenceEngine`**: Analyzes round-over-round response dynamics using local text similarity matrices to gauge if the agents are reaching a consensus.
* **`ReportGenerator`**: Consumes the in-memory transcript object graph and materializes a structured Markdown document.

### Data Flow Through the System

```
[User POST Request]
       │
       ▼
[Orchestrator: Create Debate State]
       │
 ┌─────┴─────────────────────────┐
 │ ROUND 1: Technical Prompt     │
 │ 1. System builds base prompt using Spring AI Template.
 │ 2. Parallel execution: Adapters submit prompt via Playwright.
 │ 3. Adapters scrape response; update Debate State.
 └─────┬─────────────────────────┘
       │
 ┌─────▼─────────────────────────┐
 │ ROUND 2: Cross-Critique        │
 │ 1. System generates critique prompt containing R1 answers.
 │ 2. Adapters feed inputs to respective ongoing browser sessions.
 │ 3. Responses collected and aggregated.
 └─────┬─────────────────────────┘
       │
 ┌─────▼─────────────────────────┐
 │ ROUND 3+: Rebuttal Loop       │
 │ 1. Submit counter-critiques.
 │ 2. Run ConvergenceEngine check.
 │ 3. IF (Converged OR MaxRounds) ──> BREAK
 └─────┬─────────────────────────┘
       │
       ▼
[ReportGenerator: Build Markdown] ──> [Save to Local Storage]

```

---

## 2. Project Structure

### Package Layout

```
src/main/java/com/arena/aidebate
├── ArenaApplication.java
├── config
│   ├── PlaywrightConfig.java       # Configuration for browser directories and options
│   └── AppConfig.java              # Spring AI and general configuration
├── controller
│   ├── dto
│   │   ├── DebateRequest.java      # Input payload
│   │   └── DebateStatusResponse.java # Output polling payload
│   └── DebateRestController.java   # HTTP Endpoints
├── model
│   ├── DebateContext.java          # Thread-safe object capturing full execution history
│   ├── DebateRound.java            # Captures responses/critiques for a single round
│   ├── AgentResponse.java          # Individual model's text output and metadata
│   └── DebateState.java            # Enum (INITIALIZED, R1_PENDING, R2_PENDING, etc.)
├── orchestrator
│   ├── DebateEngine.java           # Central thread managing workflow loops
│   └── ConvergenceEngine.java      # Similarity checking logic
├── service
│   ├── PlaywrightBrowserManager.java # Handles launchPersistentContext mechanics
│   └── ReportGenerator.java        # Formats output down to markdown text
└── adapter
    ├── AIAgentAdapter.java         # High level interface
    ├── impl
    │   ├── GeminiAdapter.java      # Selector mappings for gemini.google.com
    │   ├── ChatGPTAdapter.java     # Selector mappings for chatgpt.com
    │   └── DeepSeekAdapter.java    # Selector mappings for chat.deepseek.com
    └── util
        └── SelectorConfig.java     # Loads selector overrides from application.yml

```

### Key Classes and Interfaces

#### `AIAgentAdapter.java`

```java
package com.arena.aidebate.adapter;

import com.microsoft.playwright.Page;

public interface AIAgentAdapter {
    String getAgentId();
    void initializeSession(Page page);
    String sendPromptAndExtractResponse(String prompt);
}

```

#### `DebateEngine.java`

```java
package com.arena.aidebate.orchestrator;

import com.arena.aidebate.model.DebateContext;
import org.springframework.stereotype.Service;
import java.util.concurrent.CompletableFuture;

@Service
public class DebateEngine {
    public CompletableFuture<DebateContext> runDebate(String initialQuestion, int maxRounds) {
        // Workflow management logic goes here
    }
}

```

### Dependency Management (`pom.xml` Highlights)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.2.5</version>
        <relativePath/>
    </parent>

    <groupId>com.arena</groupId>
    <artifactId>ai-debate-arena</artifactId>
    <version>1.0.0</version>
    <name>ai-debate-arena</name>

    <properties>
        <java.version>17</java.version>
        <playwright.version>1.54.0</playwright.version>
        <spring-ai.version>0.8.1</spring-ai.version>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>

        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-core</artifactId>
            <version>${spring-ai.version}</version>
        </dependency>

        <dependency>
            <groupId>com.microsoft.playwright</groupId>
            <artifactId>playwright</artifactId>
            <version>${playwright.version}</version>
        </dependency>

        <dependency>
            <groupId>org.apache.commons</groupId>
            <artifactId>commons-text</artifactId>
            <version>1.11.0</version>
        </dependency>

        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>
    </dependencies>
</project>

```

---

## 3. Core Implementation Strategy

### Browser Automation and the Adapter Pattern

Each web platform relies on structural patterns to manage prompt inputs and streamed text outputs. The Adapter pattern hides this logic from the orchestrator.

A deterministic trick to detect when an LLM finishes its output stream inside a browser window is to watch the interaction state of the **Send/Stop Button**. When generating text, the "Send" button morphs into a "Stop" button (or transitions to a disabled state).

#### Concrete Selector Configurations (`application.yml` layout):

```yaml
arena:
  adapters:
    chatgpt:
      url: "https://chatgpt.com"
      input-selector: "#prompt-textarea"
      send-button-selector: "button[data-testid='send-button']"
      stop-button-selector: "button[data-testid='stop-button']" # Monitor presence/absence
      response-container-selector: "div[data-message-author-role='assistant'] .markdown"
    gemini:
      url: "https://gemini.google.com"
      input-selector: "div[role='textbox'][aria-label='Input']"
      send-button-selector: "button[aria-label='Send message']"
      stop-button-selector: "button[aria-label='Stop generation']"
      response-container-selector: "message-content .model-response-text"
    deepseek:
      url: "https://chat.deepseek.com"
      input-selector: "#chat-input"
      send-button-selector: "div[class*='sendButton']"
      stop-button-selector: "div[class*='stopButton']"
      response-container-selector: ".ds-markdown"

```

#### Automation Strategy implementation snippet:

```java
public String sendPromptAndExtractResponse(Page page, String prompt, PlatformConfig config) {
    // 1. Target input element and fill it
    page.locator(config.getInputSelector()).fill(prompt);

    // 2. Click send button
    page.locator(config.getSendButtonSelector()).click();

    // 3. Monitor state: wait for stop button to clear, or send button to be re-enabled
    page.locator(config.getStopButtonSelector()).waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
    page.locator(config.getStopButtonSelector()).waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.HIDDEN));

    // 4. Extract the last matched element text content
    var responses = page.locator(config.getResponseContainerSelector()).allTextContents();
    return responses.get(responses.size() - 1); // Last block represents the current response
}

```

### Managing Persistent Browser Profiles

To circumvent standard authentication workflows and CAPTCHAs, we run individual browser contexts pointing to dedicated folders on disk via `launchPersistentContext`.

```java
package com.arena.aidebate.service;

import com.microsoft.playwright.*;
import org.springframework.stereotype.Service;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

@Service
public class PlaywrightBrowserManager {
    private Playwright playwright;
    private final Map<String, BrowserContext> activeContexts = new HashMap<>();

    public BrowserContext getOrCreateContext(String agentName) {
        if (playwright == null) {
            playwright = Playwright.create();
        }
        return activeContexts.computeIfAbsent(agentName, name -> {
            String profilePath = "./user-data-dir/" + name;
            BrowserType.LaunchOptions options = new BrowserType.LaunchOptions()
                    .setHeadless(false) // Required for manual session authentication first
                    .setArgs(java.util.List.of("--disable-blink-features=AutomationControlled"));

            return playwright.chromium().launchPersistentContext(Paths.get(profilePath), options);
        });
    }
}

```

> **Operational Note:** During the first system startup, users launch the application with headless mode disabled (`setHeadless(false)`), allowing them to manually authenticate with Google, OpenAI, and DeepSeek accounts. Sessions persist in the local `./user-data-dir/*` profiles for subsequent automated headless runs.

### Orchestrating Debate Rounds (State Machine Workflow)

We enforce sequential multi-threaded control flow through standard thread execution frameworks (`CompletableFuture`).

```java
public void executeWorkflow(DebateContext context) {
    // Round 1: Generate answers to user query
    context.setState(DebateState.ROUND_1_ACTIVE);
    runParallelPlatformStep(context, adapter -> {
        String prompt = context.getInitialQuestion();
        return adapter.sendPromptAndExtractResponse(prompt);
    });

    // Round 2: Critique generation
    context.setState(DebateState.ROUND_2_ACTIVE);
    runParallelPlatformStep(context, adapter -> {
        // Use Spring AI PromptTemplate to stitch context from R1
        String multiCritiquePrompt = templateEngine.buildCritiquePrompt(adapter.getAgentId(), context);
        return adapter.sendPromptAndExtractResponse(multiCritiquePrompt);
    });

    // Round 3+: Rebuttals and Convergence Loop
    int currentRound = 3;
    while (currentRound <= context.getMaxRounds()) {
        context.setCurrentRoundNumber(currentRound);
        context.setState(DebateState.REBUTTAL_ACTIVE);

        runParallelPlatformStep(context, adapter -> {
            String rebuttalPrompt = templateEngine.buildRebuttalPrompt(adapter.getAgentId(), context);
            return adapter.sendPromptAndExtractResponse(rebuttalPrompt);
        });

        if (convergenceEngine.evaluateConsensus(context)) {
            context.setState(DebateState.CONVERGED);
            break;
        }
        currentRound++;
    }

    if (context.getState() != DebateState.CONVERGED) {
        context.setState(DebateState.MAX_ROUNDS_REACHED);
    }
}

```

### Convergence Detection

Since direct APIs are banned under system constraints, the system computes structural alignment locally on the server via text similarity metrics. We utilize Cosine Similarity mapped against standard n-gram tokenization provided by `commons-text`.

Given two vector representations of text strings $A$ and $B$, convergence is calculated using:

$$\text{Cosine Similarity} = \frac{A \cdot B}{\|A\| \|B\|}$$

```java
package com.arena.aidebate.orchestrator;

import com.arena.aidebate.model.DebateContext;
import org.apache.commons.text.similarity.CosineSimilarity;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
public class ConvergenceEngine {
    private final CosineSimilarity cosineSimilarity = new CosineSimilarity();

    public boolean evaluateConsensus(DebateContext context) {
        var currentRoundData = context.getLatestRoundData();

        String txt1 = currentRoundData.getChatGPTResponse();
        String txt2 = currentRoundData.getGeminiResponse();
        String txt3 = currentRoundData.getDeepSeekResponse();

        // Convert strings into localized term frequency maps
        Map<CharSequence, Integer> textMap1 = transformToTermFrequency(txt1);
        Map<CharSequence, Integer> textMap2 = transformToTermFrequency(txt2);
        Map<CharSequence, Integer> textMap3 = transformToTermFrequency(txt3);

        double simA = cosineSimilarity.cosineSimilarity(textMap1, textMap2);
        double simB = cosineSimilarity.cosineSimilarity(textMap2, textMap3);
        double simC = cosineSimilarity.cosineSimilarity(textMap1, textMap3);

        double averageSimilarity = (simA + simB + simC) / 3.0;

        // Threshold check (e.g., if average similarity scores top 0.88, models have agreed structural paths)
        return averageSimilarity > 0.88;
    }

    private Map<CharSequence, Integer> transformToTermFrequency(String text) {
        return Stream.of(text.toLowerCase().split("\\W+"))
                .filter(word -> word.length() > 2)
                .collect(Collectors.toMap(w -> w, w -> 1, Integer::sum));
    }
}

```

---

## 4. Risk Analysis

| Risk Metric | Technical Impact | Structural Mitigation Strategy |
| --- | --- | --- |
| **Volatile Frontend Changes** (Selectors breaking) | High. Playwright throws `TimeoutException`, stalling execution loops. | Implement a dynamic mapping mechanism. Decouple selectors from application code entirely by exposing them through external dynamic Spring `@ConfigurationProperties` (`SelectorConfig`). This allows system operators to update query selectors inside an external configuration file without compiling a new JAR layout. |
| **Bot Detection / CAPTCHAs** | High. Cloudflare / PerimeterX challenges completely block page navigation. | 1. Use `launchPersistentContext` to preserve authenticated browser sessions.<br>

<br>2. Pass the custom initialization flag `--disable-blink-features=AutomationControlled` to strip out the standard `navigator.webdriver` footprint.<br>

<br>3. Introduce randomized pacing delays via standard jitter math models across keystrokes and element actions. |
| **Rate-Limiting Limits** (Platform specific) | Medium. UI displays "Too many requests" banner. | Build explicit error parsing flags into the extraction loop. If specific error indicators are triggered, catch the event block, sleep the active execution execution context for up to 120 seconds, and retry. |
| **Mid-Debate Browser Failures** | Critical. Memory crashes or OS drops disconnect the running thread. | Enforce persistent data logging. The `DebateContext` model structure must be flushed to disk via JSON serialized format snapshots at the end of *every individual round*. If a thread fails, the operator can call a specific state restoration endpoint to restart execution from the last valid round. |

---

## 5. Implementation Roadmap

### Phase 1: Infrastructure and Profile Initialization

* **Focus**: Build basic Spring structure, configure Maven POM layouts, initialize Playwright file storage systems.
* **Key Deliverable**: A basic executable script utility that boots Playwright Chromium with explicit persistent profile routes, allowing operators to manually log in to target platforms.
* **Estimated Complexity**: Low

### Phase 2: Web Scraping Adapter Hardening

* **Focus**: Implement specialized platform abstractions (`GeminiAdapter`, `ChatGPTAdapter`, `DeepSeekAdapter`).
* **Key Deliverable**: Execution units that read raw text inputs, find the appropriate DOM structures, input messages, track the state of the generation container, and extract the complete text blocks reliably.
* **Estimated Complexity**: High

### Phase 3: Core Loop Engine Development

* **Focus**: Build `DebateEngine` utilizing multi-threaded capabilities to coordinate state management.
* **Key Deliverable**: Orchestration engine that seamlessly processes parallel round sequences, formats prompts via Spring AI utility templates, and feeds them between browser modules.
* **Estimated Complexity**: Medium

### Phase 4: Statistical Analytics and API Layers

* **Focus**: Implement `ConvergenceEngine` local calculations and build the asynchronous Spring REST endpoints.
* **Key Deliverable**: Exposing working API controls `/api/debates/start` and status checking parameters while monitoring data similarities via underlying vector computations.
* **Estimated Complexity**: Medium

### Phase 5: Markdown Exporter and Validation

* **Focus**: Build `ReportGenerator` to assemble the collected responses into clean Markdown layouts. Run edge-case failure tests to ensure resilience.
* **Key Deliverable**: End-to-end integration proving the local platform can execute multiple rounds of collaborative cross-critiques completely headless and export clean documentation.
* **Estimated Complexity**: Low