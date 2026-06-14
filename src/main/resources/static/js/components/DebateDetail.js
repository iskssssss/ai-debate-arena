import { defineComponent, ref, shallowRef, computed, watch, onUnmounted, onActivated, onDeactivated, nextTick } from '../vue.js';
import {
    API, JUDGE_KEY_STORAGE, ROUND_TYPE_LABELS, DOC_STATUS_LABELS
} from '../config.js';
import { showToast } from '../composables/useToast.js';
import { statusBadgeClass, displayTopic, copyToClipboard } from '../utils.js';
import { renderMarkdown, clearMarkdownCache } from '../composables/useMarkdown.js';
import { sessionFingerprint, debounce } from '../composables/useDebounce.js';
import {
    DEFAULT_DOC_LAYOUT, getDocumentLayout, saveDocumentLayout, layoutToClasses
} from '../composables/useDocumentLayout.js';
import { savePageState, saveChatScroll, getChatScroll } from '../composables/usePageState.js';
import { buildMarkdownToc, scrollToTocHeading } from '../composables/useMarkdownToc.js';
import ChatBubble from './ChatBubble.js';
import OverviewStep from './OverviewStep.js';

/** 判断研讨是否仍在进行中（需轮询）。 */
function shouldPoll(status) {
    return !['CONVERGED', 'MAX_ROUNDS', 'FAILED'].includes(status);
}

/** 根据讨论方名称推断聊天室展示样式。 */
function getSpeakerStyle(name) {
    const n = name || '';
    if (/甲|chatgpt/i.test(n)) return { avatar: n.charAt(0) || '甲', party: 'party-a' };
    if (/乙|deepseek/i.test(n)) return { avatar: n.charAt(0) || '乙', party: 'party-b' };
    if (/丙|gemini/i.test(n)) return { avatar: n.charAt(0) || '丙', party: 'party-c' };
    return { avatar: n.charAt(0) || '?', party: 'party-default' };
}

/** 合并并排序本轮参与方名称。 */
function sortSpeakers(prompts, responses, participantOrder) {
    const names = new Set([...Object.keys(prompts || {}), ...Object.keys(responses || {})]);
    if (participantOrder?.length) {
        const order = participantOrder.filter(n => names.has(n));
        const rest = [...names].filter(n => !order.includes(n)).sort((a, b) => a.localeCompare(b, 'zh-CN'));
        return [...order, ...rest];
    }
    const priority = ['讨论方甲', '讨论方乙', '讨论方丙'];
    return [...names].sort((a, b) => {
        const ia = priority.indexOf(a), ib = priority.indexOf(b);
        if (ia !== -1 || ib !== -1) return (ia === -1 ? 99 : ia) - (ib === -1 ? 99 : ib);
        return a.localeCompare(b, 'zh-CN');
    });
}

/** 解析概览步骤对应的讨论记录锚点 ID。 */
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
 * 研讨详情面板：进度概览、讨论记录、产出文档。
 */
