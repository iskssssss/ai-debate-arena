# 方案研讨台（AI Debate Arena）

**输入需求 → 多方研讨方案 → 输出可落地的技术文档**

将业务或技术需求提交给多个 AI 讨论方，经多轮结构化研讨后，由整理服务归纳材料并生成可供开发团队查阅、评审和落地的 Markdown 文档。

> 更新：2026-06-15

---

## 核心价值

| 阶段 | 说明 |
|------|------|
| **输入** | 需求描述（功能目标、边界、约束、验收标准） |
| **研讨** | 讨论方甲/乙/丙独立出方案 → 交叉审阅 → 修订回应 → 收敛确认 |
| **整理** | 每轮结束后整理服务客观归纳各方材料（支持 API 或浏览器通道） |
| **输出** | 按勾选类型生成多份完整独立的 Markdown 产出文档 |

研讨过程中**不使用模型名称**，各方以「讨论方甲」「讨论方乙」「讨论方丙」匿名参与，避免立场偏见。整理服务仅客观摘录归纳，不添加整理者本人的意见或建议。

---

## 架构概览

```
┌──────────────────────────────────────────────────────────────┐
│  Vue 3 Web UI（static/js）/ Electron 桌面客户端               │
│  新建研讨 | 通道配置 | 研讨历史（含详情 master-detail）        │
└───────────────────────────┬──────────────────────────────────┘
                            │ REST API
┌───────────────────────────▼──────────────────────────────────┐
│                   Debate Orchestrator                         │
│  初始方案 → 交叉审阅 → 修订回应 → 收敛确认（循环至收敛/上限） │
│         ↓ 每轮结束                                           │
│  整理服务（API / 浏览器通道）+ JSON 快照持久化                │
│         ↓ 研讨结束                                           │
│  OutputDocumentService 逐项生成产出文档                       │
└───────────────────────────┬──────────────────────────────────┘
                            │
┌───────────────────────────▼──────────────────────────────────┐
│        ChannelRegistry + Browser / API 双模式接入             │
│  内置：ChatGPT / DeepSeek / Gemini（浏览器 Profile）          │
│  自定义：OpenAI 兼容 API 通道（须检测通过方可参与）           │
│  Playwright 独立实例 + 启动时残留进程清理                     │
└──────────────────────────────────────────────────────────────┘
```

---

## 快速开始

### 前置条件

| 依赖 | 说明 |
|------|------|
| JDK 17+ | 运行后端服务 |
| Maven 3.6+ | 项目构建 |
| Node.js 18+ | 仅桌面客户端开发/打包需要 |
| Chromium | Playwright 自动下载 |
| DeepSeek API Key | 整理服务必填（API 模式），可在通道配置页保存全局配置 |

### ① 安装 Playwright 浏览器

```powershell
cd ai-debate-arena
mvn exec:java -e -D exec.mainClass=com.microsoft.playwright.CLI -D exec.args="install chromium"
```

### ② 启动服务

```powershell
# 调试模式：端口 8080、可视浏览器、串行执行、详细日志
mvn spring-boot:run -Dspring-boot.run.profiles=debug
```

浏览器访问：**http://localhost:8080**

### ③ 桌面客户端（Electron，可选）

桌面客户端自动启动本机 Spring Boot 后端，在客户端窗口中加载研讨台页面。

```powershell
npm install
npm run desktop:dev      # 开发模式（会先构建后端 jar）
npm run desktop:build    # 打包安装包
```

说明：
- 客户端检测并使用系统 **Java 17+** 运行后端
- 可通过环境变量 `MAVEN_CMD` 指定 Maven 命令
- 打包产物输出到 `dist/`
- Playwright 登录与自动化使用**独立浏览器窗口**，不嵌入 Electron WebView
- 桌面菜单快捷键：`Ctrl+1` 新建研讨、`Ctrl+2` 通道配置、`Ctrl+3` 研讨历史

### ④ 配置通道（首次使用）

在「通道配置」页管理讨论方通道：

| 类型 | 说明 |
|------|------|
| **内置通道** | ChatGPT / DeepSeek / Gemini，固定为浏览器登录 |
| **自定义 API 通道** | 支持 OpenAI 兼容 API，须「检测连接」成功（`apiVerified`）方可参与 |
| **整理服务 API** | 可在通道配置页保存全局 DeepSeek API 配置 |

内置通道操作流程：点击「登录」→ 在独立浏览器窗口完成认证 → 点击「检测」确认状态。

```powershell
# 也可通过 API 触发登录
curl -X POST http://localhost:8080/api/profiles/chatgpt/setup \
  -H "Content-Type: application/json" \
  -d "{\"timeoutSeconds\": 300}"
```

