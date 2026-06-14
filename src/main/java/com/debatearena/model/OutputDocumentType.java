package com.debatearena.model;

import java.util.Arrays;
import java.util.List;

/**
 * 研讨结束后可产出的文档类型定义。
 */
public enum OutputDocumentType {

    IMPLEMENTATION_PLAN_FULL(
            "implementation_plan_full",
            "推荐实现方案（完整版）",
            "综合各方研讨结论形成的完整技术实现方案，涵盖架构、模块划分、关键流程与验收要点，供研发团队全面落地参考。",
            "templates/output-documents/implementation-plan-full.txt"),
    IMPLEMENTATION_PLAN_BRIEF(
            "implementation_plan_brief",
            "推荐实现方案（精简版）",
            "从研讨材料提炼的核心方案摘要，快速呈现技术选型、关键决策与 MVP 范围，适合评审对齐与快速传阅。",
            "templates/output-documents/implementation-plan-brief.txt"),
    MVP_PLAN(
            "mvp_plan",
            "MVP 实施计划",
            "按阶段整理的 MVP 交付计划，明确各阶段目标、任务清单、依赖关系与验收标准，用于排期与迭代实施。",
            "templates/output-documents/mvp-plan.txt"),
    API_DATA_DESIGN(
            "api_data_design",
            "接口与数据设计说明",
            "汇总研讨中的 API 设计与数据模型要点，整理接口契约、实体关系与存储方案，供前后端及数据层开发对齐。",
            "templates/output-documents/api-data-design.txt"),
    INDIVIDUAL_PROPOSALS(
            "individual_proposals",
            "各方独立方案汇编",
            "按讨论方归档各轮独立方案，保留各方原始思路与修订轨迹，便于对比溯源与回溯论证过程。",
            "templates/output-documents/individual-proposals.txt"),
    CROSS_REVIEW(
            "cross_review",
            "交叉审阅纪要",
            "记录各方互审意见、质疑与回应，呈现方案在审阅过程中的演进脉络与主要争议焦点。",
            "templates/output-documents/cross-review.txt"),
    DISAGREEMENT_TRADEOFF(
            "disagreement_tradeoff",
            "分歧清单",
            "列出研讨中的共识项与分歧项，并列各方立场，供产品或技术负责人在决策时参考取舍。",
            "templates/output-documents/disagreement-tradeoff.txt"),
    RISK_REGISTER(
            "risk_register",
            "风险登记册",
            "摘录材料中识别的技术、业务与实施风险及缓解措施，用于项目风险识别与跟踪管理。",
            "templates/output-documents/risk-register.txt"),
    OPEN_QUESTIONS(
            "open_questions",
            "待确认事项清单",
            "汇总研讨中未达成一致、需产品或业务方进一步确认的问题，避免开发阶段因需求模糊产生返工。",
            "templates/output-documents/open-questions.txt"),
    PRD_ACCEPTANCE(
            "prd_acceptance",
            "产品需求与验收清单",
            "将研讨结论拆解为可开发的功能条目、业务规则与验收条件，供产品拆票与研发对照实现。",
            "templates/output-documents/prd-acceptance.txt"),
    TEST_PLAN(
            "test_plan",
            "测试计划与用例大纲",
            "基于材料中的验收条件整理测试范围、策略与用例大纲，供 QA 编写用例与回归测试。",
            "templates/output-documents/test-plan.txt"),
    API_SPEC(
            "api_spec",
            "详细接口规格说明",
            "按接口整理路径、方法、参数、响应与错误码等材料中已出现的规格细节，供前后端联调对齐。",
            "templates/output-documents/api-spec.txt"),
    RBAC_MATRIX(
            "rbac_matrix",
            "权限与角色矩阵",
            "整理材料中的角色定义、功能权限与数据访问范围，形成角色权限对照表供开发与测试验证。",
            "templates/output-documents/rbac-matrix.txt");

    private final String id;
    private final String label;
    private final String description;
    private final String templatePath;

    OutputDocumentType(String id, String label, String description, String templatePath) {
        this.id = id;
        this.label = label;
        this.description = description;
        this.templatePath = templatePath;
    }

    public String getId() {
        return id;
    }

    public String getLabel() {
        return label;
    }

    /** 文档用途说明，用于前端展示。 */
    public String getDescription() {
        return description;
    }

    public String getTemplatePath() {
        return templatePath;
    }

    /**
     * 根据 ID 解析文档类型。
     */
    public static OutputDocumentType fromId(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("文档类型不能为空");
        }
        return Arrays.stream(values())
                .filter(t -> t.id.equalsIgnoreCase(id.trim()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知文档类型: " + id));
    }

    /**
     * 新建研讨时的默认勾选文档。
     */
    public static List<String> defaultIds() {
        return List.of(
                IMPLEMENTATION_PLAN_FULL.id,
                PRD_ACCEPTANCE.id,
                MVP_PLAN.id,
                TEST_PLAN.id,
                DISAGREEMENT_TRADEOFF.id
        );
    }

    /**
     * 返回全部可产出文档 ID 列表。
     */
    public static List<String> allIds() {
        return Arrays.stream(values()).map(OutputDocumentType::getId).toList();
    }
}
