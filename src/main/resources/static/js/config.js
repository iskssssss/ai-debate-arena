/** 应用常量配置。 */
export const APP_INTRO = '输入业务或技术需求，由多个 AI 讨论方经过多轮结构化研讨，最终生成可供开发团队落地的实现方案文档。';
export const APP_FUNC_LOGIN = '至少登录 2 个通道方能发起研讨。点击「登录」完成认证，点击「检测」确认状态，不再使用的通道可点「清除」移除。';
export const API = '/api';
export const IS_DESKTOP = new URLSearchParams(window.location.search).get('desktop') === '1'
    || Boolean(window.desktopClient?.isDesktop);
export const JUDGE_KEY_STORAGE = 'debate_judge_api_key';
export const THRESHOLD_STORAGE = 'debate_convergence_threshold_pct';
export const CHANNEL_LABELS = { gemini: '通道丙', chatgpt: '通道甲', deepseek: '通道乙' };
/** 通道对应的讨论方代称（用于头像展示）。 */
export const CHANNEL_AVATARS = { chatgpt: '甲', deepseek: '乙', gemini: '丙' };
export const DEFAULT_OUTPUT_DOCS = ['implementation_plan_full', 'prd_acceptance', 'mvp_plan', 'test_plan', 'disagreement_tradeoff'];
export const STATE_LABELS = { pending: '等待', active: '进行中', done: '完成', error: '异常' };
export const DOC_STATUS_LABELS = { pending: '生成中', ready: '可下载', failed: '失败' };
export const ROUND_TYPE_LABELS = {
    INITIAL: '初始方案', CRITIQUE: '交叉审阅', REBUTTAL: '修订回应', CONVERGENCE: '收敛确认'
};

/** 各页签的标题与描述（用于页头动态更新）。 */
export const PAGE_META = {
    debate: { title: '新建研讨', desc: '填写需求描述与研讨参数，发起多轮结构化研讨' },
    profiles: { title: '通道配置', desc: '管理 AI 讨论方登录状态，至少登录 2 个通道方可发起研讨' },
    debates: { title: '研讨详情', desc: '按任务编号查看研讨进度、轮次材料与产出文档' },
    history: { title: '研讨历史', desc: '本机历次研讨记录，按最近更新时间排列' }
};
