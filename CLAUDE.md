# AI Debate Arena（方案研讨台）

Multi-AI debate engine — Three AI platforms (ChatGPT, Gemini, DeepSeek) debate a topic through Playwright browser automation, with optional judge summarization and output document generation.

## Tech Stack

| Technology | Usage |
|-----------|-------|
| Spring Boot 3.2.5, Java 17, Maven | Backend framework & build |
| Playwright 1.54.0 (Chromium) | Browser automation with persistent profiles |
| Vue 3 (esm-browser.prod) | Frontend — vanilla JS components, no build step |
| Hand-written CSS | No framework; ~2K lines in `app.css` |
| marked.js + DOMPurify | Markdown rendering & XSS sanitization |
| Apache Lucene 9.10 | TF-IDF text similarity for convergence detection |
| FastJSON2, SnakeYAML 2.2 | Session snapshot serialization, selector YAML parsing |
| Electron | Desktop wrapper — auto-launches Spring Boot JAR |

## Architecture

```
┌──────────────────────────────────────────────────────┐
│  Electron (app-menu.js) — desktop wrapper            │
├──────────────────────────────────────────────────────┤
│  Browser (index.html)                                │
│  Vue 3 SPA ── components/ ── composables/           │
├──────────────────────────────────────────────────────┤
│  Spring Boot REST API                                │
│  ┌──────────┐  ┌──────────┐  ┌───────────────────┐  │
│  │Controller│→ │Orchestrator│→ │ PlatformAdapter  │  │
│  │  Layer   │  │ (State    │  │ (3 browser auto-  │  │
│  │          │  │  Machine) │  │  mation adapters) │  │
│  └──────────┘  └─────┬────┘  └───────────────────┘  │
│                      │                               │
│         ┌────────────┼────────────┐                  │
│         ▼            ▼            ▼                  │
│    JudgeService  PromptBuilder  ConvergenceDetector  │
│    (API/Channel) (Spring AI    (Apache Lucene)       │
│                   templates)                         │
├──────────────────────────────────────────────────────┤
│  Persistence                                         │
│  ~/.ai-debate-arena/                                 │
│    ├── sessions/{id}/round-{n}.json  (state snapshots)│
│    ├── profiles/                     (browser cookies)│
│    ├── channels.json                 (channel configs)│
│    └── api-config.json               (judge API key) │
└──────────────────────────────────────────────────────┘
```

## Debate State Machine

```
INITIAL_ANSWER → CRITIQUE → REBUTTAL ⇄ (convergence check)
                  ↑_______________↓    ↙
             未收敛继续循环            CONVERGED / MAX_ROUNDS / FAILED
```

- **Round 1 (INITIAL)**: Each AI submits its initial solution to the topic
- **Round 2 (CRITIQUE)**: Each AI critiques every other AI's initial response
- **Round 3+ (REBUTTAL)**: Each AI rebuts critiques against itself; convergence is checked after each round
- **Hard limits**: max 6 rounds + 20 minutes total; minimum 2 platforms required

## Key Domain Models

| Model | Purpose |
|-------|---------|
| `DebateSession` | Full debate state — topic, rounds, status, judge config, output docs |
| `DebateRound` | Single round — responses, prompts, critiques, judge record, convergence result |
| `ParticipantResponse` | One AI's response in a round — raw text, extracted content, metadata |
| `ChannelDefinition` | A debate channel — can be BROWSER (built-in) or API (custom), with keys stored locally |
| `ChannelRegistry` | Collection of all channel definitions |
| `ChannelType` | Enum: `BROWSER` or `API` |
| `AiPlatform` | Enum: `CHATGPT`, `GEMINI`, `DEEPSEEK` |
| `JudgeMode` | Enum: `API` (DeepSeek HTTP) or `CHANNEL` (browser-based judge) |
| `RoundType` | Enum: `INITIAL`, `CRITIQUE`, `REBUTTAL`, `CONVERGENCE` |
| `DebateStatus` | Enum: `CREATED` → `RUNNING` → … → `CONVERGED` / `MAX_ROUNDS` / `FAILED` |

## Backend Package Map

