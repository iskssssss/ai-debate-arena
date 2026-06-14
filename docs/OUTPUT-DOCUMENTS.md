# 产出文档说明

> 更新：2026-06-15

研讨结束后，整理服务根据用户勾选的文档类型，**逐项**调用 DeepSeek API 生成完整独立的 Markdown 文档。每份文档仅基于本场研讨材料客观归纳，不添加整理者意见，可单独阅读、不相互引用。

- 模板目录：`src/main/resources/templates/output-documents/`
- 生成服务：`judge/OutputDocumentService.java`

---

## 默认勾选（5 份）

发起研讨时若未指定 `outputDocuments`，系统默认生成以下文档：

| ID | 名称 | 用途 |
|----|------|------|
| `implementation_plan_full` | 推荐实现方案（完整版） | 综合架构、模块、流程与验收要点，供研发全面落地 |
| `prd_acceptance` | 产品需求与验收清单 | 可开发的功能条目、业务规则与验收条件 |
| `mvp_plan` | MVP 实施计划 | 分阶段目标、任务、依赖与验收标准 |
| `test_plan` | 测试计划与用例大纲 | 测试范围、策略与用例大纲 |
| `disagreement_tradeoff` | 分歧清单 | 共识项与分歧项，并列各方立场 |

---

## 全部文档类型（13 份）

### 方案类

| ID | 名称 | 用途 |
|----|------|------|
| `implementation_plan_full` | 推荐实现方案（完整版） | 完整技术实现方案 |
| `implementation_plan_brief` | 推荐实现方案（精简版） | 核心摘要，适合评审传阅 |
| `mvp_plan` | MVP 实施计划 | 分期交付与排期依据 |

### 设计与规格类

| ID | 名称 | 用途 |
|----|------|------|
| `api_data_design` | 接口与数据设计说明 | 架构层接口划分、实体关系与存储方案 |
| `api_spec` | 详细接口规格说明 | 路径、参数、响应、错误码等材料级规格 |
| `rbac_matrix` | 权限与角色矩阵 | 角色定义与功能/数据权限对照 |

### 过程与决策类

| ID | 名称 | 用途 |
|----|------|------|
| `individual_proposals` | 各方独立方案汇编 | 按讨论方归档各轮方案，便于溯源 |
| `cross_review` | 交叉审阅纪要 | 互审意见、质疑与回应 |
| `disagreement_tradeoff` | 分歧清单 | 共识与分歧并列呈现 |

### 落地与治理类

| ID | 名称 | 用途 |
|----|------|------|
| `prd_acceptance` | 产品需求与验收清单 | 拆票与研发对照 |
| `test_plan` | 测试计划与用例大纲 | QA 编写用例依据 |
| `risk_register` | 风险登记册 | 风险与缓解措施摘录 |
| `open_questions` | 待确认事项清单 | 需产品/业务方进一步确认的问题 |

---

## 文档间分工

| 对比 | 说明 |
|------|------|
| `api_data_design` vs `api_spec` | 前者侧重架构与模块交互；后者展开逐接口参数表 |
| `implementation_plan_full` vs `implementation_plan_brief` | 完整版 vs 摘要版 |
| `open_questions` vs `prd_acceptance` | 前者列未决问题；后者列已明确可验收条目 |
| `/report` vs `/documents` | `/report` 为兼容旧接口，优先返回完整版方案 |

---

## 生成规则

1. **触发时机** — 研讨状态变为 `CONVERGED` 或 `MAX_ROUNDS` 后异步执行
2. **逐项生成** — 每份文档独立一次 API 调用，完成后写入快照
3. **状态标记** — `pending`（排队/生成中）/ `ready`（可下载）/ `failed`（生成失败）
4. **正文清洗** — `DocumentContentSanitizer` 去除模型寒暄语，保留纯 Markdown 正文
5. **输出约束** — `_output-format-rules.txt` 要求直接从 `#` 标题起笔，禁止开场白

---

## API 使用

```powershell
# 获取全部可勾选类型（含用途说明）
GET /api/debates/output-document-types

# 本场文档列表与生成状态
GET /api/debates/{sessionId}/documents

# 下载单份文档（Markdown）
GET /api/debates/{sessionId}/documents/{typeId}
```

**启动时指定文档**：

```json
{
  "outputDocuments": [
    "implementation_plan_full",
    "prd_acceptance",
    "api_spec",
    "rbac_matrix"
  ]
}
```

---

## 自定义模板

1. 编辑 `templates/output-documents/{type}.txt`
2. 保持「整理原则」：仅客观摘录，禁止整理者添加意见
3. 修改 `OutputDocumentType.java` 可新增类型（需同步 ID、label、description、模板路径）
4. 重启服务后生效（模板有缓存，重启清空）
