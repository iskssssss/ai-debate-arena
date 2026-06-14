# 方案研讨台（AI Debate Arena）

**输入需求 → 多方研讨实现方案 → 输出开发可落地的技术文档**

将业务或技术需求提交给多个 AI 讨论方，经多轮结构化研讨后，由整理服务归纳材料并生成可供开发团队查阅、评审和落地的 Markdown 文档。

> 文档更新：2026-06-14

---

## 核心价值

| 阶段 | 说明 |
|------|------|
| **输入** | 需求描述（功能目标、边界、约束、验收标准） |
| **研讨** | 讨论方甲/乙/丙 独立出方案 → 交叉审阅 → 修订回应 → 收敛确认 |
| **整理** | 每轮结束后整理服务客观归纳各方材料（须配置 API Key） |
| **输出** | 按勾选类型生成多份完整独立 Markdown 产出文档 |

研讨过程中**不使用模型名称**，各方以「讨论方甲」「讨论方乙」「讨论方丙」匿名参与，避免立场偏见。整理服务仅客观摘录归纳，不添加整理者本人的意见或建议。

---

## 架构概览

```
┌─────────────────────────────────────────────────────────────┐
│         Web UI (index.html) / Electron 桌面客户端            │
│  新建研讨 | 通道配置 | 研讨详情 | 研讨历史                     │
└──────────────────────────┬──────────────────────────────────┘
                           │ REST API
┌──────────────────────────▼──────────────────────────────────┐
│                    Debate Orchestrator                       │
│  初始方案 → 交叉审阅 → 修订回应 → 收敛确认（循环至收敛/上限）   │
│         ↓ 每轮结束                                          │
│  整理服务（DeepSeek API，必选）+ JSON 快照持久化              │
│         ↓ 研讨结束                                          │
│  OutputDocumentService 逐项生成产出文档                      │
└──────────────────────────┬──────────────────────────────────┘
                           │
┌──────────────────────────▼──────────────────────────────────┐
│              Browser Automation (Playwright)                 │
│   Gemini（通道丙）| ChatGPT（通道甲）| DeepSeek（通道乙）       │
│   独立 Playwright 持久化 Profile，无需各平台 API Key          │
└─────────────────────────────────────────────────────────────┘
```

---

## 快速开始

### 前置条件

- **JDK 17+**
- **Maven 3.6+**
- **Node.js 18+**（仅桌面客户端开发/打包需要）
- **Chromium**（Playwright 自动下载）
- **整理服务 API Key**（DeepSeek，发起研讨必填）

### 1. 安装 Playwright 浏览器

```powershell
cd ai-debate-arena
mvn exec:java -e -D exec.mainClass=com.microsoft.playwright.CLI -D exec.args="install chromium"
```

### 2. 启动服务（调试模式）

```powershell
mvn spring-boot:run -Dspring-boot.run.profiles=debug
```

调试模式：端口 `8080`、显示浏览器窗口、串行执行、详细日志。

浏览器访问：**http://localhost:8080**

### 3. 桌面客户端（Electron）

桌面客户端自动启动本机 Spring Boot 后端，在客户端窗口中加载研讨台页面。

```powershell
npm install
npm run desktop:dev      # 开发模式（会先构建后端 jar）
npm run desktop:build    # 打包安装包
```

说明：

- 首版客户端检测并使用系统 **Java 17+** 运行后端
- 可通过环境变量 `MAVEN_CMD` 指定 Maven 命令
- 打包产物输出到 `dist/`
- Playwright 登录与自动化使用**独立浏览器窗口**，不嵌入 Electron WebView
- 桌面菜单：`Ctrl+1` 新建研讨、`Ctrl+2` 通道配置、`Ctrl+3` 研讨详情、`Ctrl+4` 研讨历史

### 4. 登录 AI 通道（首次）

在「通道配置」页，对需要参与研讨的平台点击「登录」，在弹出的独立浏览器窗口中完成手动登录，再点击「检测」确认状态。

```powershell
curl -X POST http://localhost:8080/api/profiles/chatgpt/setup -H "Content-Type: application/json" -d "{\"timeoutSeconds\": 300}"
curl -X POST http://localhost:8080/api/profiles/deepseek/setup -H "Content-Type: application/json" -d "{\"timeoutSeconds\": 300}"
```

