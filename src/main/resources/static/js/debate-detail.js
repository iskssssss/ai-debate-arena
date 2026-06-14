import { API, JUDGE_KEY_STORAGE, STATE_LABELS, ROUND_TYPE_LABELS } from './config.js';
import { state } from './state.js';
import { toast, escapeHtml, statusBadge } from './utils.js';
import { switchTab } from './navigation.js';
import {
    renderDocumentSection, updateDocumentDescription, clearDocumentToc, renderMarkdown
} from './documents.js';
import { savePageState, restoreScrollPosition } from './page-state.js';

/** 停止自动轮询。 */
export function stopPoll() {
    if (state.pollTimer) { clearInterval(state.pollTimer); state.pollTimer = null; }
}

/** 判断研讨是否仍在进行中（需轮询）。 */
export function shouldPoll(status) {
    const terminal = ['CONVERGED', 'MAX_ROUNDS', 'FAILED'];
    return !terminal.includes(status);
}

/** 启动每 3 秒自动刷新研讨详情。 */
export function startPoll() {
    stopPoll();
    state.pollTimer = setInterval(() => lookupDebate(true), 3000);
}

/**
 * 将历史子标签名映射为当前子标签（兼容旧状态）。
 */
function normalizeDetailTab(tab) {
    if (!tab || tab === 'overview' || tab === 'chat' || tab === 'materials') return 'main';
    return tab;
}

/**
 * 切换研讨详情子标签（研讨主视图 / 产出文档）。
 */
export function switchDetailTab(tab, options = {}) {
    const normalized = normalizeDetailTab(tab);
    document.querySelectorAll('.detail-tab').forEach(t => {
        t.classList.toggle('active', t.dataset.detailTab === normalized);
    });
    document.querySelectorAll('.detail-panel').forEach(p => {
        p.classList.toggle('active', p.id === 'detail-panel-' + normalized);
    });
    if (!options.skipSave) {
        savePageState({ detailTab: normalized });
    }
}

/**
 * 解析概览步骤对应的讨论记录锚点 ID。
 */
function resolveChatAnchor(step, status, judge) {
    const id = step.id;
    if (!id) return null;
    if (id === 'prep') return 'chat-anchor-start';
    if (id.startsWith('round-')) return 'chat-anchor-' + id;
    if (id === 'finalize') return 'chat-anchor-final';
    if (id === 'convergence') {
        const roundNum = status.currentRound || judge?.rounds?.length || 1;
        return 'chat-anchor-round-' + roundNum;
    }
    if (id === 'report') return '__documents__';
    return null;
}

/**
 * 点击概览节点，定位到右侧讨论记录相应位置。
 */
export function scrollToChatAnchor(anchorId) {
    if (anchorId === '__documents__') {
        switchDetailTab('documents');
        return;
    }
    switchDetailTab('main');
    requestAnimationFrame(() => {
        const pane = document.getElementById('detail-chat-pane');
        const anchor = document.getElementById(anchorId);
        if (!pane || !anchor) {
            toast('该节点暂无对应讨论记录', 'info');
            return;
        }
        document.querySelectorAll('.overview-step').forEach(el => {
            el.classList.toggle('overview-step-selected', el.dataset.anchor === anchorId);
        });
        const top = anchor.getBoundingClientRect().top
            - pane.getBoundingClientRect().top + pane.scrollTop - 12;
        pane.scrollTo({ top: Math.max(0, top), behavior: 'smooth' });
        anchor.classList.add('chat-anchor-highlight');
        setTimeout(() => anchor.classList.remove('chat-anchor-highlight'), 1600);
    });
}

/** 绑定概览节点点击事件（事件委托）。 */
function bindOverviewClickEvents() {
    const overviewPane = document.getElementById('detail-overview-pane');
    if (!overviewPane) return;
    overviewPane.addEventListener('click', (e) => {
        const step = e.target.closest('.overview-step-link');
        if (!step?.dataset.anchor) return;
        scrollToChatAnchor(step.dataset.anchor);
    });
}

