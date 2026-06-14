import { API } from './config.js';
import { escapeHtml, formatTime, statusBadge, historyStatusClass } from './utils.js';

/** 加载研讨历史列表。 */
export async function loadHistory(silent) {
    const el = document.getElementById('history-list');
    if (!silent) el.innerHTML = '<div class="loading">加载中</div>';
    try {
        const res = await fetch(API + '/debates?limit=30');
        if (!res.ok) throw new Error('读取失败');
        const items = await res.json();
        if (!items.length) {
            el.innerHTML = '<div class="empty-state"><div class="empty-icon">📭</div><p>暂无研讨记录</p><p class="hint">在「新建研讨」页发起第一场研讨</p></div>';
            return;
        }
        el.innerHTML = '<div class="history-list">' + items.map(renderHistoryItem).join('') + '</div>';
    } catch (e) {
        if (!silent) el.innerHTML = `<div class="callout callout-error">加载失败：${e.message}</div>`;
    }
}

/** 渲染单条研讨历史记录 HTML。 */
export function renderHistoryItem(item) {
    const participants = (item.participants || []).join('、') || '—';
    const time = formatTime(item.updatedAt || item.createdAt);
    const statusCls = historyStatusClass(item.status);
    const reason = item.status === 'FAILED' && item.failureReason
        ? `<div class="hint" style="color:var(--error);margin-top:8px;">${escapeHtml(item.failureReason)}</div>` : '';
    return `<div class="history-card ${statusCls}" onclick="openSession('${item.sessionId}')">
        <div class="history-title">${escapeHtml(item.topic || '未命名研讨')}</div>
        <div class="history-meta">
            ${statusBadge(item.status, item.statusLabel)}
            <span>${escapeHtml(item.sessionId.slice(0, 8))}…</span>
            <span>轮次 ${item.currentRound}/${item.maxRounds}</span>
            <span>${escapeHtml(participants)}</span>
            <span>${escapeHtml(time)}</span>
        </div>
        ${reason}
    </div>`;
}
