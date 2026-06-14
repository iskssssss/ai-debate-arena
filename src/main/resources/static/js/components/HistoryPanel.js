import { defineComponent, ref, computed, watch, onMounted, onUnmounted, nextTick } from '../vue.js';
import { API } from '../config.js';
import { formatTime, statusBadgeClass, historyStatusClass, displayTopic, isSessionId } from '../utils.js';
import { showToast } from '../composables/useToast.js';
import DebateDetail from './DebateDetail.js';

/** 已结束的研讨状态集合。 */
const TERMINAL_STATUSES = new Set(['CONVERGED', 'MAX_ROUNDS', 'FAILED']);

/**
 * 生成历史列表数据指纹，用于静默刷新时跳过无变化更新。
 * @param {Array} list 研讨历史列表
 * @returns {string} 指纹字符串
 */
function historyFingerprint(list) {
    return (list || []).map(item =>
        `${item.sessionId}:${item.status}:${item.currentRound}:${item.updatedAt || item.createdAt}`
    ).join('|');
}

/**
 * 研讨历史面板：左侧列表 + 右侧详情（master-detail 布局）。
 */
export default defineComponent({
    name: 'HistoryPanel',
    components: { DebateDetail },
    props: {
        /** 整理 API Key，传递给详情面板。 */
        judgeApiKey: { type: String, default: '' },
        /** 当前页签是否可见，用于控制详情轮询。 */
        visible: { type: Boolean, default: true }
    },
    emits: ['bubble-open'],
    setup(props, { emit, expose }) {
        const items = ref([]);
        const loading = ref(true);
        const error = ref('');
        const lastFingerprint = ref('');
        const activeSessionId = ref('');
        const searchQuery = ref('');
        const detailRef = ref(null);
        const detailMounted = ref(false);
        const listPollTimer = ref(null);

        /** 是否存在进行中的研讨（用于决定是否轮询列表）。 */
        const hasRunningDebates = computed(() =>
            items.value.some(item => !TERMINAL_STATUSES.has(item.status))
        );

        /** 按搜索关键词过滤历史列表。 */
        const filteredItems = computed(() => {
            const q = searchQuery.value.trim().toLowerCase();
            if (!q) return items.value;
            return items.value.filter(item =>
                item.sessionId.toLowerCase().includes(q)
                || (item.topic || '').toLowerCase().includes(q)
            );
        });

        /** 加载研讨历史列表。 */
        async function load(silent = false) {
            if (!silent) loading.value = true;
            error.value = '';
            try {
                const res = await fetch(API + '/debates?limit=30');
                if (!res.ok) throw new Error('读取失败');
                const next = await res.json();
                const fp = historyFingerprint(next);
                if (silent && fp === lastFingerprint.value) return;
                lastFingerprint.value = fp;
                items.value = next;
            } catch (e) {
                if (!silent) error.value = e.message;
            } finally {
                loading.value = false;
            }
        }

        /**
         * 选中并打开指定研讨详情。
         * @param {string} sessionId 任务编号
         * @param {FocusEvent} [event] 点击事件
         */
        async function openSession(sessionId, event) {
            event?.currentTarget?.blur();
            const id = (sessionId || '').trim();
            if (!id) return;
            detailMounted.value = true;
            activeSessionId.value = id;
            await nextTick();
            await detailRef.value?.openSession(id);
        }

        /**
         * 搜索框回车：优先匹配列表项，否则按任务编号直接打开。
         */
        async function onSearchEnter() {
            const q = searchQuery.value.trim();
            if (!q) return;
            const exact = items.value.find(item => item.sessionId.toLowerCase() === q.toLowerCase());
            if (exact) {
                await openSession(exact.sessionId);
                return;
            }
            if (isSessionId(q)) {
                await openSession(q);
                return;
            }
            const partial = items.value.find(item => item.sessionId.toLowerCase().startsWith(q.toLowerCase()));
            if (partial && filteredItems.value.length === 1) {
                await openSession(partial.sessionId);
                return;
            }
            showToast('未找到匹配记录，请输入完整任务编号后回车', 'info');
        }

        /** 切换详情子标签（供页面状态恢复使用）。 */
        function switchDetailTab(tab) {
            detailRef.value?.switchTab(tab);
        }

        /** 启动历史列表静默轮询。 */
        function startListPoll() {
            stopListPoll();
            if (!props.visible || !hasRunningDebates.value) return;
            listPollTimer.value = setInterval(() => load(true), 30000);
        }

        /** 停止历史列表轮询。 */
        function stopListPoll() {
            if (listPollTimer.value) {
                clearInterval(listPollTimer.value);
                listPollTimer.value = null;
            }
        }

        watch([() => props.visible, hasRunningDebates], ([visible]) => {
            if (visible && hasRunningDebates.value) startListPoll();
            else stopListPoll();
        });

        onMounted(() => {
            load().then(() => {
                if (props.visible && hasRunningDebates.value) startListPoll();
            });
        });

        onUnmounted(stopListPoll);

        expose({ load, openSession, switchDetailTab });

        return {
            items, loading, error, searchQuery, filteredItems,
            activeSessionId, detailRef, detailMounted, load, openSession, onSearchEnter,
            formatTime, statusBadgeClass, historyStatusClass, displayTopic, isSessionId,
            onBubbleOpen: (payload) => emit('bubble-open', payload)
        };
    },
    template: `
    <div class="history-layout">
        <aside class="history-sidebar">
            <div class="history-sidebar-toolbar">
                <input v-model="searchQuery" class="history-search"
                    placeholder="搜索任务编号或主题，回车打开编号…"
                    @keyup.enter="onSearchEnter">
                <button class="btn btn-outline btn-sm" @click="load()">刷新</button>
            </div>
            <div v-if="loading" class="loading">加载中</div>
            <div v-else-if="error" class="callout callout-error">加载失败：{{ error }}</div>
            <div v-else-if="!items.length" class="empty-state history-sidebar-empty">
                <div class="empty-icon">📭</div>
                <p>暂无研讨记录</p>
                <p class="hint">在「新建研讨」页发起第一场研讨</p>
            </div>
            <div v-else-if="!filteredItems.length" class="empty-state history-sidebar-empty">
                <p>无匹配记录</p>
                <p v-if="isSessionId(searchQuery)" class="hint">按回车直接打开该任务编号</p>
                <p v-else class="hint">可输入完整任务编号后按回车打开</p>
            </div>
            <div v-else class="history-list">
                <button v-for="item in filteredItems" :key="item.sessionId"
                        type="button"
                        class="history-card"
                        :class="[historyStatusClass(item.status), { active: activeSessionId === item.sessionId }]"
                        @click="openSession(item.sessionId, $event)">
                    <div class="history-title">{{ displayTopic(item.topic) }}</div>
                    <div class="history-meta">
                        <span class="badge" :class="statusBadgeClass(item.status)">{{ item.statusLabel || item.status }}</span>
                        <span>{{ item.sessionId.slice(0, 8) }}…</span>
                        <span>轮次 {{ item.currentRound }}/{{ item.maxRounds }}</span>
                        <span>{{ (item.participants || []).join('、') || '—' }}</span>
                        <span>{{ formatTime(item.updatedAt || item.createdAt) }}</span>
                    </div>
                    <div v-if="item.status === 'FAILED' && item.failureReason"
                         class="hint" style="color:var(--error);margin-top:8px;">{{ item.failureReason }}</div>
                </button>
            </div>
        </aside>
        <main class="history-detail-pane">
            <DebateDetail v-if="detailMounted"
                v-show="activeSessionId"
                ref="detailRef"
                :session-id="activeSessionId"
                :judge-api-key="judgeApiKey"
                :visible="visible && !!activeSessionId"
                embedded
                @bubble-open="onBubbleOpen" />
            <div v-if="!activeSessionId" class="history-detail-empty">
                <div class="empty-icon">📋</div>
                <p>从左侧选择研讨记录查看详情</p>
                <p class="hint">也可输入完整任务编号后按回车打开</p>
            </div>
        </main>
    </div>
    `
});
