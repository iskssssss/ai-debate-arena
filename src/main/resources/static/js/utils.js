/** HTML 转义，防止 XSS。 */
export function escapeHtml(text) {
    if (!text) return '';
    return String(text).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

/** 格式化日期时间为本地字符串。 */
export function formatTime(value) {
    if (!value) return '—';
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return value;
    return date.toLocaleString('zh-CN', { hour12: false });
}

/** 根据研讨状态返回徽章 CSS 类名。 */
export function statusBadgeClass(status) {
    if (status === 'CONVERGED') return 'badge-ok';
    if (status === 'FAILED') return 'badge-err';
    if (status === 'MAX_ROUNDS') return 'badge-warn';
    return 'badge-neutral';
}

/** 根据研讨状态返回历史卡片样式类名。 */
export function historyStatusClass(status) {
    if (status === 'CONVERGED') return 'status-ok';
    if (status === 'FAILED') return 'status-err';
    if (status === 'MAX_ROUNDS') return 'status-warn';
    return 'status-run';
}

/** 根据通道登录状态返回徽章 CSS 类名。 */
export function loginBadgeClass(status) {
    if (status === 'LOGGED_IN') return 'badge-ok';
    if (status === 'ERROR') return 'badge-err';
    return 'badge-warn';
}

/** 根据通道登录状态返回徽章文本。 */
export function loginBadgeText(status) {
    if (status === 'LOGGED_IN') return '已登录';
    if (status === 'ERROR') return '异常';
    return '未登录';
}

/**
 * 将研讨主题转为列表展示用纯文本（去除 Markdown 标记并截断）。
 * @param {string} topic 原始主题
 * @param {number} [maxLen=80] 最大字符数
 * @returns {string} 展示用标题
 */
export function displayTopic(topic, maxLen = 80) {
    if (!topic) return '未命名研讨';
    const plain = topic
        .replace(/^#{1,6}\s+/gm, '')
        .replace(/\*\*/g, '')
        .replace(/`+/g, '')
        .replace(/\n+/g, ' ')
        .trim();
    return plain.length > maxLen ? plain.slice(0, maxLen) + '…' : plain;
}

/**
 * 判断文本是否为 UUID 格式的任务编号。
 * @param {string} text 待检测文本
 * @returns {boolean} 是否为任务编号
 */
export function isSessionId(text) {
    const q = (text || '').trim();
    return /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(q);
}

/**
 * 复制文本到剪贴板。
 * @param {string} text 待复制内容
 * @returns {Promise<boolean>} 是否复制成功
 */
export async function copyToClipboard(text) {
    if (!text) return false;
    try {
        await navigator.clipboard.writeText(text);
        return true;
    } catch {
        try {
            const ta = document.createElement('textarea');
            ta.value = text;
            ta.style.position = 'fixed';
            ta.style.opacity = '0';
            document.body.appendChild(ta);
            ta.select();
            document.execCommand('copy');
            document.body.removeChild(ta);
            return true;
        } catch {
            return false;
        }
    }
}
