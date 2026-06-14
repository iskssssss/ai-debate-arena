# 1. Architecture Design

## Overall System Architecture

```text
+---------------------------------------------------------+
|                    Spring Boot API                      |
|---------------------------------------------------------|
| DebateController                                        |
| DebateQueryController                                   |
+--------------------------+------------------------------+
                           |
                           v
+---------------------------------------------------------+
|                   Debate Orchestrator                   |
|---------------------------------------------------------|
| DebateEngine                                             |
| RoundCoordinator                                         |
| ConsensusDetector                                        |
| DebateStateMachine                                       |
+--------------------------+------------------------------+
                           |
                           v
+---------------------------------------------------------+
|                    AI Agent Layer                       |
|---------------------------------------------------------|
| GeminiAgent                                              |
| ChatGPTAgent                                             |
| DeepSeekAgent                                             |
| (implements AiAgent)                                     |
+--------------------------+------------------------------+
                           |
                           v
+---------------------------------------------------------+
|                 Browser Automation Layer                |
|---------------------------------------------------------|
| PlaywrightManager                                        |
| BrowserProfileManager                                    |
| SessionHealthMonitor                                     |
| SelectorRegistry                                         |
+--------------------------+------------------------------+
                           |
                           v
+---------------------------------------------------------+
|                    Chromium Instances                   |
|---------------------------------------------------------|
| Gemini Persistent Context                                |
| ChatGPT Persistent Context                               |
| DeepSeek Persistent Context                              |
+---------------------------------------------------------+

                           |
                           v

+---------------------------------------------------------+
|                    Persistence Layer                    |
|---------------------------------------------------------|
| DebateRepository                                         |
| DebateRunStore                                           |
| MarkdownReportGenerator                                  |
+---------------------------------------------------------+
```

---

## Component Responsibilities

### DebateController

REST endpoints:

```http
POST /api/debates
GET  /api/debates/{id}
GET  /api/debates/{id}/report
```

Responsibilities:

* Create debate requests
* Return status
* Return final report

---

### DebateEngine

Central orchestration component.

Responsibilities:

* Initialize debate
* Manage rounds
* Invoke AI agents
* Handle failures
* Determine termination

Example:

```java
DebateResult runDebate(DebateRequest request);
```

---

### RoundCoordinator

Handles debate logic:

```text
Round 1
 └─ Initial Answers

Round 2
 └─ Critique Others

Round 3
 └─ Rebuttal

Round 4+
 └─ Convergence Check
```

---

### ConsensusDetector

Determines:

```text
Consensus reached?
Strong disagreement?
Need more rounds?
```

Provides score:

```java
ConsensusScore {
    double semanticSimilarity;
    double positionAlignment;
    boolean converged;
}
```

---

### AiAgent

Platform abstraction.

```java
public interface AiAgent {

    String getName();

    AiResponse ask(String prompt);

    AgentHealth health();

}
```

Implementations:

```java
GeminiAgent
ChatGptAgent
DeepSeekAgent
```

---

### PlaywrightManager

Owns Playwright lifecycle.

Responsibilities:

* launchPersistentContext
* page management
* recovery

---

### BrowserProfileManager

Manages:

```text
profiles/
 ├─ chatgpt/
 ├─ gemini/
 └─ deepseek/
```

Persistent sessions survive restarts.

---

## Data Flow

```text
Client
  |
  v

DebateController

  |
  v

DebateEngine

  |
  +---------------------+
  |                     |
  v                     v

RoundCoordinator   DebateStateMachine

  |
  v

AiAgent(s)

  |
  v

Playwright

  |
  v

Browser UI

  |
  v

Responses

  |
  v

ConsensusDetector

  |
  v

MarkdownReportGenerator

  |
  v

Stored Result
```

---

# 2. Project Structure

```text
ai-debate-arena
│
├── api
│   ├── DebateController
│   └── dto
│
├── debate
│   ├── DebateEngine
│   ├── DebateStateMachine
│   ├── RoundCoordinator
│   ├── ConsensusDetector
│   └── model
│
├── agent
│   ├── AiAgent
│   ├── AbstractPlaywrightAgent
│   ├── ChatGptAgent
│   ├── GeminiAgent
│   └── DeepSeekAgent
│
├── browser
│   ├── PlaywrightManager
│   ├── BrowserProfileManager
│   ├── SelectorRegistry
│   ├── BrowserRecoveryService
│   └── PageUtils
│
├── prompt
│   ├── DebatePromptFactory
│   └── templates
│
├── report
│   ├── MarkdownReportGenerator
│   └── DebateSummaryBuilder
│
├── persistence
│   ├── DebateRepository
│   └── DebateStore
│
├── config
│   └── ApplicationConfig
│
└── Application
```