Cookie 持久化目录：`%USERPROFILE%\.ai-debate-arena\profiles\`

**至少需要 2 个通道已登录**才能发起研讨；未满足时页面会自动跳转至通道配置。

### 5. 发起研讨

**Web UI（默认首页「新建研讨」）**：

1. 填写需求描述
2. 配置研讨轮数、收敛阈值（50%~100%，默认 75%）
3. 填写整理服务 API Key（仅存浏览器本地，随请求发送，服务端不落盘）
4. 勾选产出文档（至少 1 份，默认 5 份）
5. 点击「提交研讨」

**API 示例**：

```powershell
curl -X POST http://localhost:8080/api/debates `
  -H "Content-Type: application/json" `
  -d '{
    "topic": "为电商系统实现订单超时自动取消：支持分布式部署、可配置超时、库存回滚与消息通知",
    "maxRounds": 4,
    "convergenceThreshold": 0.75,
    "judgeEnabled": true,
    "judgeApiKey": "sk-xxx",
    "judgeModel": "deepseek-v4-flash",
    "outputDocuments": [
      "implementation_plan_full",
      "prd_acceptance",
      "mvp_plan",
      "test_plan",
      "disagreement_tradeoff"
    ]
  }'
```

### 6. 查看结果

```powershell
# 查询进度与步骤树
curl http://localhost:8080/api/debates/{sessionId}

# 产出文档列表及生成状态
curl http://localhost:8080/api/debates/{sessionId}/documents

# 下载指定产出文档（Markdown）
curl http://localhost:8080/api/debates/{sessionId}/documents/implementation_plan_full

# 兼容旧接口：优先返回「推荐实现方案（完整版）」
curl http://localhost:8080/api/debates/{sessionId}/report

# 轮次整理材料（各轮提示词/回答/整理结果）
curl http://localhost:8080/api/debates/{sessionId}/judge
```

Web UI「研讨详情」支持进度时间线、轮次材料、产出文档 Markdown 预览与下载。

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
| `GET` | `/api/debates/{sessionId}/report` | 兼容报告（优先完整版产出文档） |
| `GET` | `/api/debates/{sessionId}/judge` | 轮次整理材料 |
| `POST` | `/api/debates/{sessionId}/cancel` | 终止研讨 |
| `POST` | `/api/debates/{sessionId}/resume` | 恢复快照 |

**启动请求体**（`DebateRequest`）：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `topic` | string | ✅ | 需求描述 |
| `maxRounds` | int | — | 最大轮数，默认 5，范围 2~10 |
| `convergenceThreshold` | double | — | 收敛阈值 0.5~1.0，默认 0.75（UI 以百分比配置） |
| `judgeEnabled` | boolean | — | 默认 `true`，须配合 API Key |
| `judgeApiKey` | string | ✅ | 整理服务 API Key（仅内存，不落盘） |
| `judgeModel` | string | — | 默认 `deepseek-v4-flash` |
| `outputDocuments` | string[] | — | 产出文档类型 ID；为空时使用系统默认 |

### 通道（Profile）管理

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/api/profiles` | 查询所有通道状态 |
| `POST` | `/api/profiles/{platform}/setup` | 打开浏览器引导登录 |
| `GET` | `/api/profiles/{platform}/health` | 健康检查 |
| `DELETE` | `/api/profiles/{platform}` | 清除 Profile |

`platform` 取值：`gemini`（通道丙）/ `chatgpt`（通道甲）/ `deepseek`（通道乙）

---

## 研讨流程

```
第 1 轮 [初始方案]
  各讨论方独立输出实现方案

第 2 轮 [交叉审阅]
  审阅其他讨论方方案

第 3+ 轮 [修订回应]（循环）
  回应审阅意见，输出修订方案

每轮结束后
  → 收敛检测（双语 TF-IDF 相似度）
  → 整理服务归纳本轮材料（异步）
  → 持久化 JSON 快照

相似度达到阈值 → 提前结束（CONVERGED）
达到轮数上限   → 结束（MAX_ROUNDS）

研讨结束后（异步）
  → 按勾选类型逐项生成产出文档
  → 每份文档独立完整，可单独阅读