/** 气泡放大事件是否已绑定到详情根节点。 */
let bubbleExpandBound = false;

/**
 * 打开聊天气泡内容放大弹窗。
 * @param {string} title 发送方名称
 * @param {string} tag 消息类型标签
 * @param {string} content 原始 Markdown 正文
 */
export function openBubbleModal(title, tag, content) {
    const modal = document.getElementById('bubble-modal');
    const titleEl = document.getElementById('bubble-modal-title');
    const tagEl = document.getElementById('bubble-modal-tag');
    const bodyEl = document.getElementById('bubble-modal-body');
    if (!modal || !titleEl || !tagEl || !bodyEl) return;

    titleEl.textContent = title || '消息内容';
    if (tag) {
        tagEl.textContent = tag;
        tagEl.hidden = false;
    } else {
        tagEl.textContent = '';
        tagEl.hidden = true;
    }
    bodyEl.innerHTML = renderMarkdown(content || '');
    modal.hidden = false;
    modal.setAttribute('aria-hidden', 'false');
    document.body.classList.add('bubble-modal-open');
    modal.querySelector('.bubble-modal-close')?.focus();
}

/**
 * 关闭聊天气泡放大弹窗。
 */
export function closeBubbleModal() {
    const modal = document.getElementById('bubble-modal');
    if (!modal || modal.hidden) return;
    modal.hidden = true;
    modal.setAttribute('aria-hidden', 'true');
    document.body.classList.remove('bubble-modal-open');
    document.getElementById('bubble-modal-body')?.replaceChildren();
}

/**
 * 绑定气泡点击放大事件（委托到 debate-detail 根节点，轮询重渲染后仍有效）。
 */
function bindBubbleExpandEvents() {
    const root = document.getElementById('debate-detail');
    if (!root || bubbleExpandBound) return;
    bubbleExpandBound = true;

    root.addEventListener('click', (e) => {
        if (e.target.closest('button')) return;
        const bubble = e.target.closest('.chat-bubble-expandable');
        if (!bubble) return;
        const raw = bubble.querySelector('.chat-bubble-raw')?.value;
        if (!raw?.trim()) return;
        const msg = bubble.closest('.chat-msg');
        const title = msg?.querySelector('.chat-sender')?.textContent?.trim() || '消息内容';
        const tag = msg?.querySelector('.chat-tag')?.textContent?.trim() || '';
        openBubbleModal(title, tag, raw);
    });

    root.addEventListener('keydown', (e) => {
        if (e.key !== 'Enter' && e.key !== ' ') return;
        const bubble = e.target.closest('.chat-bubble-expandable');
        if (!bubble) return;
        e.preventDefault();
        bubble.click();
    });
}

/** 弹窗是否已绑定全局关闭事件。 */
let bubbleModalBound = false;

/** 绑定弹窗遮罩、关闭按钮与 Esc 键（全局只绑定一次）。 */
export function bindBubbleModalEvents() {
    if (bubbleModalBound) return;
    bubbleModalBound = true;

    const modal = document.getElementById('bubble-modal');
    if (!modal) return;

    modal.addEventListener('click', (e) => {
        if (e.target.closest('[data-bubble-modal-close]')) {
            closeBubbleModal();
        }
    });

    document.addEventListener('keydown', (e) => {
        if (e.key === 'Escape') closeBubbleModal();
    });
}

/** 渲染活跃步骤的子任务列表（用于概览时间线）。 */
function renderStepChildrenForOverview(step, steps) {
    const active = steps?.find(s => s.state === 'active');
    if (step !== active || !step.children?.length) return '';
    let html = '<div class="overview-children">';
    for (const c of step.children) {
        const stateLabel = STATE_LABELS[c.state] || c.state;
        html += `<div class="overview-child">
            <span class="overview-child-label">${escapeHtml(c.label)}</span>
            <span class="overview-child-state">${stateLabel}${c.detail ? ' · ' + escapeHtml(c.detail) : ''}</span>
        </div>`;
    }
    html += '</div>';
    return html;
}

