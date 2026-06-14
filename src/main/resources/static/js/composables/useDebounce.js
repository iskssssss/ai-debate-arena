/**
 * 创建防抖函数。
 * @param {Function} fn 目标函数
 * @param {number} delay 延迟毫秒
 * @returns {Function} 防抖后的函数
 */
export function debounce(fn, delay = 300) {
    let timer = null;
    return function (...args) {
        clearTimeout(timer);
        timer = setTimeout(() => fn.apply(this, args), delay);
    };
}

/**
 * 生成研讨会话数据指纹，用于轮询时跳过无变化的更新。
 * @param {object} status 研讨状态
 * @param {object} judge 整理数据
 * @returns {string} 指纹字符串
 */
export function sessionFingerprint(status, judge) {
    if (!status) return '';
    const rounds = (judge?.rounds || []).map(r =>
        `${r.roundNumber}:${Object.keys(r.prompts || {}).length}:${Object.keys(r.responses || {}).length}:${r.judgeRecord?.success ?? 'n'}`
    ).join(';');
    const fj = judge?.finalJudge;
    const fjKey = fj ? `${fj.success}:${(fj.analysis || fj.errorMessage || '').length}` : 'none';
    const steps = (status.steps || []).map(s => `${s.id}:${s.state}`).join(',');
    return `${status.status}|${status.currentRound}|${status.currentPhase}|${status.postProcessing}|${steps}|${rounds}|${fjKey}`;
}