本机数据目录：

| 路径 | 内容 |
|------|------|
| `~/.ai-debate-arena/profiles/` | 浏览器 Cookie / Profile |
| `~/.ai-debate-arena/channels.json` | 通道注册表 |
| `~/.ai-debate-arena/api-config.json` | 整理服务 API 配置 |
| `~/.ai-debate-arena/sessions/` | 研讨快照与产出文档 |

> **至少需要 2 个通道就绪**（浏览器已登录或 API 已验证）才能发起研讨；未满足时页面自动跳转至通道配置。

### ⑤ 发起研讨

**Web UI（三步向导）**：

1. **研讨描述** — 填写需求描述（功能目标、范围、验收标准）
2. **研讨参数** — 轮数、收敛阈值（50%~100%）、整理方式（API / 通道）、产出文档勾选
3. **参与讨论方** — 勾选就绪通道、自定义讨论方名称（甲/乙/丙…）
4. 点击「提交研讨」→ 自动跳转「研讨历史」并打开详情

**API 示例**：

```powershell
curl -X POST http://localhost:8080/api/debates `
  -H "Content-Type: application/json" `
  -d '{
    "topic": "为电商系统实现订单超时自动取消：支持分布式部署、可配置超时、库存回滚与消息通知",
    "maxRounds": 4,
    "convergenceThreshold": 0.75,
    "judgeEnabled": true,
    "judgeMode": "API",
    "judgeApiKey": "sk-xxx",
    "judgeModel": "deepseek-v4-flash",
    "participantChannelIds": ["chatgpt", "deepseek", "gemini"],
    "participantAliases": {
      "chatgpt": "讨论方甲",
      "deepseek": "讨论方乙",
      "gemini": "讨论方丙"
    },
    "outputDocuments": [
      "implementation_plan_full",
      "prd_acceptance",
      "mvp_plan",
      "test_plan",
      "disagreement_tradeoff"
    ]
  }'
```

### ⑥ 查看结果

```powershell
# 查询状态与进度步骤
curl http://localhost:8080/api/debates/{sessionId}

# 产出文档列表及生成状态
curl http://localhost:8080/api/debates/{sessionId}/documents

# 下载指定产出文档（Markdown）
curl http://localhost:8080/api/debates/{sessionId}/documents/implementation_plan_full

# 兼容旧接口：优先返回「推荐实现方案（完整版）」
curl http://localhost:8080/api/debates/{sessionId}/report

# 轮次整理材料（各轮提示词 / 回答 / 整理结果）
curl http://localhost:8080/api/debates/{sessionId}/judge
```

Web UI「研讨历史」提供左侧列表 + 右侧详情（master-detail），支持进度时间线、轮次材料、产出文档 Markdown 预览与下载。进行中研讨自动轮询刷新。

---

## REST API 参考

### 研讨

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/api/debates` | 发起研讨 |
| `GET` | `/api/debates` | 研讨历史列表（`?limit=30`） |
| `GET` | `/api/debates/output-document-types` | 全部可勾选产出文档类型 |
| `GET` | `/api/debates/{sessionId}` | 查询状态与进度步骤 |
| `GET` | `/api/debates/{sessionId}/documents` | 产出文档列表及状态 |
| `GET` | `/api/debates/{sessionId}/documents/{typeId}` | 下载指定产出文档 |
| `GET` | `/api/debates/{sessionId}/report` | 兼容报告（优先返回完整版方案） |
| `GET` | `/api/debates/{sessionId}/judge` | 轮次整理材料 |
| `POST` | `/api/debates/{sessionId}/cancel` | 终止研讨 |
| `POST` | `/api/debates/{sessionId}/resume` | 恢复快照 |
| `POST` | `/api/debates/{sessionId}/judge/rounds/{roundNumber}/retry` | 重试指定轮次整理 |

**请求体字段**（`DebateRequest`）：

| 字段 | 类型 | 必填 | 说明 |
|------|------|:----:|------|
| `topic` | string | ✅ | 需求描述 |
| `maxRounds` | int | — | 最大轮数，默认 5，范围 2~10 |
| `convergenceThreshold` | double | — | 收敛阈值 0.5~1.0，默认 0.75（UI 以百分比表示） |
| `judgeEnabled` | boolean | — | 是否启用整理，默认 `true` |
| `judgeMode` | string | — | `API`（DeepSeek HTTP）或 `CHANNEL`（浏览器通道整理） |
| `judgeApiKey` | string | API 模式 | 整理服务 API Key，仅存内存，不落盘 |
| `judgeModel` | string | — | 整理模型，默认 `deepseek-v4-flash` |
| `judgeChannel` | string | CHANNEL 模式 | 整理通道平台：`CHATGPT` / `DEEPSEEK` / `GEMINI` |
| `participantChannelIds` | string[] | — | 参与通道 ID 列表；为空时默认全部内置通道 |
| `participantAliases` | object | — | 讨论方自定义名称，key 为通道 ID |
| `outputDocuments` | string[] | — | 产出文档类型 ID；为空时使用系统默认 |

### 通道注册表

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/api/profiles/channels` | 列出全部通道及就绪状态 |
| `PUT` | `/api/profiles/channels/{channelId}` | 更新自定义 API 通道 |
| `POST` | `/api/profiles/channels` | 新增自定义 API 通道 |
| `DELETE` | `/api/profiles/channels/{channelId}` | 删除自定义 API 通道 |
| `POST` | `/api/profiles/channels/{channelId}/test` | 检测 API 通道连通性 |