/** 渲染垂直时间线概览（左侧序号，右侧详情，可点击定位讨论记录）。 */
function renderOverviewPanel(status, judge) {
    let html = '<div class="overview-panel">';
    if (status.currentPhase) {
        html += `<div class="overview-phase">${escapeHtml(status.currentPhase)}</div>`;
    }
    if (status.steps?.length) {
        html += '<div class="overview-timeline">';
        status.steps.forEach((step, i) => {
            const isLast = i === status.steps.length - 1;
            const indexText = step.state === 'done' ? '✓' : String(i + 1);
            const anchor = resolveChatAnchor(step, status, judge);
            const linkCls = anchor ? ' overview-step-link' : '';
            const dataAttr = anchor ? ` data-anchor="${anchor}"` : '';
            html += `<div class="overview-step ${step.state}${linkCls}"${dataAttr}>
                <div class="overview-rail">
                    <div class="overview-index">${indexText}</div>
                    ${!isLast ? '<div class="overview-line"></div>' : ''}
                </div>
                <div class="overview-content">
                    <div class="overview-title">${escapeHtml(step.label)}</div>
                    ${step.detail ? `<div class="overview-detail">${escapeHtml(step.detail)}</div>` : ''}
                    ${renderStepChildrenForOverview(step, status.steps)}
                </div>
            </div>`;
        });
        html += '</div>';
    } else {
        html += '<div class="empty-state"><p>暂无进度信息</p></div>';
    }
    if (status.failureReason && status.status === 'FAILED') {
        html += `<div class="callout callout-error overview-error">${escapeHtml(status.failureReason)}</div>`;
    }
    html += '</div>';
    return html;
}

/** 根据讨论方名称推断聊天室展示样式（头像、气泡颜色；多方均靠左展示）。 */
function getSpeakerStyle(name) {
    const n = name || '';
    if (/甲|chatgpt/i.test(n)) return { avatar: '甲', party: 'party-a' };
    if (/乙|deepseek/i.test(n)) return { avatar: '乙', party: 'party-b' };
    if (/丙|gemini/i.test(n)) return { avatar: '丙', party: 'party-c' };
    return { avatar: n.charAt(0) || '?', party: 'party-default' };
}

/** 合并并排序本轮参与方名称。 */
function sortSpeakers(prompts, responses) {
    const names = new Set([...Object.keys(prompts || {}), ...Object.keys(responses || {})]);
    const priority = ['讨论方甲', '讨论方乙', '讨论方丙'];
    return [...names].sort((a, b) => {
        const ia = priority.indexOf(a), ib = priority.indexOf(b);
        if (ia !== -1 || ib !== -1) return (ia === -1 ? 99 : ia) - (ib === -1 ? 99 : ib);
        return a.localeCompare(b, 'zh-CN');
    });
}

/** 渲染聊天气泡内的 Markdown 正文。 */
function renderChatBubbleContent(content) {
    return `<div class="md-preview chat-bubble-md">${renderMarkdown(content)}</div>`;
}

/** 包裹气泡外壳与内层滚动区，避免原生滚动条破坏圆角；有正文时可点击放大。 */
function wrapChatBubble(bubbleCls, innerHtml, rawContent) {
    const hasContent = rawContent != null && String(rawContent).trim();
    if (!hasContent) {
        return `<div class="chat-bubble ${bubbleCls}">
            <div class="chat-bubble-scroll">${innerHtml}</div>
        </div>`;
    }
    return `<div class="chat-bubble ${bubbleCls} chat-bubble-expandable" role="button" tabindex="0" title="点击查看完整内容">
        <div class="chat-bubble-scroll">${innerHtml}</div>
        <textarea class="chat-bubble-raw" hidden readonly>${escapeHtml(rawContent)}</textarea>
    </div>`;
}

