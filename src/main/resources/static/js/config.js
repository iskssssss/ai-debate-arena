/** 应用常量配置。 */
export const APP_INTRO = '输入业务或技术需求，由多个 AI 讨论方经过多轮结构化研讨，最终生成可供开发团队落地的实现方案文档。';
export const APP_FUNC_LOGIN = '至少登录 2 个通道方能发起研讨。点击「登录」完成认证，点击「检测」确认状态，不再使用的通道可点「清除」移除。';
export const API = '/api';
export const IS_DESKTOP = new URLSearchParams(window.location.search).get('desktop') === '1'
    || Boolean(window.desktopClient?.isDesktop);
export const JUDGE_KEY_STORAGE = 'debate_judge_api_key';
export const THRESHOLD_STORAGE = 'debate_convergence_threshold_pct';
export const PARTICIPANT_NAMES_STORAGE = 'debate_participant_names';
/** 通道对应的头像字符。 */
export const CHANNEL_AVATARS = { chatgpt: 'C', deepseek: 'D', gemini: 'G' };
export const DEFAULT_OUTPUT_DOCS = ['implementation_plan_full', 'prd_acceptance', 'mvp_plan', 'test_plan', 'disagreement_tradeoff'];
export const STATE_LABELS = { pending: '等待', active: '进行中', done: '完成', error: '异常' };
export const DOC_STATUS_LABELS = { pending: '生成中', ready: '可下载', failed: '失败' };
export const ROUND_TYPE_LABELS = {
    INITIAL: '初始方案', CRITIQUE: '交叉审阅', REBUTTAL: '修订回应', CONVERGENCE: '收敛确认'
};
/** 各轮次类型在流程说明中的副标题。 */
export const ROUND_TYPE_DETAILS = {
    INITIAL: '各方独立提交实现方案',
    CRITIQUE: '审阅其他方方案并指出问题',
    REBUTTAL: '根据审阅意见修订方案',
    CONVERGENCE: '确认共识与分歧'
};

/** 各页签的标题与描述（用于页头动态更新）。 */
export const PAGE_META = {
    debate: { title: '新建研讨', desc: '填写需求描述与研讨参数，发起多轮结构化研讨' },
    profiles: { title: '通道配置', desc: '管理讨论方通道：内置服务商浏览器登录、添加自定义 API 通道' },
    history: { title: '研讨历史', desc: '查看本机历次研讨记录，点击记录查看进度、讨论与产出文档' }
};
