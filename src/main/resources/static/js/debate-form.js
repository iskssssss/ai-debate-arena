import { API, DEFAULT_OUTPUT_DOCS, JUDGE_KEY_STORAGE, THRESHOLD_STORAGE, CHANNEL_LABELS } from './config.js';
import { state } from './state.js';
import { toast, escapeHtml } from './utils.js';
import { isDebateReady, fetchProfiles } from './profiles.js';
import { switchTab } from './navigation.js';
import { loadHistory } from './history.js';

/** 加载产出文档类型并渲染勾选列表。 */
export async function loadOutputDocumentTypes() {
    const el = document.getElementById('output-doc-options');
    try {
        const res = await fetch(API + '/debates/output-document-types');
        state.outputDocumentTypes = await res.json();
        el.innerHTML = state.outputDocumentTypes.map(t => {
            const checked = DEFAULT_OUTPUT_DOCS.includes(t.id) ? 'checked' : '';
            return `<label class="doc-item"><input type="checkbox" name="output-doc" value="${t.id}" ${checked}>
                <span class="doc-item-body">
                    <span class="doc-item-title">${escapeHtml(t.label)}</span>
                    <span class="doc-item-desc">${escapeHtml(t.description || '')}</span>
                </span></label>`;
        }).join('');
    } catch (e) {
        el.innerHTML = `<div style="color:var(--error);font-size:13px;">加载失败：${e.message}</div>`;
    }
}

/** 获取当前勾选的产出文档类型 ID 列表。 */
export function getSelectedOutputDocuments() {
    return Array.from(document.querySelectorAll('input[name="output-doc"]:checked')).map(el => el.value);
}

/** 读取收敛阈值（0~1 小数）。 */
export function getConvergenceThreshold() {
    const pct = parseInt(document.getElementById('thresholdPctInput').value, 10);
    const normalized = Number.isFinite(pct) ? Math.min(100, Math.max(50, pct)) : 75;
    return normalized / 100;
}

/** 滑块变更时同步数字输入框。 */
export function syncThresholdFromRange() {
    const range = document.getElementById('thresholdPct');
    const input = document.getElementById('thresholdPctInput');
    input.value = range.value;
    localStorage.setItem(THRESHOLD_STORAGE, range.value);
}

/** 数字输入框变更时同步滑块。 */
export function syncThresholdFromInput() {
    const range = document.getElementById('thresholdPct');
    const input = document.getElementById('thresholdPctInput');
    let pct = parseInt(input.value, 10);
    if (!Number.isFinite(pct)) return;
    pct = Math.min(100, Math.max(50, pct));
    input.value = pct;
    range.value = pct;
    localStorage.setItem(THRESHOLD_STORAGE, String(pct));
}

/** 恢复上次使用的收敛阈值（百分比）。 */
export function restoreThresholdPct() {
    const saved = parseInt(localStorage.getItem(THRESHOLD_STORAGE), 10);
    if (!Number.isFinite(saved)) return;
    const pct = Math.min(100, Math.max(50, saved));
    document.getElementById('thresholdPct').value = pct;
    document.getElementById('thresholdPctInput').value = pct;
}

/** 切换整理方式（API / 通道）的字段可见性。 */
export async function onJudgeModeChange() {
    const mode = document.querySelector('input[name="judgeMode"]:checked')?.value || 'API';
    const apiFields = document.getElementById('judge-api-fields');
    const channelFields = document.getElementById('judge-channel-fields');
    const apiKeyRequired = document.getElementById('api-key-required');

    if (mode === 'API') {
        apiFields.style.display = '';
        channelFields.style.display = 'none';
        if (apiKeyRequired) apiKeyRequired.style.display = '';
    } else {
        apiFields.style.display = 'none';
        channelFields.style.display = '';
        if (apiKeyRequired) apiKeyRequired.style.display = 'none';
        // 动态填充已登录通道选项
        await populateJudgeChannelOptions();
    }
}