### 整理服务 API 配置

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/api/profiles/api-config` | 查询全局 API 配置（Key 脱敏） |
| `PUT` | `/api/profiles/api-config` | 保存全局 API 配置 |
| `POST` | `/api/profiles/api-config/test` | 检测连通性 |
| `DELETE` | `/api/profiles/api-config` | 清除配置 |

### 浏览器 Profile 管理

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/api/profiles` | 查询所有平台 Profile 状态（兼容） |
| `POST` | `/api/profiles/{platform}/setup` | 打开浏览器引导登录 |
| `GET` | `/api/profiles/{platform}/health` | 健康检查 |
| `DELETE` | `/api/profiles/{platform}` | 清除 Profile |

`platform` 取值：`chatgpt` / `deepseek` / `gemini`

**通道就绪条件**：

- 内置浏览器通道：`loginStatus = LOGGED_IN` 且 Profile 有效
- 自定义 API 通道：已配置 Key / BaseURL / Model 且 `apiVerified = true`

---

## 研讨流程

```
第 1 轮 [初始方案]
  各讨论方独立输出实现方案

第 2 轮 [交叉审阅]
  各讨论方审阅其他讨论方方案

第 3+ 轮 [修订回应]（循环）
  回应审阅意见，输出修订方案

每轮结束后：
  → 收敛检测（中英混合 TF-IDF 相似度）
  → 整理服务归纳本轮材料（异步）
  → JSON 快照持久化

相似度 ≥ 阈值 → 提前结束（CONVERGED）
达到轮数上限 → 结束（MAX_ROUNDS）

研讨结束后（异步）：
  → 按勾选类型逐项生成产出文档
  → 每份文档独立完整，可单独阅读
```

详细说明见 [docs/WORKFLOW.md](docs/WORKFLOW.md)，产出文档类型见 [docs/OUTPUT-DOCUMENTS.md](docs/OUTPUT-DOCUMENTS.md)。

---

## 收敛检测

| 维度 | 说明 |
|------|------|
| 算法 | 中英文混合分词（中文二元组 + 英文单词）+ TF-IDF + 最小 pairwise 余弦相似度 |
| 判定 | `minPairwiseSimilarity >= convergenceThreshold` 时提前结束 |
| 惩罚项 | 中英文否定/转折词命中时扣减相似度，避免表面文本相近但实质分歧被忽略 |
| 配置 | 每场研讨可在 UI 配置 50%~100% 阈值；`application.yml` 中为全局默认值 |

实现类：`convergence/TextSimilarityConvergenceDetector.java`

---

## 配置说明

### 主配置 `application.yml`

```yaml
debate:
  max-rounds: 5                   # 全局默认；每场可在 UI/API 覆盖
  convergence-threshold: 0.75     # 全局默认；每场可在 UI/API 覆盖
  ai-timeout-seconds: 120
  parallel-execution: true

judge:
  base-url: https://api.deepseek.com
  default-model: deepseek-v4-flash

browser:
  profile-base-dir: ${user.home}/.ai-debate-arena/profiles
  headless: true
```

### 调试配置 `application-debug.yml`

```powershell
mvn spring-boot:run -Dspring-boot.run.profiles=debug
```

| 项 | 值 |
|----|-----|
| 端口 | 8080 |
| headless | false |
| parallel-execution | false |
| 日志级别 | DEBUG |

---

## 项目结构