---

## Core Domain Objects

### Debate

```java
public class Debate {

    UUID id;

    String question;

    DebateStatus status;

    List<RoundResult> rounds;

}
```

---

### RoundResult

```java
public class RoundResult {

    int roundNumber;

    List<AgentResponse> responses;

}
```

---

### AgentResponse

```java
public class AgentResponse {

    String agent;

    String prompt;

    String answer;

    Instant timestamp;

}
```

---

## pom.xml Highlights

```xml
<properties>
    <java.version>17</java.version>
    <spring.boot.version>3.2.5</spring.boot.version>
    <playwright.version>1.54.0</playwright.version>
</properties>
```

Dependencies:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
</dependency>

<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-core</artifactId>
</dependency>

<dependency>
    <groupId>com.microsoft.playwright</groupId>
    <artifactId>playwright</artifactId>
    <version>1.54.0</version>
</dependency>

<dependency>
    <groupId>org.commonmark</groupId>
    <artifactId>commonmark</artifactId>
</dependency>

<dependency>
    <groupId>org.apache.commons</groupId>
    <artifactId>commons-text</artifactId>
</dependency>
```

Recommended:

```xml
spring-boot-starter-validation
lombok
jackson-databind
caffeine
```

---

# 3. Core Implementation Strategy

## Browser Automation Design

Use Adapter Pattern.

```java
public interface AiAgent {

    AiResponse ask(String prompt);

}
```

Base implementation:

```java
public abstract class AbstractPlaywrightAgent
        implements AiAgent {

    protected BrowserContext context;
    protected Page page;

    protected abstract String getUrl();

    protected abstract Locator inputBox();

    protected abstract Locator submitButton();

    protected abstract String extractResponse();
}
```

---

### ChatGPT Adapter

Typical flow:

```java
page.navigate("https://chatgpt.com");

input.fill(prompt);

input.press("Enter");

waitUntilStreamingStops();

extractLatestAssistantMessage();
```

---

### Gemini Adapter

```java
page.navigate("https://gemini.google.com");

fillPrompt();

submit();

waitForResponse();

extractAnswer();
```

---

### DeepSeek Adapter

```java
page.navigate("https://chat.deepseek.com");

fillPrompt();

submit();

extractAnswer();
```

---

## Selector Registry

Avoid hardcoding selectors.

```java
public interface SelectorProvider {

    String inputSelector();

    String responseSelector();

    String stopButtonSelector();

}
```

Config:

```yaml
selectors:
  chatgpt:
    input: ...
    response: ...

  gemini:
    input: ...
```

Benefits:

* hot-fix UI changes
* no recompilation

---

## Persistent Browser Profiles

Critical requirement.

Directory:

```text
profiles/
 ├── chatgpt/
 ├── gemini/
 └── deepseek/
```

Launch:

```java
BrowserType.LaunchPersistentContextOptions opts =
    new BrowserType.LaunchPersistentContextOptions();

playwright.chromium()
    .launchPersistentContext(
        profilePath,
        opts
    );
```

First run:

```text
1. Browser opens
2. User logs in manually
3. Session stored
4. Future runs reuse cookies
```

---

## Debate Workflow

Use explicit state machine.

```text
CREATED

↓

ROUND_1

↓

ROUND_2

↓

REBUTTAL

↓

CONVERGENCE_CHECK

↓

COMPLETED
```

Model:

```java
enum DebatePhase {

    INITIAL_ANSWER,
    CRITIQUE,
    REBUTTAL,
    CONSENSUS,
    COMPLETED

}
```

---

## Round Logic

### Round 1

Prompt:

```text
Answer the following question:

<Question>

Provide reasoning.
```

---

### Round 2

Prompt:

```text
You are reviewing two other AI answers.

Identify:
1. Errors
2. Missing considerations
3. Strong points
```

---

### Round 3

Prompt:

```text
Review critiques against your answer.

Revise your position.

State:
- what you accept
- what you reject
- why
```

---

### Round 4+

Prompt:

```text
Compare all positions.

Can consensus be reached?