/** 渲染单条聊天消息气泡（多方统一靠左排列）。 */
function renderChatBubble({ avatar, avatarCls, sender, tag, bubbleCls, content, charCount }) {
    return `<div class="chat-msg">
        <div class="chat-avatar chat-avatar-${avatarCls}">${escapeHtml(avatar)}</div>
        <div class="chat-body">
            <div class="chat-meta">
                <span class="chat-sender">${escapeHtml(sender)}</span>
                ${tag ? `<span class="chat-tag">${escapeHtml(tag)}</span>` : ''}
                ${charCount ? `<span>${charCount} 字</span>` : ''}
            </div>
            ${wrapChatBubble(`chat-bubble-${bubbleCls}`, renderChatBubbleContent(content), content)}
        </div>
    </div>`;
}

/** 渲染单轮讨论内容（不含外层 chat-feed 容器）。 */
function renderRoundChatContent(r) {
    const speakers = sortSpeakers(r.prompts, r.responses);
    let html = '';

    for (const name of speakers) {
        const style = getSpeakerStyle(name);
        const prompt = r.prompts?.[name];
        const response = r.responses?.[name];

        if (prompt) {
            html += renderChatBubble({
                avatar: style.avatar,
                avatarCls: style.party,
                sender: name,
                tag: '发送内容',
                bubbleCls: 'prompt',
                content: prompt,
                charCount: prompt.length
            });
        }
        if (response) {
            html += renderChatBubble({
                avatar: style.avatar,
                avatarCls: style.party,
                sender: name,
                tag: '回复',
                bubbleCls: style.party,
                content: response,
                charCount: response.length
            });
        }
    }

    if (r.judgeRecord) {
        html += '<div class="chat-divider">整理结果</div>';
        if (r.judgeRecord.success) {
            html += `<div class="chat-msg chat-msg-judge chat-msg-right">
                <div class="chat-avatar chat-avatar-judge">整</div>
                <div class="chat-body">
                    <div class="chat-meta"><span class="chat-sender">本轮整理</span><span class="chat-tag">摘要</span></div>
                    ${wrapChatBubble('chat-bubble-judge', renderChatBubbleContent(r.judgeRecord.analysis || ''), r.judgeRecord.analysis || '')}
                </div>
            </div>`;
        } else {
            html += `<div class="chat-msg chat-msg-judge chat-msg-right">
                <div class="chat-avatar chat-avatar-judge">整</div>
                <div class="chat-body">
                    <div class="chat-meta"><span class="chat-sender">本轮整理</span><span class="chat-tag">失败</span></div>
                    ${wrapChatBubble('chat-bubble-judge chat-bubble-error', renderChatBubbleContent(r.judgeRecord.errorMessage || '整理失败'), r.judgeRecord.errorMessage || '整理失败')}
                    <button class="btn btn-outline btn-sm" style="margin-top:8px;" onclick="retryRoundJudge(${r.roundNumber})">重试整理</button>
                </div>
            </div>`;
        }
    }

    return html;
}