/** 从 profiles 缓存填充整理通道下拉选项。 */
async function populateJudgeChannelOptions() {
    const select = document.getElementById('judgeChannel');
    if (!select) return;
    const currentValue = select.value;
    try {
        const profiles = await fetchProfiles();
        let html = '<option value="">-- 请选择已登录通道 --</option>';
        for (const [key, p] of Object.entries(profiles)) {
            const label = CHANNEL_LABELS[key] || key;
            const loggedIn = p.loginStatus === 'LOGGED_IN';
            const disabled = loggedIn ? '' : ' disabled';
            const note = loggedIn ? '' : '（未登录）';
            html += `<option value="${key.toUpperCase()}"${disabled}>${label}${note}</option>`;
        }
        select.innerHTML = html;
        if (currentValue) select.value = currentValue;
    } catch (e) {
        select.innerHTML = '<option value="">-- 加载失败 --</option>';
    }
}

/** 提交新建研讨请求。 */
export async function startDebate() {
    if (!(await isDebateReady())) {
        toast('请先在「通道配置」页登录至少 2 个通道', 'error');
        switchTab('profiles', { skipReadyCheck: true });
        return;
    }
    const topic = document.getElementById('topic').value.trim();
    if (!topic) { toast('请填写需求描述，包括功能目标、范围边界和验收标准', 'error'); return; }
    const maxRounds = parseInt(document.getElementById('maxRounds').value) || 4;
    const convergenceThreshold = getConvergenceThreshold();
    const outputDocuments = getSelectedOutputDocuments();

    if (!outputDocuments.length) {
        toast('请至少选择 1 份产出文档', 'error'); return;
    }

    // 根据整理方式构建请求体
    const judgeMode = document.querySelector('input[name="judgeMode"]:checked')?.value || 'API';
    const body = { topic, maxRounds, convergenceThreshold, judgeEnabled: true, outputDocuments };

    if (judgeMode === 'API') {
        const judgeApiKey = document.getElementById('judgeApiKey').value.trim();
        if (!judgeApiKey) {
            toast('请填写整理服务 API Key', 'error'); return;
        }
        const judgeModel = document.getElementById('judgeModel').value;
        body.judgeMode = 'API';
        body.judgeApiKey = judgeApiKey;
        body.judgeModel = judgeModel;
        localStorage.setItem(JUDGE_KEY_STORAGE, judgeApiKey);
    } else {
        const judgeChannel = document.getElementById('judgeChannel').value;
        if (!judgeChannel) {
            toast('请选择整理通道', 'error'); return;
        }
        body.judgeMode = 'CHANNEL';
        body.judgeChannel = judgeChannel;
    }

    const el = document.getElementById('debate-result');
    el.innerHTML = '<span style="color:var(--text-secondary);">提交中…</span>';

    try {
        const res = await fetch(API + '/debates', {
            method: 'POST', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body)
        });
        const data = await res.json();
        if (res.ok) {
            el.innerHTML = `<div class="callout callout-success">
                研讨已提交。任务编号：<code style="font-size:12px;">${data.sessionId}</code>
                <div style="margin-top:12px;">
                    <button class="btn btn-outline btn-sm" onclick="openSession('${data.sessionId}')">查看进度</button>
                </div>
            </div>`;
            toast('研讨已提交', 'success');
            loadHistory(true);
        } else {
            const msg = data.message || data.errorCode || '提交失败';
            el.innerHTML = `<span style="color:var(--error);">提交失败：${msg}</span>`;
            if (data.errorCode === 'INSUFFICIENT_PLATFORMS') {
                toast('请先登录至少 2 个通道', 'error');
                switchTab('profiles', { skipReadyCheck: true });
            } else {
                toast(msg, 'error');
            }
        }
    } catch (e) {
        el.innerHTML = `<span style="color:var(--error);">请求失败：${e.message}</span>`;
    }
}