Output:
AGREE
PARTIAL_AGREE
DISAGREE
```

---

## Convergence Detection

Avoid pure string matching.

Use hybrid scoring.

### Level 1

Cosine Similarity

```text
TF-IDF
```

Libraries:

```text
Apache Commons Math
Smile
```

---

### Level 2

Jaccard Similarity

```text
keyword overlap
```

---

### Level 3

Position Extraction

Prompt AI:

```text
Summarize final position in 5 bullets.
```

Compare bullets.

---

### Consensus Formula

```java
score =
  0.5 * semanticSimilarity +
  0.3 * keywordSimilarity +
  0.2 * positionAgreement;
```

Example:

```text
>= 0.85

=> consensus reached
```

---

# 4. Risk Analysis

## Risk #1: UI Changes

Highest risk.

Examples:

```text
DOM changes
CSS changes
Button changes
Shadow DOM
```

Mitigation:

### Selector fallback chain

```java
primary selector
secondary selector
xpath fallback
aria-label fallback
```

---

### Contract tests

Nightly verification:

```java
chatgpt smoke test
gemini smoke test
deepseek smoke test
```

Detect breakages early.

---

## Risk #2: Streaming Response Detection

AI platforms stream text.

Naive extraction causes truncation.

Solution:

```java
waitUntilTextStable()
```

Algorithm:

```text
Capture content length

No growth for 3-5 seconds

=> complete
```

---

## Risk #3: Rate Limiting

Platforms may throttle.

Mitigation:

```text
request pacing
randomized delays
backoff retry
queueing
```

Never fire simultaneous rapid prompts.

Use staggered execution:

```text
ChatGPT
+1 sec Gemini
+2 sec DeepSeek
```

---

## Risk #4: Bot Detection

Mitigation:

Persistent real browser.

```text
Headed mode preferred
Real user profile
No API abuse
Human login
```

Avoid:

```text
headless-only
aggressive parallelism
browser fingerprint manipulation
```

---

## Risk #5: Mid-Debate Failure

Example:

```text
Gemini unavailable
```

Engine behavior:

```text
Mark agent FAILED

Continue with remaining agents

Consensus based on survivors
```

Report:

```markdown
## Failure Summary

Gemini unavailable during Round 3
```

---

## Risk #6: Infinite Debate Loops

Hard limits:

```java
MAX_ROUNDS = 6;
```

and

```java
MAX_DURATION = 20 minutes;
```

---

# 5. Implementation Roadmap

## Phase 1 — Browser Foundation

Complexity: Medium

Build:

```text
PlaywrightManager
ProfileManager
ChatGPT automation
```

Deliverable:

```text
Ask ChatGPT via browser
Return response
```

---

## Phase 2 — Multi-Agent Layer

Complexity: Medium

Build:

```text
GeminiAgent
DeepSeekAgent
AiAgent abstraction
```

Deliverable:

```text
Three platforms responding
```

---

## Phase 3 — Debate Engine

Complexity: High

Build:

```text
RoundCoordinator
StateMachine
PromptFactory
```

Deliverable:

```text
Full multi-round debate
```

---

## Phase 4 — Consensus System

Complexity: Medium-High

Build:

```text
Similarity scoring
Position extraction
Consensus detection
```

Deliverable:

```text
Automatic convergence
```

---

## Phase 5 — REST API

Complexity: Low

Build:

```text
POST /debates
GET /debates/{id}
GET /report
```

Deliverable:

```text
External control
```

---

## Phase 6 — Markdown Reporting

Complexity: Low

Generate:

```markdown
# Debate Report

Question

## Round 1
ChatGPT
Gemini
DeepSeek

## Round 2
Critiques

## Round 3
Rebuttals

## Consensus Analysis

Similarity Score: 0.89

Final Consensus

...
```

---

## Phase 7 — Reliability Hardening

Complexity: High

Build:

```text
Selector fallback system
Retries
Health checks
Recovery
Session monitoring
```

Deliverable:

```text
Production-grade local tool
```

---

# Recommended Final Architecture

如果目标是“长期稳定运行”，我会采用：

```text
Spring Boot
    +
Debate State Machine
    +
Agent Adapter Layer
    +
Playwright Persistent Contexts
    +
Externalized Selector Registry
    +
Consensus Engine
    +
Markdown Report Generator
```

其中最关键的设计决策有三个：

1. **Agent Adapter Pattern** —— 隔离各平台 UI 差异。
2. **Persistent Chromium Profiles** —— 避免重复登录，显著提升可用性。
3. **显式状态机(State Machine)** —— 避免多轮辩论逻辑失控，便于恢复、重试和扩展到更多 AI 平台。