/** 渲染完整讨论流（各轮对话 + 最终整理，不含进度概览）。 */
function renderFullChatFeed(judge) {
    let html = '<div class="chat-feed chat-feed-full">';
    html += `<div class="chat-divider" id="chat-anchor-start">研讨开始</div>`;

    if (!judge || !judge.judgeEnabled) {
        html += '<div class="chat-msg chat-msg-center">';
        html += '<div class="chat-bubble chat-bubble-system">讨论进行中，材料整理尚未开启</div></div>';
    } else if (!judge.rounds?.length) {
        html += '<div class="chat-msg chat-msg-center">';
        html += '<div class="chat-bubble chat-bubble-system">轮次材料整理中，请稍后刷新…</div></div>';
    } else {
        for (const r of judge.rounds) {
            const typeLabel = ROUND_TYPE_LABELS[r.roundType] || r.roundType;
            html += `<div class="chat-divider" id="chat-anchor-round-${r.roundNumber}">第 ${r.roundNumber} 轮 · ${typeLabel}</div>`;
            html += renderRoundChatContent(r);
        }
    }

    if (judge?.finalJudge) {
        html += '<div class="chat-divider" id="chat-anchor-final">最终整理</div>';
        if (judge.finalJudge.success) {
            html += `<div class="chat-msg chat-msg-judge">
                <div class="chat-avatar chat-avatar-judge">终</div>
                <div class="chat-body">
                    <div class="chat-meta"><span class="chat-sender">最终整理</span></div>
                    ${wrapChatBubble('chat-bubble-judge', renderChatBubbleContent(judge.finalJudge.analysis || ''), judge.finalJudge.analysis || '')}
                </div>
            </div>`;
        } else if (judge.finalJudge.errorMessage) {
            html += `<div class="chat-msg chat-msg-judge">
                <div class="chat-avatar chat-avatar-judge">终</div>
                <div class="chat-body">
                    <div class="chat-meta"><span class="chat-sender">最终整理</span><span class="chat-tag">失败</span></div>
                    ${wrapChatBubble('chat-bubble-judge chat-bubble-error', renderChatBubbleContent(judge.finalJudge.errorMessage), judge.finalJudge.errorMessage)}
                </div>
            </div>`;
        } else {
            html += `<div class="chat-msg chat-msg-judge">
                <div class="chat-avatar chat-avatar-judge">终</div>
                <div class="chat-body">
                    <div class="chat-meta"><span class="chat-sender">最终整理</span></div>
                    <div class="chat-bubble chat-bubble-judge">最终整理生成中…</div>
                </div>
            </div>`;
        }
    }

    html += '</div>';
    return html;
}

/** 重试指定轮次的整理（使用本地保存的 API Key）。 */
export async function retryRoundJudge(roundNumber) {
    if (!state.currentSessionId) return;
    const apiKey = document.getElementById('judgeApiKey')?.value?.trim()
        || localStorage.getItem(JUDGE_KEY_STORAGE);
    if (!apiKey) {
        toast('缺少整理服务 API Key，请返回「新建研讨」页填写', 'error');
        return;
    }
    try {
        const res = await fetch(API + '/debates/' + state.currentSessionId + '/judge/rounds/' + roundNumber + '/retry', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ judgeApiKey: apiKey })
        });
        const data = await res.json();
        if (res.ok) {
            toast('第 ' + roundNumber + ' 轮整理已重试', 'success');
            lookupDebate(true);
        } else {
            toast(data.message || '重试失败', 'error');
        }
    } catch (e) {
        toast('重试失败：' + e.message, 'error');
    }
}

/** 打开指定研讨详情页并查询。 */
export function openSession(sessionId) {
    document.getElementById('lookup-id').value = sessionId;
    switchTab('debates');
    lookupDebate();
}

/** 恢复右侧讨论记录区滚动位置。 */
function restoreChatPaneScroll(scrollTop) {
    if (typeof scrollTop !== 'number' || scrollTop < 0) return;
    const apply = () => {
        const pane = document.getElementById('detail-chat-pane');
        if (pane) pane.scrollTop = scrollTop;
    };
    requestAnimationFrame(() => {
        apply();
        setTimeout(apply, 120);
    });
}

