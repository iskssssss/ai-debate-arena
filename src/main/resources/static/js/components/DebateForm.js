import { defineComponent, ref, computed, watch, onMounted, onActivated } from '../vue.js';
import {
    API, DEFAULT_OUTPUT_DOCS, JUDGE_KEY_STORAGE, THRESHOLD_STORAGE,
    PARTICIPANT_NAMES_STORAGE,
    ROUND_TYPE_LABELS, ROUND_TYPE_DETAILS
} from '../config.js';
import { showToast } from '../composables/useToast.js';
import { debounce } from '../composables/useDebounce.js';

/**
 * 生成参与讨论方列表指纹，用于静默刷新时跳过无变化更新。
 * @param {object} channels 通道列表
 * @param {Array} list 参与方列表
 * @returns {string} 指纹字符串
 */
function participantsFingerprint(channels, list) {
    const status = Object.values(channels || {}).map(ch =>
        `${ch.id}:${ch.type}:${ch.ready}:${ch.apiVerified}:${ch.displayName}`
    ).join('|');
    const selection = (list || []).map(p =>
        `${p.channelId}:${p.checked}:${p.alias}`
    ).join('|');
    return status + '||' + selection;
}

/**
 * 新建研讨向导步骤定义。
 */
const WIZARD_STEPS = [
    { id: 'topic', title: '研讨描述', detail: '需求与验收标准' },
    { id: 'params', title: '研讨参数', detail: '轮次、阈值与产出' },
    { id: 'participants', title: '参与讨论方', detail: '选择通道与命名' }
];

/**
 * 新建研讨表单面板。
 */