export default defineComponent({
    name: 'DebateDetail',
    components: { ChatBubble, OverviewStep },
    props: {
        sessionId: { type: String, default: '' },
        judgeApiKey: { type: String, default: '' },
        /** 当前页签是否可见，不可见时暂停轮询以节省资源。 */
        visible: { type: Boolean, default: true },
        /** 嵌入历史页时隐藏顶部搜索栏。 */
        embedded: { type: Boolean, default: false }
    },
    emits: ['bubble-open'],
    setup(props, { emit, expose }) {
        const lookupId = ref(props.sessionId || '');
        const loading = ref(false);
        const error = ref('');
        const status = shallowRef(null);
        const judge = shallowRef(null);
        const documentList = ref([]);
        const lastFingerprint = ref('');
        const detailTab = ref('main');
        const selectedAnchor = ref(null);
        const pollTimer = ref(null);

        // 文档预览相关
        const selectedDocType = ref('');
        const previewHtml = ref('');
        const previewMessage = ref('选择文档后点击预览');
        const previewLoading = ref(false);
        const docLayout = ref({ ...DEFAULT_DOC_LAYOUT });
        const tocItems = ref([]);
        const activeTocId = ref('');

        const chatPaneRef = ref(null);
        /** 概览时间线是否展开，默认折叠以留出讨论记录空间。 */
        const overviewExpanded = ref(false);

        const isPolling = computed(() =>
            status.value && (shouldPoll(status.value.status) || status.value.postProcessing)
        );

        const participants = computed(() =>
            (status.value?.participants || []).join('、') || '—'
        );

        const thresholdPct = computed(() =>
            Math.round((status.value?.convergenceThreshold || 0.75) * 100)
        );

        /** 详情页展示的纯文本主题（去除 Markdown）。 */
        const topicTitle = computed(() => displayTopic(status.value?.topic, 200));

        /** 聊天区滚动位置防抖保存。 */
        const debouncedSaveChatScroll = debounce(() => {
            const id = lookupId.value.trim();
            if (id && chatPaneRef.value) {
                saveChatScroll(id, chatPaneRef.value.scrollTop);
            }
        }, 200);

        /** 聊天区滚动时保存位置，便于切换记录后恢复。 */
        function onChatScroll() {
            debouncedSaveChatScroll();
        }

        /** 复制完整任务编号到剪贴板。 */
        async function copySessionId() {
            const id = status.value?.sessionId;
            if (!id) return;
            const ok = await copyToClipboard(id);
            showToast(ok ? '任务编号已复制' : '复制失败', ok ? 'success' : 'error');
        }

        /** 概览时间线步骤（含锚点）。 */
        const overviewSteps = computed(() => {
            if (!status.value?.steps?.length) return [];
            const activeStep = status.value.steps.find(s => s.state === 'active');
            return status.value.steps.map((step, i) => ({
                ...step,
                index: i,
                isLast: i === status.value.steps.length - 1,
                indexText: step.state === 'done' ? '✓' : String(i + 1),
                anchor: resolveChatAnchor(step, status.value, judge.value),
                isActiveStep: step === activeStep
            }));
        });

        /** 聊天流各轮数据。 */
        const chatRounds = computed(() => {
            if (!judge.value?.rounds?.length) return [];
            return judge.value.rounds.map(r => ({
                ...r,
                typeLabel: ROUND_TYPE_LABELS[r.roundType] || r.roundType,
                speakers: sortSpeakers(r.prompts, r.responses, status.value?.participants).map(name => {
                    const style = getSpeakerStyle(name);
                    return {
                        name,
                        style,
                        prompt: r.prompts?.[name] || null,
                        response: r.responses?.[name] || null
                    };
                })
            }));
        });

        const layoutClasses = computed(() => layoutToClasses(docLayout.value));

        /** 当前选中文档的用途说明。 */
        const selectedDocDescription = computed(() =>
            documentList.value.find(d => d.type === selectedDocType.value)?.description || ''
        );

        /** 停止自动轮询。 */
        function stopPoll() {
            if (pollTimer.value) {
                clearInterval(pollTimer.value);
                pollTimer.value = null;
            }
        }

        /** 启动每 3 秒自动刷新（页签不可见时不启动）。 */
        function startPoll() {
            stopPoll();
            if (!props.visible) return;
            pollTimer.value = setInterval(() => lookup(true), 3000);
        }

        /** 切换概览时间线展开/折叠。 */
        function toggleOverview(event) {
            event?.currentTarget?.blur();
            overviewExpanded.value = !overviewExpanded.value;
        }

        /** 切换研讨详情子标签。 */
        function switchTab(tab, event) {
            event?.currentTarget?.blur();
            detailTab.value = tab === 'documents' ? 'documents' : 'main';
            savePageState({ detailTab: detailTab.value });
        }

        /** 点击概览节点定位讨论记录。 */
        function scrollToAnchor(anchorId, event) {
            event?.currentTarget?.blur();
            if (anchorId === '__documents__') {
                switchTab('documents');
                return;
            }
            switchTab('main');
            selectedAnchor.value = anchorId;
            nextTick(() => {
                const pane = chatPaneRef.value;
                const anchor = document.getElementById(anchorId);
                if (!pane || !anchor) {
                    showToast('该节点暂无对应讨论记录', 'info');
                    return;
                }
                const top = anchor.getBoundingClientRect().top
                    - pane.getBoundingClientRect().top + pane.scrollTop - 12;
                pane.scrollTo({ top: Math.max(0, top), behavior: 'smooth' });
                anchor.classList.add('chat-anchor-highlight');
                setTimeout(() => anchor.classList.remove('chat-anchor-highlight'), 1600);
            });
        }

        /** 打开放大气泡弹窗。 */
        function openBubble(payload) {
            emit('bubble-open', payload);
        }

        /** 应用接口返回的研讨数据（静默轮询时跳过无变化更新）。 */
        function applySessionData(newStatus, newJudge, newDocs, silent) {
            const fp = sessionFingerprint(newStatus, newJudge);
            if (silent && fp === lastFingerprint.value) return false;
            lastFingerprint.value = fp;
            status.value = newStatus;
            judge.value = newJudge;
            documentList.value = newDocs;
            return true;
        }

        /** 查询并渲染研讨详情。 */
        async function lookup(silent = false) {
            const sessionId = lookupId.value.trim();
            if (!sessionId) {
                if (!silent) showToast('请输入任务编号，可从研讨历史中获取', 'error');
                return;
            }

            const prevScroll = silent && chatPaneRef.value ? chatPaneRef.value.scrollTop : null;
            if (!silent) {
                loading.value = true;
                error.value = '';
            }

            try {
                const [statusRes, judgeRes, docsRes] = await Promise.all([
                    fetch(API + '/debates/' + sessionId),
                    fetch(API + '/debates/' + sessionId + '/judge'),
                    fetch(API + '/debates/' + sessionId + '/documents')
                ]);
                if (!statusRes.ok) {
                    stopPoll();
                    error.value = '未找到该任务，请检查任务编号是否正确';
                    status.value = null;
                    return;
                }
                const newStatus = await statusRes.json();
                const newJudge = judgeRes.ok ? await judgeRes.json() : null;
                const newDocs = docsRes.ok ? await docsRes.json() : [];
                const changed = applySessionData(newStatus, newJudge, newDocs, silent);
                if (silent && !changed) return;

                if (documentList.value.length && !selectedDocType.value) {
                    selectedDocType.value = documentList.value[0].type;
                    syncDocLayout();
                }

                // 首次加载时自动预览首个产出文档
                if (documentList.value.length && selectedDocType.value
                    && !previewHtml.value && previewMessage.value === '选择文档后点击预览') {
                    previewDocument();
                }

                savePageState({ tab: 'history', lookupId: sessionId });

                if (isPolling.value) {
                    if (!pollTimer.value) startPoll();
                } else {
                    stopPoll();
                }

                await nextTick();
                if (prevScroll !== null && chatPaneRef.value) {
                    chatPaneRef.value.scrollTop = prevScroll;
                } else if (!silent && chatPaneRef.value) {
                    const saved = getChatScroll(sessionId);
                    if (typeof saved === 'number') {
                        chatPaneRef.value.scrollTop = saved;
                    }
                }
            } catch (e) {
                if (!silent) error.value = '请求失败：' + e.message;
            } finally {
                loading.value = false;
            }
        }

        /** 终止进行中的研讨任务。 */
        async function cancelDebate() {
            const sessionId = lookupId.value.trim();
            if (!confirm('确定终止该研讨任务？当前轮次完成后停止。')) return;
            try {
                await fetch(API + '/debates/' + sessionId + '/cancel', { method: 'POST' });
                showToast('终止请求已发送', 'info');
                lookup();
            } catch (e) {
                showToast('操作失败：' + e.message, 'error');
            }
        }

        /** 重试指定轮次的整理。 */
        async function retryRoundJudge(roundNumber) {
            const sessionId = lookupId.value.trim();
            const apiKey = props.judgeApiKey?.trim() || localStorage.getItem(JUDGE_KEY_STORAGE) || '';
            const body = apiKey ? { judgeApiKey: apiKey } : {};
            try {
                const res = await fetch(API + '/debates/' + sessionId + '/judge/rounds/' + roundNumber + '/retry', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(body)
                });
                const data = await res.json();
                if (res.ok) {
                    showToast('第 ' + roundNumber + ' 轮整理已重试', 'success');
                    lookup(true);
                } else {
                    showToast(data.message || '重试失败', 'error');
                }
            } catch (e) {
                showToast('重试失败：' + e.message, 'error');
            }
        }

        /** 同步当前文档排版配置到控件。 */
        function syncDocLayout() {
            if (!selectedDocType.value) return;
            docLayout.value = { ...getDocumentLayout(selectedDocType.value) };
        }

        /** 保存排版配置并应用。 */
        function saveLayout() {
            if (!selectedDocType.value) return;
            saveDocumentLayout(selectedDocType.value, docLayout.value);
        }

        /** 恢复默认排版。 */
        function resetLayout() {
            if (!selectedDocType.value) return;
            docLayout.value = { ...DEFAULT_DOC_LAYOUT };
            saveDocumentLayout(selectedDocType.value, docLayout.value);
        }

        /** 根据标题生成目录。 */
        function buildTocFromPreview(el) {
            const items = buildMarkdownToc(el);
            tocItems.value = items;
            if (items.length) activeTocId.value = items[0].id;
        }

        /** 预览当前选中的产出文档。 */
        async function previewDocument() {
            const sessionId = lookupId.value.trim();
            const type = selectedDocType.value;
            if (!sessionId || !type) return;
            previewLoading.value = true;
            previewMessage.value = '加载中…';
            previewHtml.value = '';
            try {
                const res = await fetch(API + '/debates/' + sessionId + '/documents/' + type);
                const text = await res.text();
                if (!res.ok) {
                    previewMessage.value = '加载失败：' + text;
                    tocItems.value = [];
                    return;
                }
                previewHtml.value = renderMarkdown(text);
                previewMessage.value = '';
                await nextTick();
                const el = document.getElementById('document-preview');
                buildTocFromPreview(el);
            } catch (e) {
                previewMessage.value = '加载失败：' + e.message;
                tocItems.value = [];
            } finally {
                previewLoading.value = false;
            }
        }

        /** 下载当前选中的产出文档。 */
        function downloadDocument() {
            const sessionId = lookupId.value.trim();
            const type = selectedDocType.value;
            if (!sessionId || !type) return;
            const item = documentList.value.find(d => d.type === type);
            window.open(API + '/debates/' + sessionId + '/documents/' + type, '_blank');
            showToast('正在下载：' + (item?.title || type), 'info');
        }

        /** 点击目录项滚动到标题。 */
        function scrollToHeading(id, event) {
            event?.preventDefault();
            event?.currentTarget?.blur();
            scrollToTocHeading(id, document.getElementById('document-preview'));
            activeTocId.value = id;
        }

        /** 外部设置任务编号并查询（唯一的数据加载入口）。 */
        function openSession(sessionId) {
            if (lookupId.value !== sessionId) {
                clearMarkdownCache();
                lastFingerprint.value = '';
                previewHtml.value = '';
                previewMessage.value = '选择文档后点击预览';
                selectedAnchor.value = null;
            }
            lookupId.value = sessionId;
            return lookup();
        }

        /** 仅同步父组件传入的任务编号，不触发查询（避免与 openSession 重复请求）。 */
        watch(() => props.sessionId, (id) => {
            if (id && id !== lookupId.value) {
                lookupId.value = id;
            }
        });

        watch(selectedDocType, (type, oldType) => {
            syncDocLayout();
            if (oldType && type && type !== oldType && lookupId.value.trim() && status.value) {
                previewDocument();
            }
        });

        /** 页签可见性变化时暂停/恢复轮询。 */
        watch(() => props.visible, (v) => {
            if (v && isPolling.value) startPoll();
            else stopPoll();
        });

        onActivated(() => {
            if (props.visible && isPolling.value) startPoll();
        });

        onDeactivated(stopPoll);

        onUnmounted(stopPoll);

        expose({ openSession, lookup, switchTab });

        return {
            lookupId, loading, error, status, judge, documentList, detailTab,
            selectedAnchor, isPolling, participants, thresholdPct, overviewExpanded, topicTitle,
            overviewSteps, chatRounds, chatPaneRef,
            selectedDocType, selectedDocDescription, previewHtml, previewMessage, previewLoading,
            docLayout, tocItems, activeTocId, layoutClasses,
            ROUND_TYPE_LABELS, DOC_STATUS_LABELS, statusBadgeClass,
            renderMarkdown, switchTab, scrollToAnchor, openBubble, lookup, toggleOverview, onChatScroll,
            cancelDebate, retryRoundJudge, saveLayout, resetLayout, copySessionId,
            previewDocument, downloadDocument, scrollToHeading
        };
    },
    template: `
    <div class="card card-chat" :class="{ 'card-chat-embedded': embedded }">
        <div v-if="!embedded" class="chat-search-bar">
            <input v-model="lookupId" placeholder="输入任务编号查看研讨详情…" @keyup.enter="lookup()">
            <button class="btn btn-primary btn-sm" @click="lookup()">进入</button>
        </div>

        <div v-if="loading" class="detail-area"><div class="loading">加载聊天室…</div></div>
        <div v-else-if="error" class="detail-area"><div class="callout callout-error">{{ error }}</div></div>
        <div v-else-if="!status" class="detail-area">
            <div class="empty-state">
                <div class="empty-icon">💬</div>
                <p>{{ embedded ? '从左侧选择研讨记录' : '输入任务编号，查看多轮研讨过程与产出文档' }}</p>
            </div>
        </div>
        <div v-else class="detail-area">
            <div class="chat-room">
                <div class="chat-room-header">
                    <div class="chat-room-header-top">
                        <span class="badge" :class="statusBadgeClass(status.status)">{{ status.statusLabel || status.status }}</span>
                        <span class="chat-room-members">{{ participants }}</span>
                    </div>
                    <div class="chat-room-title">{{ topicTitle }}</div>
                    <div class="chat-room-chips">
                        <span class="meta-chip meta-chip-copy">
                            任务编号 {{ status.sessionId.slice(0, 8) }}…
                            <button type="button" class="meta-chip-btn" title="复制完整任务编号"
                                @click="copySessionId">复制</button>
                        </span>
                        <span class="meta-chip">轮次 {{ status.currentRound }} / {{ status.maxRounds }}</span>
                        <span class="meta-chip">收敛 {{ thresholdPct }}%</span>
                    </div>
                </div>

                <div class="chat-room-tabs">
                    <button class="detail-tab" :class="{ active: detailTab === 'main' }" @click="switchTab('main', $event)">研讨</button>
                    <button class="detail-tab" :class="{ active: detailTab === 'documents' }" @click="switchTab('documents', $event)">产出文档</button>
                    <button v-if="detailTab === 'main'" type="button"
                        class="overview-tab-toggle"
                        :class="{ expanded: overviewExpanded }"
                        :title="overviewExpanded ? '收起左侧进度时间线' : '展开左侧进度时间线'"
                        @click="toggleOverview($event)">
                        <svg viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.6" aria-hidden="true"><path d="M7 4v12M13 4v12M4 7h12M4 13h12"/></svg>
                        {{ overviewExpanded ? '收起进度' : '研讨进度' }}
                    </button>
                </div>

                <div class="chat-room-body">
                    <!-- 研讨主视图 -->
                    <div v-show="detailTab === 'main'" id="detail-panel-main" class="detail-panel">
                        <div class="detail-split" :class="{ 'overview-collapsed': !overviewExpanded }">
                            <div class="detail-split-overview">
                                <div class="overview-panel">
                                    <div class="overview-panel-head">
                                        <span class="overview-panel-title">研讨进度</span>
                                        <button type="button" class="btn btn-outline btn-sm overview-collapse-btn"
                                            @click="toggleOverview($event)">
                                            {{ overviewExpanded ? '收起' : '展开' }}
                                        </button>
                                    </div>
                                    <div v-if="status.currentPhase" class="overview-phase">{{ status.currentPhase }}</div>
                                    <div v-if="overviewSteps.length" class="overview-timeline">
                                        <OverviewStep v-for="step in overviewSteps" :key="step.id || step.index"
                                            :step="step" :selected-anchor="selectedAnchor"
                                            @anchor-click="scrollToAnchor" />
                                    </div>
                                    <div v-else class="empty-state"><p>暂无进度信息</p></div>
                                    <div v-if="status.failureReason && status.status === 'FAILED'" class="callout callout-error overview-error">{{ status.failureReason }}</div>
                                    <div v-else-if="status.platformFailures?.length" class="callout callout-warning overview-error">
                                        <strong>部分讨论方未能参与</strong>（研讨已降级继续）<br>
                                        <span v-for="(f, fi) in status.platformFailures" :key="fi">{{ f }}<br v-if="fi < status.platformFailures.length - 1"></span>
                                    </div>
                                </div>
                            </div>
                            <div class="detail-split-chat" ref="chatPaneRef" @scroll="onChatScroll">
                                <div class="chat-feed chat-feed-full">
                                    <div class="chat-divider" id="chat-anchor-start">研讨开始</div>

                                    <template v-if="!judge || !judge.judgeEnabled">
                                        <div class="chat-msg chat-msg-center">
                                            <div class="chat-bubble chat-bubble-system">讨论进行中，材料整理尚未开启</div>
                                        </div>
                                    </template>
                                    <template v-else-if="!chatRounds.length">
                                        <div class="chat-msg chat-msg-center">
                                            <div class="chat-bubble chat-bubble-system">轮次材料整理中，请稍后刷新…</div>
                                        </div>
                                    </template>
                                    <template v-else>
                                        <template v-for="r in chatRounds" :key="r.roundNumber">
                                            <div class="chat-divider" :id="'chat-anchor-round-' + r.roundNumber">第 {{ r.roundNumber }} 轮 · {{ r.typeLabel }}</div>
                                            <template v-for="sp in r.speakers" :key="r.roundNumber + '-' + sp.name">
                                                <ChatBubble v-if="sp.prompt"
                                                    :sender="sp.name" tag="发送内容" :char-count="sp.prompt.length"
                                                    :content="sp.prompt" :avatar="sp.style.avatar" :avatar-party="sp.style.party"
                                                    bubble-class="chat-bubble-prompt" @expand="openBubble" />
                                                <ChatBubble v-if="sp.response"
                                                    :sender="sp.name" tag="回复" :char-count="sp.response.length"
                                                    :content="sp.response" :avatar="sp.style.avatar" :avatar-party="sp.style.party"
                                                    :bubble-class="'chat-bubble-' + sp.style.party" @expand="openBubble" />
                                            </template>
                                            <template v-if="r.judgeRecord">
                                                <div class="chat-divider">整理结果</div>
                                                <ChatBubble v-if="r.judgeRecord.success"
                                                    sender="本轮整理" tag="摘要" :content="r.judgeRecord.analysis || ''"
                                                    avatar="整" avatar-party="judge-right" bubble-class="chat-bubble-judge"
                                                    @expand="openBubble" />
                                                <ChatBubble v-else
                                                    sender="本轮整理" tag="失败" :content="r.judgeRecord.errorMessage || '整理失败'"
                                                    avatar="整" avatar-party="judge-right" bubble-class="chat-bubble-judge chat-bubble-error"
                                                    @expand="openBubble">
                                                    <template #footer>
                                                        <button class="btn btn-outline btn-sm" style="margin-top:8px;" @click="retryRoundJudge(r.roundNumber)">重试整理</button>
                                                    </template>
                                                </ChatBubble>
                                            </template>
                                        </template>
                                    </template>

                                    <template v-if="judge?.finalJudge">
                                        <div class="chat-divider" id="chat-anchor-final">最终整理</div>
                                        <ChatBubble v-if="judge.finalJudge.success"
                                            sender="最终整理" :content="judge.finalJudge.analysis || ''"
                                            avatar="终" avatar-party="judge" bubble-class="chat-bubble-judge"
                                            :expandable="true" @expand="openBubble" />
                                        <ChatBubble v-else-if="judge.finalJudge.errorMessage"
                                            sender="最终整理" tag="失败" :content="judge.finalJudge.errorMessage"
                                            avatar="终" avatar-party="judge" bubble-class="chat-bubble-judge chat-bubble-error"
                                            @expand="openBubble" />
                                        <ChatBubble v-else sender="最终整理" avatar="终" avatar-party="judge"
                                            bubble-class="chat-bubble-judge" :expandable="false">
                                            最终整理生成中…
                                        </ChatBubble>
                                    </template>
                                </div>
                            </div>
                        </div>
                    </div>

                    <!-- 产出文档 -->
                    <div v-show="detailTab === 'documents'" id="detail-panel-documents" class="detail-panel">
                        <div v-if="!documentList.length" class="empty-state">
                            <p>本场研讨未配置产出文档</p>
                            <p class="hint">发起研讨时可勾选需要生成的文档类型</p>
                        </div>
                        <template v-else>
                            <div class="doc-toolbar">
                                <select v-model="selectedDocType">
                                    <option v-for="d in documentList" :key="d.type" :value="d.type">
                                        {{ d.title }}（{{ DOC_STATUS_LABELS[d.status] || d.status }}）
                                    </option>
                                </select>
                                <button class="btn btn-outline btn-sm" :disabled="previewLoading" @click="previewDocument">
                                    {{ previewLoading ? '加载中…' : '预览' }}
                                </button>
                                <button class="btn btn-primary btn-sm" @click="downloadDocument">下载</button>
                            </div>
                            <p class="hint" style="margin-top:8px;">{{ selectedDocDescription }}</p>
                            <div class="doc-layout-panel">
                                <div class="doc-layout-title">排版设置 <span class="hint">（按文档类型独立保存）</span></div>
                                <div class="doc-layout-grid">
                                    <label class="doc-layout-field">
                                        <span>字号</span>
                                        <select v-model="docLayout.fontSize" @change="saveLayout">
                                            <option value="sm">较小</option>
                                            <option value="md">标准</option>
                                            <option value="lg">较大</option>
                                        </select>
                                    </label>
                                    <label class="doc-layout-field">
                                        <span>行距</span>
                                        <select v-model="docLayout.lineHeight" @change="saveLayout">
                                            <option value="compact">紧凑</option>
                                            <option value="normal">标准</option>
                                            <option value="relaxed">宽松</option>
                                        </select>
                                    </label>
                                    <label class="doc-layout-field">
                                        <span>版心宽度</span>
                                        <select v-model="docLayout.contentWidth" @change="saveLayout">
                                            <option value="narrow">较窄</option>
                                            <option value="normal">标准</option>
                                            <option value="wide">较宽</option>
                                        </select>
                                    </label>
                                    <label class="doc-layout-field">
                                        <span>表格样式</span>
                                        <select v-model="docLayout.tableStyle" @change="saveLayout">
                                            <option value="default">默认</option>
                                            <option value="striped">斑马纹</option>
                                        </select>
                                    </label>
                                    <label class="doc-layout-field doc-layout-check">
                                        <input type="checkbox" v-model="docLayout.showToc"
                                            @change="(e) => { saveLayout(); e.target.blur(); }">
                                        <span>显示目录</span>
                                    </label>
                                    <button type="button" class="btn btn-outline btn-sm doc-layout-reset" @click="resetLayout">恢复默认</button>
                                </div>
                            </div>
                            <div class="doc-preview-layout" :class="layoutClasses.layoutRoot">
                                <div class="doc-preview-main">
                                    <div id="document-preview" class="report-box md-preview" :class="layoutClasses.preview">
                                        <span v-if="previewMessage">{{ previewMessage }}</span>
                                        <div v-else v-html="previewHtml"></div>
                                    </div>
                                </div>
                                <nav v-show="docLayout.showToc" class="doc-toc" aria-label="文档目录">
                                    <div class="doc-toc-title">目录</div>
                                    <ul class="doc-toc-list">
                                        <li v-if="!tocItems.length" class="doc-toc-empty">暂无目录</li>
                                        <li v-for="item in tocItems" :key="item.id" :class="item.level >= 2 ? 'toc-l' + item.level : ''">
                                            <a href="#" :class="{ active: activeTocId === item.id }"
                                               @click.prevent="scrollToHeading(item.id, $event)">{{ item.text }}</a>
                                        </li>
                                    </ul>
                                </nav>
                            </div>
                        </template>
                    </div>
                </div>

                <div class="chat-room-footer">
                    <button class="btn btn-outline btn-sm" @click="lookup()">刷新</button>
                    <span v-if="isPolling" class="polling-badge"><span class="polling-dot"></span>每 3 秒自动刷新</span>
                    <button v-if="isPolling" class="btn btn-danger btn-sm" @click="cancelDebate">终止</button>
                </div>
            </div>
        </div>
    </div>
    `
});
