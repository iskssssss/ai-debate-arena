/** 显示右下角 Toast 提示。 */
export function toast(msg, type = 'info') {
    const root = document.getElementById('toast-root') || document.body;
    const t = document.createElement('div');
    t.className = 'toast toast-' + type;
    t.textContent = msg;
    root.appendChild(t);
    setTimeout(() => {
        t.style.opacity = '0';
        t.style.transition = 'opacity 0.2s';
        setTimeout(() => t.remove(), 200);
    }, 3200);
}

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

/** 根据研讨状态生成徽章 HTML。 */
export function statusBadge(status, statusLabel) {
    const cls = status === 'CONVERGED' ? 'badge-ok'
        : status === 'FAILED' ? 'badge-err'
        : status === 'MAX_ROUNDS' ? 'badge-warn' : 'badge-neutral';
    return `<span class="badge ${cls}">${statusLabel || status}</span>`;
}

/** 根据研讨状态返回历史卡片样式类名。 */
export function historyStatusClass(status) {
    if (status === 'CONVERGED') return 'status-ok';
    if (status === 'FAILED') return 'status-err';
    if (status === 'MAX_ROUNDS') return 'status-warn';
    return 'status-run';
}

/** 根据通道登录状态生成徽章 HTML。 */
export function badgeForLogin(status) {
    if (status === 'LOGGED_IN') return '<span class="badge badge-ok">已登录</span>';
    if (status === 'ERROR') return '<span class="badge badge-err">异常</span>';
    return '<span class="badge badge-warn">未登录</span>';
}