```

详细说明见 [docs/WORKFLOW.md](docs/WORKFLOW.md)。产出文档类型见 [docs/OUTPUT-DOCUMENTS.md](docs/OUTPUT-DOCUMENTS.md)。

---

## 收敛检测

- **算法**：中英文混合分词（中文二元组 + 英文词）+ TF-IDF + 最小 pairwise 余弦相似度
- **判定**：`minPairwiseSimilarity >= convergenceThreshold` 时提前结束
- **惩罚**：中英文否定/转折词命中时扣减相似度，避免表面文本相近但实质分歧被忽略
- **配置**：每场研讨可在 UI 配置 50%~100% 阈值；`application.yml` 中的 `convergence-threshold` 仅为全局默认值

实现类：`convergence/TextSimilarityConvergenceDetector.java`

---

## 配置说明

### 主配置 `application.yml`

```yaml
debate:
  max-rounds: 5
  convergence-threshold: 0.75   # 全局默认；每场可在 UI/API 覆盖
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
│   ├── WORKFLOW.md              # 研讨流程与提示词
│   └── OUTPUT-DOCUMENTS.md      # 产出文档类型说明
├── electron/                    # 桌面客户端
│   ├── main.js
│   ├── app-menu.js
│   └── preload.js
└── src/main/
    ├── java/com/debatearena/
    │   ├── orchestrator/        # 研讨编排
    │   ├── convergence/         # 收敛检测
    │   ├── prompts/             # 研讨提示词构建
    │   ├── judge/               # 整理服务 + 产出文档生成
    │   ├── adapter/             # 各平台浏览器适配器
    │   ├── browser/             # Playwright / Profile 管理
    │   ├── reporting/           # 兼容报告生成（SynthesisGenerator）
    │   ├── service/             # 进度步骤构建等
    │   └── controller/          # REST API
    └── resources/
        ├── static/index.html    # Web 控制台
        ├── static/vendor/       # Markdown 预览依赖
        ├── templates/debate/    # 各轮研讨提示词
        ├── templates/judge/     # 轮次整理提示词
        ├── templates/output-documents/  # 产出文档模板（13 种）
        └── selectors/           # 各平台页面选择器（YAML）
```

---

## 技术栈

| 技术 | 版本 | 用途 |
|------|------|------|
| Spring Boot | 3.2.5 | 应用框架 |
| Playwright | 1.54.0 | 浏览器自动化 |
| Apache Lucene | 9.10.0 | 文本预处理（收敛检测） |
| Spring AI (ST 模板) | 1.0.0 | 提示词模板渲染 |
| DeepSeek API | — | 整理服务与产出文档生成 |
| Electron | 42.x | 跨平台桌面客户端 |
| marked + DOMPurify | — | 产出文档 Markdown 预览 |

---

## 设计理念

- **需求驱动**：围绕可落地的实现方案，而非开放式辩论
- **匿名讨论方**：提示词与产出均使用讨论方甲/乙/丙
- **动态参与**：未登录通道自动排除，2~3 方研讨自适应
- **整理中立**：仅客观归纳材料，禁止整理者添加主观意见
- **文档可落地**：13 种产出文档覆盖方案、需求、测试、接口、权限等
- **免平台 API Key**：Playwright 模拟浏览器（整理服务 API 除外）
- **优雅降级**：至少 2 个通道可用即可继续；每轮 JSON 快照支持恢复

---

## 常见问题

**Q: 为什么必须填写整理服务 API Key？**  
A: 每轮结束后需归纳各方材料，研讨结束后需生成产出文档，均依赖 DeepSeek API。Key 仅存内存，不写入磁盘快照。

**Q: 收敛阈值怎么理解？**  
A: 取各方方案两两相似度中的**最小值**，达到设定百分比（如 75%）时提前结束。阈值越高，要求各方方案越接近才结束。

**Q: 相似度为什么以前总是很高？**  
A: 旧版使用 EnglishAnalyzer 处理中文导致失真。现已改为中英文混合分词 + TF-IDF，相似度更能反映实质差异。

**Q: 产出文档与 `/report` 有何区别？**  
A: `/report` 为兼容接口，优先返回「推荐实现方案（完整版）」产出文档；推荐使用 `/documents` 获取全部勾选文档。

**Q: 选择器失效怎么办？**  
A: 修改 `src/main/resources/selectors/*.yml` 后重启服务。