```
ai-debate-arena/
├── README.md
├── docs/
│   ├── WORKFLOW.md               # 研讨流程与提示词说明
│   └── OUTPUT-DOCUMENTS.md       # 产出文档类型说明
├── electron/                     # 桌面客户端
│   ├── main.js
│   ├── app-menu.js
│   └── preload.js
└── src/main/
    ├── java/com/debatearena/
    │   ├── orchestrator/         # 研讨编排引擎
    │   ├── convergence/          # 收敛检测
    │   ├── prompts/              # 提示词构建
    │   ├── judge/                # 整理服务 + 产出文档生成
    │   ├── adapter/              # 各平台浏览器适配器
    │   ├── browser/              # Playwright / Profile / 进程清理
    │   ├── service/              # 通道注册表、进度步骤等
    │   ├── persistence/          # 快照、通道、API 配置持久化
    │   └── controller/           # REST API
    └── resources/
        ├── static/
        │   ├── index.html
        │   ├── css/app.css
        │   └── js/               # Vue 3 ESM 组件
        │       ├── App.js
        │       ├── components/   # DebateForm / HistoryPanel / ProfilesPanel …
        │       └── composables/  # useToast / usePageState / useMarkdown …
        ├── templates/debate/     # 各轮研讨提示词
        ├── templates/judge/      # 轮次整理提示词
        ├── templates/output-documents/  # 产出文档模板（13 种）
        └── selectors/            # 各平台页面选择器（YAML）
```

---

## 技术栈

| 技术 | 版本 | 用途 |
|------|------|------|
| Spring Boot | 3.2.5 | 应用框架 |
| Vue 3 | 3.x（ESM） | Web 控制台 |
| Playwright | 1.54.0 | 浏览器自动化 |
| Apache Lucene | 9.10.0 | 文本预处理与收敛检测 |
| Spring AI | 1.0.0 | 提示词模板渲染 |
| DeepSeek API | — | 整理服务与产出文档生成 |
| Electron | 42.x | 跨平台桌面客户端 |
| marked + DOMPurify | — | Markdown 渲染与 XSS 防护 |

---

## 设计原则

- **需求驱动** — 围绕可落地的实现方案，而非开放式辩论
- **匿名讨论方** — 提示词与产出均使用「讨论方甲/乙/丙」，不暴露厂商或模型名称
- **动态参与** — 未登录通道自动排除，2~3 方研讨自适应
- **整理中立** — 仅客观归纳材料，禁止整理者添加主观意见
- **文档可落地** — 13 种产出文档覆盖方案、需求、测试、接口、权限等维度
- **通道双模式** — 内置浏览器通道 + 自定义 API 通道（须检测通过方可参与）
- **免平台 API Key** — Playwright 模拟浏览器操作（仅整理服务需 DeepSeek API Key）
- **浏览器自愈** — 启动时清理残留进程与锁文件，Profile 占用时自动重试
- **优雅降级** — 最少 2 个通道可用即可继续；每轮 JSON 快照支持崩溃恢复

---

## 常见问题

<details>
<summary><b>为什么必须填写整理服务 API Key？</b></summary>

每轮结束后需归纳各方材料，研讨结束后需生成产出文档。可选方式：

- **API 整理**（`judgeMode = API`）：填写 DeepSeek API Key，或在通道配置页保存全局配置
- **通道整理**（`judgeMode = CHANNEL`）：指定已登录的浏览器通道作为整理方

Key 仅存内存或本机 `api-config.json`，不写入研讨快照。
</details>

<details>
<summary><b>重启后通道检测失败怎么办？</b></summary>

服务被强制终止时浏览器进程可能残留。系统在启动时通过 `BrowserProcessCleaner` 自动清理残留进程与 Profile 锁文件，启动浏览器失败时也会自动重试。若仍失败，可在任务管理器结束 chrome/chromium 进程后重试「检测」。
</details>

<details>
<summary><b>自定义 API 通道为什么不能参与研讨？</b></summary>

新增或修改 API 通道配置后，须点击「检测连接」，检测成功（`apiVerified = true`）后方可勾选参与研讨。
</details>

<details>
<summary><b>收敛阈值怎么理解？</b></summary>

取各方方案两两相似度中的**最小值**，达到设定百分比（如 75%）时提前结束。阈值越高，要求各方方案越接近才结束。每场研讨可在 UI 独立配置 50%~100%。
</details>

<details>
<summary><b>产出文档与 /report 接口有何区别？</b></summary>

`/report` 为兼容旧接口，优先返回「推荐实现方案（完整版）」产出文档。推荐使用 `/documents` 接口获取全部勾选文档列表及下载链接。
</details>

<details>
<summary><b>选择器失效怎么办？</b></summary>

修改 `src/main/resources/selectors/*.yml` 中对应平台的选择器后重启服务。
</details>