/** 查询并渲染研讨详情（左概览 + 右讨论记录）。 */
export async function lookupDebate(silent, options = {}) {
    const sessionId = document.getElementById('lookup-id').value.trim();
    if (!sessionId) { if (!silent) toast('请输入任务编号，可从研讨历史中获取', 'error'); return; }

    const prevChatPane = document.getElementById('detail-chat-pane');
    const chatScrollY = silent && prevChatPane ? prevChatPane.scrollTop : null;
    const activeDetailTab = silent
        ? document.querySelector('.detail-tab.active')?.dataset.detailTab
        : null;

    const el = document.getElementById('debate-detail');
    if (!silent) el.innerHTML = '<div class="loading">加载聊天室…</div>';

    try {
        const [statusRes, judgeRes, docsRes] = await Promise.all([
            fetch(API + '/debates/' + sessionId),
            fetch(API + '/debates/' + sessionId + '/judge'),
            fetch(API + '/debates/' + sessionId + '/documents')
        ]);
        if (!statusRes.ok) {
            stopPoll();
            el.innerHTML = '<div class="callout callout-error">未找到该任务，请检查任务编号是否正确</div>';
            return;
        }
        const status = await statusRes.json();
        const judge = judgeRes.ok ? await judgeRes.json() : null;
        const documents = docsRes.ok ? await docsRes.json() : [];
        state.currentSessionId = sessionId;
        state.currentDocumentList = documents;

        const participants = (status.participants || []).join('、') || '—';
        const thresholdPct = Math.round((status.convergenceThreshold || 0.75) * 100);
        const isPolling = shouldPoll(status.status) || status.postProcessing;

        el.innerHTML = `
            <div class="chat-room">
                <div class="chat-room-header">
                    <div class="chat-room-header-top">
                        ${statusBadge(status.status, status.statusLabel)}
                        <span class="chat-room-members">${escapeHtml(participants)}</span>
                    </div>
                    <div class="chat-room-title">${escapeHtml(status.topic)}</div>
                    <div class="chat-room-chips">
                        <span class="meta-chip">任务编号 ${status.sessionId.slice(0, 8)}…</span>
                        <span class="meta-chip">轮次 ${status.currentRound} / ${status.maxRounds}</span>
                        <span class="meta-chip">收敛 ${thresholdPct}%</span>
                    </div>
                </div>

                <div class="chat-room-tabs">
                    <button class="detail-tab active" data-detail-tab="main" onclick="switchDetailTab('main')">研讨</button>
                    <button class="detail-tab" data-detail-tab="documents" onclick="switchDetailTab('documents')">产出文档</button>
                </div>

                <div class="chat-room-body" id="chat-room-body">
                    <div id="detail-panel-main" class="detail-panel active">
                        <div class="detail-split">
                            <div class="detail-split-overview" id="detail-overview-pane">
                                ${renderOverviewPanel(status, judge)}
                            </div>
                            <div class="detail-split-chat" id="detail-chat-pane">
                                ${renderFullChatFeed(judge)}
                            </div>
                        </div>
                    </div>
                    <div id="detail-panel-documents" class="detail-panel">
                        ${renderDocumentSection(documents)}
                    </div>
                </div>

                <div class="chat-room-footer">
                    <button class="btn btn-outline btn-sm" onclick="lookupDebate()">刷新</button>
                    ${isPolling ? '<span class="polling-badge"><span class="polling-dot"></span>每 3 秒自动刷新</span>' : ''}
                    ${isPolling ? `<button class="btn btn-danger btn-sm" onclick="cancelDebate('${sessionId}')">终止</button>` : ''}
                </div>
            </div>
        `;

        if (documents && documents.length) {
            updateDocumentDescription();
            clearDocumentToc();
        }

        if (isPolling) {
            if (!state.pollTimer) startPoll();
        } else {
            stopPoll();
        }

        savePageState({ tab: 'debates', lookupId: sessionId });

        const tabToRestore = normalizeDetailTab(
            options.restoreDetailTab || activeDetailTab || 'main'
        );
        switchDetailTab(tabToRestore, { skipSave: !options.restoreDetailTab && !!silent });
        bindOverviewClickEvents();
        bindBubbleExpandEvents();

        if (chatScrollY !== null) {
            restoreChatPaneScroll(chatScrollY);
        } else {
            const chatPane = document.getElementById('detail-chat-pane');
            if (chatPane) chatPane.scrollTop = 0;
            if (options.restoreScroll) {
                restoreScrollPosition();
            }
        }
    } catch (e) {
        if (!silent) el.innerHTML = `<div class="callout callout-error">请求失败：${e.message}</div>`;
    }
}

/** 终止进行中的研讨任务。 */
export async function cancelDebate(sessionId) {
    if (!confirm('确定终止该研讨任务？当前轮次完成后停止。')) return;
    try {
        await fetch(API + '/debates/' + sessionId + '/cancel', { method: 'POST' });
        toast('终止请求已发送', 'info');
        lookupDebate();
    } catch (e) {
        toast('操作失败：' + e.message, 'error');
    }
}