export default defineComponent({
    name: 'DebateForm',
    props: {
        debateReady: { type: Boolean, default: false }
    },
    emits: ['submit-success', 'need-profiles', 'judge-key-changed'],
    setup(props, { emit }) {
        const topic = ref('');
        const maxRounds = ref(4);
        const thresholdPct = ref(75);
        const judgeMode = ref('API');
        const judgeApiKey = ref('');
        const judgeModel = ref('deepseek-v4-flash');
        const judgeChannel = ref('');
        const outputDocTypes = ref([]);
        const selectedDocs = ref([]);
        const participants = ref([]);
        const profiles = ref({});
        const loadingDocs = ref(true);
        const loadingParticipants = ref(true);
        const submitResult = ref(null);
        const submitting = ref(false);
        const lastParticipantsFingerprint = ref('');
        const globalApiKeyConfigured = ref(false);
        const currentStep = ref(0);
        let participantsInitialLoadDone = false;

        /** 根据轮次编号推断计划轮次类型。 */
        function buildPlannedRoundType(roundNum) {
            if (roundNum <= 1) return 'INITIAL';
            if (roundNum === 2) return 'CRITIQUE';
            return 'REBUTTAL';
        }

        /** 研讨流程步骤列表（响应式）。 */
        const flowSteps = computed(() => {
            const rounds = Math.min(10, Math.max(2, maxRounds.value || 4));
            const steps = [];
            for (let r = 1; r <= rounds; r++) {
                const type = buildPlannedRoundType(r);
                steps.push({
                    title: `第 ${r} 轮 · ${ROUND_TYPE_LABELS[type]}`,
                    detail: ROUND_TYPE_DETAILS[type] || ''
                });
            }
            steps.push({ title: '收敛判定', detail: '每轮后比对方案相似度，达阈值则提前结束' });
            return steps;
        });

        /** 步骤节点样式：已完成 / 当前 / 待填。 */
        function stepNodeClass(index) {
            if (index < currentStep.value) return 'done';
            if (index === currentStep.value) return 'active';
            return '';
        }

        /** 校验指定向导步骤，失败时弹出提示。 */
        function validateStep(stepIndex) {
            if (stepIndex === 0) {
                if (!topic.value.trim()) {
                    showToast('请填写需求描述，包括功能目标、范围边界和验收标准', 'error');
                    return false;
                }
                return true;
            }
            if (stepIndex === 1) {
                if (!selectedDocs.value.length) {
                    showToast('请至少选择 1 份产出文档', 'error');
                    return false;
                }
                if (judgeMode.value === 'API') {
                    const key = judgeApiKey.value.trim();
                    if (!key && !globalApiKeyConfigured.value) {
                        showToast('请填写整理服务 API Key，或在「通道配置」中保存全局 API Key', 'error');
                        return false;
                    }
                } else if (!judgeChannel.value) {
                    showToast('请选择整理通道', 'error');
                    return false;
                }
                return true;
            }
            if (stepIndex === 2) {
                if (!props.debateReady) {
                    showToast('请先在「通道配置」页登录至少 2 个通道', 'error');
                    emit('need-profiles');
                    return false;
                }
                return validateParticipants();
            }
            return true;
        }

        /** 进入下一步（校验当前步骤）。 */
        function nextStep() {
            if (!validateStep(currentStep.value)) return;
            if (currentStep.value < WIZARD_STEPS.length - 1) {
                currentStep.value += 1;
            }
        }

        /** 返回上一步。 */
        function prevStep() {
            if (currentStep.value > 0) currentStep.value -= 1;
        }

        /** 点击步骤条跳转（仅允许回到已完成步骤）。 */
        function goToStep(index) {
            if (index === currentStep.value) return;
            if (index < currentStep.value) {
                currentStep.value = index;
                return;
            }
            for (let i = currentStep.value; i < index; i++) {
                if (!validateStep(i)) return;
            }
            currentStep.value = index;
        }

        /** 拉取通道注册表。 */
        async function fetchChannels() {
            const res = await fetch(API + '/profiles/channels');
            return await res.json();
        }

        /** 加载产出文档类型。 */
        async function loadOutputDocumentTypes() {
            loadingDocs.value = true;
            try {
                const res = await fetch(API + '/debates/output-document-types');
                outputDocTypes.value = await res.json();
                selectedDocs.value = outputDocTypes.value
                    .filter(t => DEFAULT_OUTPUT_DOCS.includes(t.id))
                    .map(t => t.id);
            } catch (e) {
                showToast('加载文档类型失败：' + e.message, 'error');
            } finally {
                loadingDocs.value = false;
            }
        }

        /** 加载参与讨论方选项（支持静默刷新）。 */
        async function loadParticipantOptions(silent = false) {
            if (!silent) loadingParticipants.value = true;
            try {
                const data = await fetchChannels();
                profiles.value = data;
                const savedNames = JSON.parse(localStorage.getItem(PARTICIPANT_NAMES_STORAGE) || '{}');
                const prevMap = Object.fromEntries(participants.value.map(p => [p.channelId, p]));
                const next = Object.values(data).map(ch => ({
                    channelId: ch.id,
                    platform: ch.platform || ch.id.toUpperCase(),
                    channelLabel: ch.displayName,
                    ready: Boolean(ch.ready),
                    type: ch.type,
                    checked: prevMap[ch.id]?.checked ?? true,
                    alias: prevMap[ch.id]?.alias || savedNames[ch.id] || ch.displayName,
                    defaultAlias: ch.displayName
                }));
                const fp = participantsFingerprint(data, next);
                if (silent && fp === lastParticipantsFingerprint.value) return;
                lastParticipantsFingerprint.value = fp;
                participants.value = next;
            } catch (e) {
                if (!silent) showToast('加载通道状态失败：' + e.message, 'error');
            } finally {
                if (!silent) loadingParticipants.value = false;
            }
        }

        /** 填充整理通道下拉选项。 */
        async function populateJudgeChannels() {
            try {
                profiles.value = await fetchChannels();
            } catch { /* 忽略 */ }
        }

        /** 同步收敛阈值到 localStorage（防抖，避免滑块拖动频繁写入）。 */
        const persistThreshold = debounce((pct) => {
            localStorage.setItem(THRESHOLD_STORAGE, String(pct));
        }, 200);

        /** 规范化并同步收敛阈值。 */
        function syncThreshold() {
            let pct = parseInt(thresholdPct.value, 10);
            if (!Number.isFinite(pct)) return;
            pct = Math.min(100, Math.max(50, pct));
            thresholdPct.value = pct;
            persistThreshold(pct);
        }

        /** 保存讨论方名称到本地。 */
        function saveParticipantNames() {
            const names = {};
            participants.value.forEach(p => {
                const v = (p.alias || '').trim();
                if (v) names[p.channelId] = v;
            });
            localStorage.setItem(PARTICIPANT_NAMES_STORAGE, JSON.stringify(names));
        }

        /** 讨论方名称防抖保存。 */
        const debouncedSaveNames = debounce(saveParticipantNames, 300);

        /** 已登录通道列表（用于整理通道选择）。 */
        const loggedInChannels = computed(() =>
            Object.values(profiles.value)
                .filter(ch => ch.type === 'BROWSER' && ch.platform)
                .map(ch => ({
                    key: ch.platform,
                    label: ch.displayName,
                    loggedIn: ch.loginStatus === 'LOGGED_IN'
                }))
        );

        /** 校验讨论方选择。 */
        function validateParticipants() {
            const selected = participants.value.filter(p => p.checked);
            if (selected.length < 2) {
                showToast('请至少勾选 2 个讨论方', 'error');
                return false;
            }
            const notReady = selected.filter(p => !p.ready);
            if (notReady.length) {
                showToast('所选通道未就绪：' + notReady.map(p => p.channelLabel).join('、'), 'error');
                return false;
            }
            const names = selected.map(p => (p.alias || '').trim()).filter(Boolean);
            if (new Set(names).size !== names.length) {
                showToast('讨论方名称不能重复', 'error');
                return false;
            }
            return true;
        }

        /** 拉取通道配置页保存的三方 API 设置。 */
        async function loadApiConfig() {
            try {
                const res = await fetch(API + '/profiles/api-config');
                if (!res.ok) return;
                const data = await res.json();
                globalApiKeyConfigured.value = Boolean(data.apiKeyConfigured);
                if (data.model) judgeModel.value = data.model;
            } catch { /* 忽略 */ }
        }

        /** 提交新建研讨请求。 */
        async function startDebate() {
            if (!validateStep(0) || !validateStep(1) || !validateStep(2)) {
                if (!topic.value.trim()) currentStep.value = 0;
                else if (!selectedDocs.value.length) currentStep.value = 1;
                else currentStep.value = 2;
                return;
            }

            const docs = selectedDocs.value;

            const selected = participants.value.filter(p => p.checked);
            const body = {
                topic: topic.value.trim(),
                maxRounds: maxRounds.value || 4,
                convergenceThreshold: thresholdPct.value / 100,
                judgeEnabled: true,
                outputDocuments: docs,
                participantChannelIds: selected.map(p => p.channelId),
                participantAliases: {}
            };

            selected.forEach(p => {
                const v = (p.alias || '').trim();
                if (v) body.participantAliases[p.channelId] = v;
            });
            saveParticipantNames();

            if (judgeMode.value === 'API') {
                const key = judgeApiKey.value.trim();
                body.judgeMode = 'API';
                if (key) {
                    body.judgeApiKey = key;
                    localStorage.setItem(JUDGE_KEY_STORAGE, key);
                }
                body.judgeModel = judgeModel.value;
            } else {
                body.judgeMode = 'CHANNEL';
                body.judgeChannel = judgeChannel.value;
            }

            submitting.value = true;
            submitResult.value = null;
            try {
                const res = await fetch(API + '/debates', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(body)
                });
                const data = await res.json();
                if (res.ok) {
                    submitResult.value = { ok: true, sessionId: data.sessionId };
                    showToast('研讨已提交', 'success');
                    emit('submit-success', data.sessionId);
                } else {
                    const msg = data.message || data.errorCode || '提交失败';
                    submitResult.value = { ok: false, message: msg };
                    if (data.errorCode === 'INSUFFICIENT_PLATFORMS') {
                        showToast('请先登录至少 2 个通道', 'error');
                        emit('need-profiles');
                    } else {
                        showToast(msg, 'error');
                    }
                }
            } catch (e) {
                submitResult.value = { ok: false, message: e.message };
            } finally {
                submitting.value = false;
            }
        }

        /** 恢复本地保存的设置。 */
        function restoreLocalSettings() {
            const savedKey = localStorage.getItem(JUDGE_KEY_STORAGE);
            if (savedKey) judgeApiKey.value = savedKey;
            const savedPct = parseInt(localStorage.getItem(THRESHOLD_STORAGE), 10);
            if (Number.isFinite(savedPct)) {
                thresholdPct.value = Math.min(100, Math.max(50, savedPct));
            }
        }

        watch(judgeMode, (mode) => {
            if (mode === 'CHANNEL') populateJudgeChannels();
        });

        /** API Key 变更时同步到父组件与本地存储。 */
        const syncJudgeApiKey = debounce((key) => {
            const trimmed = (key || '').trim();
            if (trimmed) localStorage.setItem(JUDGE_KEY_STORAGE, trimmed);
            emit('judge-key-changed', trimmed);
        }, 200);
        watch(judgeApiKey, (key) => syncJudgeApiKey(key));

        /** 讨论方名称变更时防抖写入本地。 */
        watch(participants, () => debouncedSaveNames(), { deep: true });

        /** 勾选/单选后移除焦点，避免焦点环引起视觉跳动。 */
        function blurChoiceInput(event) {
            event.target?.blur();
        }

        onMounted(async () => {
            restoreLocalSettings();
            loadOutputDocumentTypes();
            await loadParticipantOptions();
            await loadApiConfig();
            participantsInitialLoadDone = true;
            populateJudgeChannels();
        });

        /** 页签重新激活时静默刷新参与方登录状态（跳过首次挂载）。 */
        onActivated(() => {
            if (participantsInitialLoadDone) {
                loadParticipantOptions(true);
                loadApiConfig();
            }
        });

        return {
            topic, maxRounds, thresholdPct, judgeMode, judgeApiKey, judgeModel, judgeChannel,
            outputDocTypes, selectedDocs, participants, loadingDocs, loadingParticipants,
            submitResult, submitting, flowSteps, loggedInChannels, globalApiKeyConfigured,
            currentStep, wizardSteps: WIZARD_STEPS,
            syncThreshold, saveParticipantNames, debouncedSaveNames, startDebate, loadParticipantOptions,
            stepNodeClass, nextStep, prevStep, goToStep, blurChoiceInput
        };
    },
    template: `
    <div class="form-layout">
        <div class="card form-main debate-wizard">
            <nav class="stepper" aria-label="新建研讨步骤">
                <template v-for="(step, i) in wizardSteps" :key="step.id">
                    <div class="stepper-item" :class="{ clickable: i <= currentStep }" @click="goToStep(i)">
                        <div class="stepper-node" :class="stepNodeClass(i)">{{ i + 1 }}</div>
                        <div class="stepper-label">
                            <div class="stepper-label-title">{{ step.title }}</div>
                            <div class="stepper-label-detail">{{ step.detail }}</div>
                        </div>
                    </div>
                    <div v-if="i < wizardSteps.length - 1" class="stepper-line" :class="{ done: i < currentStep }"></div>
                </template>
            </nav>

            <div class="wizard-body">
                <!-- 步骤 1：研讨描述 -->
                <section v-show="currentStep === 0" class="wizard-step">
                    <div class="section-label"><span class="section-num">1</span>研讨描述</div>
                    <div class="form-group">
                        <textarea v-model="topic" rows="8" class="wizard-textarea"
                            placeholder="描述功能目标、范围边界、约束条件和验收标准。&#10;&#10;示例：订单超时自动取消——创建后 N 分钟未支付则关闭订单（N 可配置），需支持多实例部署，取消后回滚库存并通知下游。退款流程不在本次范围。"></textarea>
                        <span class="hint">请尽量写清业务场景、边界与验收标准，便于各讨论方独立给出方案</span>
                    </div>
                </section>

                <!-- 步骤 2：研讨参数 -->
                <section v-show="currentStep === 1" class="wizard-step">
                    <div class="section-label"><span class="section-num">2</span>研讨参数</div>
                    <div class="form-row">
                        <div class="form-group">
                            <label>研讨轮数</label>
                            <input v-model.number="maxRounds" type="number" min="2" max="10">
                            <span class="hint">含初始方案、交叉审阅、修订回应</span>
                        </div>
                        <div class="form-group">
                            <label>收敛阈值</label>
                            <div class="threshold-row">
                                <input v-model.number="thresholdPct" type="range" min="50" max="100" step="5" @input="syncThreshold">
                                <input v-model.number="thresholdPct" type="number" min="50" max="100" step="5" @input="syncThreshold">
                                <span class="threshold-unit">%</span>
                            </div>
                            <span class="hint">当所有讨论方的方案相似度均达到此百分比时，研讨自动结束</span>
                        </div>
                    </div>

                    <div class="form-group" style="margin-top:20px;">
                        <label>汇总整理</label>
                        <div class="judge-mode-toggle">
                            <label class="toggle-option">
                                <input type="radio" v-model="judgeMode" value="API" @change="blurChoiceInput">
                                <span class="toggle-label">API 整理</span>
                                <span class="toggle-desc">DeepSeek API</span>
                            </label>
                            <label class="toggle-option">
                                <input type="radio" v-model="judgeMode" value="CHANNEL" @change="blurChoiceInput">
                                <span class="toggle-label">通道整理</span>
                                <span class="toggle-desc">已登录 AI 通道</span>
                            </label>
                        </div>
                    </div>
                    <div class="judge-config-panel">
                    <div v-show="judgeMode === 'API'" class="form-row">
                        <div class="form-group">
                            <label>整理服务 API Key <span v-if="!globalApiKeyConfigured" class="required">*</span></label>
                            <input v-model="judgeApiKey" type="password" placeholder="sk-..." autocomplete="off">
                            <span class="hint">{{ globalApiKeyConfigured ? '已在「通道配置」保存全局 Key，可留空使用；填写则覆盖本场研讨' : '仅保存在浏览器本地，随请求发送，服务端不落盘' }}</span>
                        </div>
                        <div class="form-group">
                            <label>整理模型</label>
                            <select v-model="judgeModel">
                                <option value="deepseek-v4-flash">deepseek-v4-flash</option>
                                <option value="deepseek-v4-pro">deepseek-v4-pro</option>
                            </select>
                        </div>
                    </div>
                    <div v-show="judgeMode === 'CHANNEL'" class="form-group">
                        <label>整理通道 <span class="required">*</span></label>
                        <select v-model="judgeChannel">
                            <option value="">-- 请选择已登录通道 --</option>
                            <option v-for="ch in loggedInChannels" :key="ch.key" :value="ch.key" :disabled="!ch.loggedIn">
                                {{ ch.label }}{{ ch.loggedIn ? '' : '（未登录）' }}
                            </option>
                        </select>
                        <span class="hint">选择已登录的 AI 通道作为整理方</span>
                    </div>
                    </div>

                    <div class="form-group" style="margin-top:20px;">
                        <label>产出文档</label>
                        <div v-if="loadingDocs" class="doc-grid"><div class="loading">加载文档类型…</div></div>
                        <div v-else class="doc-grid">
                            <label v-for="t in outputDocTypes" :key="t.id" class="doc-item"
                                :class="{ 'is-selected': selectedDocs.includes(t.id) }">
                                <input type="checkbox" :value="t.id" v-model="selectedDocs" @change="blurChoiceInput">
                                <span class="doc-item-body">
                                    <span class="doc-item-title">{{ t.label }}</span>
                                    <span class="doc-item-desc">{{ t.description || '' }}</span>
                                </span>
                            </label>
                        </div>
                    </div>
                </section>

                <!-- 步骤 3：参与讨论方 -->
                <section v-show="currentStep === 2" class="wizard-step">
                    <div class="section-label"><span class="section-num">3</span>参与讨论方</div>
                    <div v-if="loadingParticipants" class="participant-grid"><div class="loading">加载通道状态…</div></div>
                    <div v-else class="participant-grid">
                        <div v-for="p in participants" :key="p.channelId"
                            class="participant-item" :class="{ 'is-selected': p.checked }">
                            <input type="checkbox" :id="'participant-' + p.channelId"
                                v-model="p.checked" class="participant-check" @change="blurChoiceInput">
                            <label :for="'participant-' + p.channelId"
                                class="participant-item-main participant-check-label">
                                <div class="participant-item-title">{{ p.channelLabel }}
                                    <span class="hint">（{{ p.type === 'API' ? 'API' : '浏览器' }}）</span>
                                </div>
                                <div class="participant-item-desc">{{ p.ready ? '已就绪，可参与' : '未就绪，请先在通道配置页完成设置' }}</div>
                            </label>
                            <div class="participant-item-name">
                                <span class="field-label-sm">讨论方名称</span>
                                <input type="text" maxlength="24" v-model="p.alias"
                                    :disabled="!p.checked" :placeholder="p.defaultAlias"
                                    @click.stop @mousedown.stop>
                            </div>
                        </div>
                    </div>
                    <span class="hint">至少勾选 2 个讨论方；名称将用于提示词与研讨记录展示</span>
                </section>
            </div>

            <div class="wizard-actions">
                <button v-if="currentStep > 0" class="btn btn-outline" @click="prevStep">上一步</button>
                <div class="wizard-actions-end">
                    <button v-if="currentStep < wizardSteps.length - 1" class="btn btn-primary" @click="nextStep">下一步</button>
                    <button v-else class="btn btn-primary" :disabled="submitting" @click="startDebate">
                        {{ submitting ? '提交中…' : '提交研讨' }}
                    </button>
                </div>
            </div>

            <div v-if="submitResult" class="wizard-result">
                <div v-if="submitResult.ok" class="callout callout-success">
                    研讨已提交。任务编号：<code style="font-size:12px;">{{ submitResult.sessionId }}</code>
                </div>
                <span v-else style="color:var(--error);">提交失败：{{ submitResult.message }}</span>
            </div>
        </div>

        <aside class="form-aside card">
            <h3 class="aside-title">研讨流程</h3>
            <ol class="flow-steps">
                <li v-for="(step, i) in flowSteps" :key="i">
                    <strong>{{ step.title }}</strong>
                    <span>{{ step.detail }}</span>
                </li>
            </ol>
            <p class="hint" style="margin-top:16px;">研讨结束后自动生成勾选的产出文档，可在「研讨历史」页预览与下载。</p>
        </aside>
    </div>
    `
});