```
com.debatearena
├── DebateArenaApplication.java       # Spring Boot entry point
├── adapter/                          # Platform-specific browser automation
│   ├── PlatformAdapter.java          # Interface: initialize, sendPrompt, resetConversation, healthCheck, close
│   ├── ChatGPTAdapter.java           # ChatGPT browser automation
│   ├── GeminiAdapter.java            # Gemini browser automation
│   ├── DeepSeekAdapter.java          # DeepSeek browser automation
│   ├── PromptSubmitter.java          # Types prompt text into the AI input box
│   ├── ResponseCompletionWaiter.java # Waits for AI response to finish streaming
│   ├── ResponseClipboardExtractor.java # Extracts response via clipboard API
│   ├── ResponseContentValidator.java # Validates response quality
│   ├── FallbackSelector.java         # Handles degraded platform states
│   └── BrowserAutomationException.java
├── browser/                          # Playwright lifecycle
│   ├── PlaywrightManager.java        # Per-platform Playwright + BrowserContext + ShutdownHook
│   ├── BrowserProcessCleaner.java    # Kills orphaned browser processes
│   ├── ProfileManager.java           # Persistent browser profiles (cookies/login)
│   ├── HealthStatus.java / LoginStatus.java
│   └── YamlSelectorProvider.java     # Reads DOM selectors from YAML configs
├── controller/                       # REST endpoints
│   ├── DebateController.java         # /api/debates — start, status, history, cancel, judge reports
│   ├── ChannelController.java        # /api/profiles/channels — list, update, add, delete, test channels
│   ├── ProfileController.java        # /api/profiles — login status, logout
│   ├── ThirdPartyApiSettingsController.java # /api/profiles/api-config — judge API config
│   ├── GlobalExceptionHandler.java
│   └── dto/                          # Request/Response DTOs
├── judge/                            # Judge & output document services
│   ├── JudgeService.java             # Interface
│   ├── ApiJudgeService.java          # DeepSeek API judge (round summaries)
│   ├── ChannelJudgeService.java      # Browser-based channel judge
│   ├── DeepSeekApiClient.java        # DeepSeek HTTP API client
│   ├── OutputDocumentService.java    # Generates post-debate documents
│   └── DocumentContentSanitizer.java # Cleans AI response artifacts
├── model/                            # Domain objects (see Key Domain Models above)
├── orchestrator/                     # Core debate engine
│   ├── DebateOrchestrator.java       # State machine engine, round lifecycle
│   ├── ConversationManager.java      # Manages per-platform conversation context
│   └── PlatformQueueManager.java     # Manages per-platform task queues
├── persistence/                      # JSON file-based storage
│   ├── DebateStateStore.java         # Round snapshots → ~/.ai-debate-arena/sessions/
│   ├── ChannelRegistryStore.java     # Channel configs → channels.json
│   └── ThirdPartyApiSettingsStore.java # Judge API settings → api-config.json
├── prompts/                          # Prompt engineering
│   ├── DebatePromptBuilder.java      # Builds round-specific prompts from templates
│   ├── PromptTemplateService.java    # Loads & caches prompt templates
│   └── CompressionService.java       # Compresses conversation history for context window
├── convergence/                      # Convergence detection
│   ├── ConvergenceDetector.java      # Interface
│   └── TextSimilarityConvergenceDetector.java # TF-IDF cosine similarity (Apache Lucene)
├── service/                          # Business services
│   ├── ChannelRegistryService.java   # Channel CRUD, login status, readiness check
│   ├── ThirdPartyApiSettingsService.java # Judge API settings CRUD, connectivity test
│   ├── ApiParticipantService.java    # API-based participant (custom channels)
│   ├── ParticipantSelectionHelper.java # Selects participants for each round
│   └── DebateProgressBuilder.java    # Builds progress step DTOs for frontend
├── config/                           # Spring configuration
│   ├── DebateConfig.java             # @ConfigurationProperties("debate")
│   ├── BrowserConfig.java            # @ConfigurationProperties("browser")
│   ├── JudgeConfig.java              # @ConfigurationProperties("judge")
│   ├── AiPlatformProperties.java     # @ConfigurationProperties("ai-platforms")
│   ├── AsyncConfig.java              # Thread pool for async debate execution
│   └── StartupConfigLogger.java      # Logs effective config at startup
└── reporting/                        # Output generation
    ├── SynthesisGenerator.java       # Generates final synthesis
    └── MarkdownRenderer.java         # Renders output as Markdown
```

## Frontend Architecture (Vue 3 SPA)

Entry: `index.html` → `main.js` → `App.js` (root component)

```
static/
├── index.html                  # Single HTML shell with <div id="app">
├── css/app.css                 # All application styles (~2K lines)
├── js/
│   ├── main.js                 # createApp(App).mount('#app')
│   ├── vue.js                  # Re-exports Vue 3 API from esm-browser build
│   ├── App.js                  # Root component: sidebar nav + KeepAlive panels
│   ├── config.js               # API URLs, localStorage keys, constants, PAGE_META
│   ├── utils.js                # Shared utility functions
│   ├── components/
│   │   ├── ProfilesPanel.js    # Channel configuration page (login, rename, API mode, add custom)
│   │   ├── DebateForm.js       # New debate form with topic, rounds, judge settings, output docs
│   │   ├── DebateDetail.js     # Live debate progress / detail view (rounds, judge reports)
│   │   ├── HistoryPanel.js     # Past debates list with search & delete
│   │   ├── BubbleModal.js      # Reusable content modal (Markdown rendered)
│   │   ├── ChatBubble.js       # Individual chat message bubble
│   │   ├── OverviewStep.js     # Progress step indicator in detail view
│   │   └── ToastContainer.js   # Global toast notification layer
│   └── composables/
│       ├── useToast.js         # Toast notification state & showToast()
│       ├── usePageState.js     # Page state persistence (tab, scroll, section)
│       ├── useDocumentLayout.js # Document reading layout helpers
│       ├── useMarkdown.js      # Markdown rendering (marked + DOMPurify)
│       ├── useMarkdownToc.js   # Table-of-contents extraction from markdown
│       └── useDebounce.js      # Debounce utility
└── vendor/
    ├── marked.min.js           # Markdown parser
    ├── purify.min.js           # DOMPurify XSS sanitizer
    └── vue.esm-browser.prod.js # Vue 3 production build (ES module)
```

### Vue Component Tree
```
App
├── ToastContainer (always mounted)
├── ProfilesPanel (KeepAlive, lazy)
├── DebateForm (KeepAlive, lazy)
├── HistoryPanel (KeepAlive, lazy)
└── BubbleModal (conditional — shows full debate round/message detail)
```

### Key Frontend Patterns
- **No build step**: Vue 3 is loaded directly in the browser via ES modules
- **KeepAlive**: Panel components preserve state when switching tabs
- **Page state**: `usePageState` persists tab/scroll/section to sessionStorage
- **Toast system**: Global reactive toast queue in `useToast.js`
- **Channel readiness**: Frontend polls `/api/profiles/channels` to check if ≥2 channels are ready before entering debate form

## REST API Summary

### Debate

| Method | Path | Purpose |
|--------|------|---------|
| `POST` | `/api/debates` | Start new debate |
| `GET` | `/api/debates` | List past debate sessions (`?limit=30`) |
| `GET` | `/api/debates/output-document-types` | List all output document types |
| `GET` | `/api/debates/{id}` | Get debate status & progress steps |
| `GET` | `/api/debates/{id}/documents` | List generated output documents |
| `GET` | `/api/debates/{id}/documents/{typeId}` | Download specific output document |
| `GET` | `/api/debates/{id}/report` | Legacy report (prefers full impl plan) |
| `GET` | `/api/debates/{id}/judge` | Get judge round materials |
| `POST` | `/api/debates/{id}/cancel` | Cancel running debate |
| `POST` | `/api/debates/{id}/resume` | Resume from snapshot |
| `POST` | `/api/debates/{id}/judge/rounds/{n}/retry` | Retry round judge summarization |

### Channels & Profiles

| Method | Path | Purpose |
|--------|------|---------|
| `GET` | `/api/profiles/channels` | List all channels with readiness |
| `PUT` | `/api/profiles/channels/{id}` | Update channel config |
| `POST` | `/api/profiles/channels` | Add custom API channel |
| `DELETE` | `/api/profiles/channels/{id}` | Delete custom channel |
| `POST` | `/api/profiles/channels/{id}/test` | Test API channel connectivity |
| `GET` | `/api/profiles` | Get all platform profile statuses |
| `POST` | `/api/profiles/{platform}/setup` | Open browser for interactive login |
| `GET` | `/api/profiles/{platform}/health` | Platform health check |
| `DELETE` | `/api/profiles/{platform}` | Clear platform profile |

### Judge API Config

| Method | Path | Purpose |
|--------|------|---------|
| `GET` | `/api/profiles/api-config` | Get judge API settings (key masked) |
| `PUT` | `/api/profiles/api-config` | Save judge API settings |
| `POST` | `/api/profiles/api-config/test` | Test API connectivity |
| `DELETE` | `/api/profiles/api-config` | Clear settings |

## Configuration

### application.yml (key settings)
- `debate.max-rounds`: 5 (hard limit 6)
- `debate.convergence-threshold`: 0.75 (cosine similarity)
- `debate.ai-timeout-seconds`: 120 per interaction
- `debate.parallel-execution`: true (all platforms called concurrently each round)
- `browser.headless`: true (set false for debugging)
- `browser.channel`: chromium (or chrome for system install)
- `ai-platforms.platforms.<id>.enabled`: true/false per platform

### application-desktop.yml
- Enables `headless: false` for visible browser debugging
- Sets `debate.parallel-execution: false` for sequential mode

## Running the Project

```bash
# Backend (Spring Boot)
mvn spring-boot:run

# With debug profile (visible browsers, sequential execution)
mvn spring-boot:run -Dspring-boot.run.profiles=debug

# Desktop mode
mvn spring-boot:run -Dspring-boot.run.profiles=desktop

# Electron (after building JAR)
cd electron && npm start
```

## Build

```bash
mvn clean package -DskipTests
# JAR: target/ai-debate-arena-1.0.0-SNAPSHOT.jar
```

## Key Design Decisions & Gotchas

### Browser Automation
- **Each AI platform gets its own Playwright instance + persistent BrowserContext** to avoid `Object doesn't exist: worker@...` errors from shared connections
- **Judge browsers are isolated from participant browsers** — each debate session gets its own judge browser resources
- **Adapter pattern**: All Playwright DOM operations are strictly inside adapter implementations; the orchestrator never touches the DOM directly
- **JVM ShutdownHook**: Registered in `PlaywrightManager` to clean up browser processes on forced exit
- **`BrowserProcessCleaner`**: Kills orphaned chromium/chrome processes that Playwright might leave behind

### State Persistence
- After each round, the full `DebateSession` is serialized to JSON at `~/.ai-debate-arena/sessions/{id}/round-{n}.json`
- This enables crash recovery: on restart, the debate can resume from the last completed round
- **API keys are never persisted** — stored in memory only (`ConcurrentHashMap`), cleared on debate end

### Channel System (New)
- Built-in channels (chatgpt, deepseek, gemini) default to `BROWSER` type but can be switched to `API`
- Custom API channels get `api-{uuid}` IDs, max 8 custom channels
- API channels must pass a connectivity test before participating in debates
- Channel configs persist to `~/.ai-debate-arena/channels.json`

### Judge/Organizer
- Two modes: `API` (DeepSeek HTTP API, default) and `CHANNEL` (browser-based channel)
- Judge API key is stored separately via `ThirdPartyApiSettingsStore` at `~/.ai-debate-arena/api-config.json`
- Round judge prompts are loaded from classpath templates and include conversation context

### Frontend
- No npm/webpack/build step — Vue 3 ES module build is vendored directly
- Components use Vue 3 Composition API via `defineComponent` + `setup()`
- `KeepAlive` preserves component state across tab switches (no data re-fetching)
- DOMPurify sanitizes all AI-generated content before rendering as HTML

### Convergence Detection
- Uses Apache Lucene TF-IDF + cosine similarity
- After each rebuttal round, compares the current round's responses to the previous round's
- If similarity ≥ threshold (default 0.75), the debate converges and stops

### Thread Safety
- `DebateOrchestrator` runs debates asynchronously (`@Async`) in a dedicated thread pool
- `ConcurrentHashMap` used for shared state (session API keys, failed platforms)
- Per-platform task queues in `PlatformQueueManager` prevent concurrent browser operations on the same platform
- Virtual threads are disabled (`spring.threads.virtual.enabled: false`) because Playwright needs platform threads
