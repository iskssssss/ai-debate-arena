import { defineComponent, ref, computed, onMounted, onUnmounted, nextTick, KeepAlive } from './vue.js';
import {
    APP_INTRO, APP_FUNC_LOGIN, IS_DESKTOP, JUDGE_KEY_STORAGE, PAGE_META, API
} from './config.js';
import { showToast } from './composables/useToast.js';
import {
    initPageStatePersistence, loadPageState, savePageState, restoreScrollPosition
} from './composables/usePageState.js';
import ProfilesPanel from './components/ProfilesPanel.js';
import DebateForm from './components/DebateForm.js';
import HistoryPanel from './components/HistoryPanel.js';
import BubbleModal from './components/BubbleModal.js';
import ToastContainer from './components/ToastContainer.js';

/**
 * 应用根组件：侧栏导航 + 各功能面板。
 */
export default defineComponent({
    name: 'App',
    components: {
        ProfilesPanel, DebateForm, HistoryPanel, BubbleModal, ToastContainer,
        KeepAlive
    },
    setup() {
        const activeTab = ref('debate');
        const sidebarOpen = ref(false);
        const debateReady = ref(false);
        const judgeApiKey = ref(localStorage.getItem(JUDGE_KEY_STORAGE) || '');
        const historyRef = ref(null);
        const profilesRef = ref(null);

        const bubbleVisible = ref(false);
        const bubbleTitle = ref('');
        const bubbleTag = ref('');
        const bubbleContent = ref('');

        const pageMeta = computed(() => PAGE_META[activeTab.value] || PAGE_META.debate);
        const appSubtitle = computed(() =>
            IS_DESKTOP ? APP_INTRO + '（桌面客户端在本机自动启动后端服务）' : APP_INTRO
        );

        /** 切换侧栏展开状态。 */
        function toggleSidebar(forceOpen) {
            if (forceOpen === true) sidebarOpen.value = true;
            else if (forceOpen === false) sidebarOpen.value = false;
            else sidebarOpen.value = !sidebarOpen.value;
        }

        /** 判断通道是否满足发起研讨条件。 */
        async function checkDebateReady() {
            try {
                const res = await fetch(API + '/profiles/channels');
                const channels = await res.json();
                const count = Object.values(channels).filter(ch => ch.ready).length;
                debateReady.value = count >= 2;
            } catch {
                debateReady.value = false;
            }
            return debateReady.value;
        }

        /** 切换到指定页签。 */
        async function switchTab(name, options = {}) {
            if (name === 'debate' && !options.skipReadyCheck) {
                const ready = await checkDebateReady();
                if (!ready) {
                    showToast('请先在「通道配置」页登录至少 2 个通道', 'error');
                    return switchTab('profiles', { skipReadyCheck: true });
                }
            }
            activeTab.value = name;
            toggleSidebar(false);
            if (!options.skipSave) {
                savePageState({ tab: name });
            }
            await nextTick();
            if (name === 'profiles') profilesRef.value?.refresh(true);
            if (name === 'history') historyRef.value?.load(true);
        }

        /** 通道就绪状态变更回调。 */
        function onReadyChanged(ready) {
            debateReady.value = ready;
        }

        /** 同步新建研讨页填写的整理 API Key。 */
        function onJudgeKeyChanged(key) {
            judgeApiKey.value = key || '';
        }

        /** 研讨提交成功：跳转历史页并打开详情。 */
        async function onSubmitSuccess(sessionId) {
            await switchTab('history', { skipReadyCheck: true });
            await nextTick();
            await historyRef.value?.openSession(sessionId);
            historyRef.value?.load(true);
        }

        /** 需要跳转通道配置页。 */
        function onNeedProfiles() {
            switchTab('profiles', { skipReadyCheck: true });
        }

        /** 打开指定研讨详情（跳转历史页）。 */
        async function openSession(sessionId) {
            await switchTab('history', { skipReadyCheck: true });
            await nextTick();
            await historyRef.value?.openSession(sessionId);
        }

        /** 打开放大气泡弹窗。 */
        function onBubbleOpen({ title, tag, content }) {
            bubbleTitle.value = title;
            bubbleTag.value = tag;
            bubbleContent.value = content;
            bubbleVisible.value = true;
            document.body.classList.add('bubble-modal-open');
        }

        /** 关闭放大弹窗。 */
        function closeBubble() {
            bubbleVisible.value = false;
            document.body.classList.remove('bubble-modal-open');
        }

        /** Esc 键关闭弹窗。 */
        function onKeydown(e) {
            if (e.key === 'Escape') closeBubble();
        }

        /** 初始化桌面客户端事件监听。 */
        function initDesktopClient() {
            if (!IS_DESKTOP) return;
            window.desktopClient?.onBackendExited?.(() => showToast('本机服务已退出，请重启客户端', 'error'));
            window.desktopClient?.onNavigate?.((tab) => {
                switchTab(tab === 'debates' ? 'history' : tab);
            });
            window.desktopClient?.onAppAction?.((action) => {
                if (action === 'refresh-profiles') {
                    switchTab('profiles', { skipReadyCheck: true });
                }
                if (action === 'show-login-hint') {
                    showToast(APP_FUNC_LOGIN, 'info');
                }
            });
        }

        /** 恢复刷新前的页面状态。 */
        async function restoreState() {
            const saved = loadPageState();
            if (saved.tab) {
                const tab = saved.tab === 'debates' ? 'history' : saved.tab;
                await switchTab(tab, { skipReadyCheck: true, skipSave: true });
                if (tab === 'history' && saved.lookupId) {
                    await nextTick();
                    await historyRef.value?.openSession(saved.lookupId);
                    if (saved.detailTab) {
                        historyRef.value?.switchDetailTab(saved.detailTab);
                    }
                    restoreScrollPosition();
                } else {
                    restoreScrollPosition();
                }
                return;
            }
            const ready = await checkDebateReady();
            switchTab(ready ? 'debate' : 'profiles', { skipReadyCheck: true });
        }

        onMounted(() => {
            initPageStatePersistence();
            initDesktopClient();
            document.addEventListener('keydown', onKeydown);
            restoreState();
        });

        onUnmounted(() => {
            document.removeEventListener('keydown', onKeydown);
        });

        return {
            activeTab, sidebarOpen, debateReady, judgeApiKey,
            historyRef, profilesRef,
            bubbleVisible, bubbleTitle, bubbleTag, bubbleContent,
            pageMeta, appSubtitle,
            toggleSidebar, switchTab, onReadyChanged,
            onSubmitSuccess, onNeedProfiles, openSession,
            onBubbleOpen, closeBubble, onJudgeKeyChanged
        };
    },
    template: `
    <div class="app">
        <aside class="sidebar" :class="{ open: sidebarOpen }">
            <div class="sidebar-brand">
                <div class="brand-icon" aria-hidden="true">研</div>
                <div class="brand-text">
                    <span class="brand-name">方案研讨台</span>
                    <span class="brand-tag">AI Debate Arena</span>
                </div>
            </div>
            <nav class="sidebar-nav" aria-label="主导航">
                <button class="nav-item" :class="{ active: activeTab === 'debate' }" @click="switchTab('debate')">
                    <svg class="nav-icon" viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.6"><path d="M10 4v12M4 10h12"/></svg>
                    新建研讨
                </button>
                <button class="nav-item" :class="{ active: activeTab === 'profiles' }" @click="switchTab('profiles')">
                    <svg class="nav-icon" viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.6"><circle cx="7" cy="7" r="2.5"/><circle cx="13" cy="7" r="2.5"/><path d="M3 16c0-2.2 1.8-4 4-4s4 1.8 4 4M9 16c0-2.2 1.8-4 4-4s4 1.8 4 4"/></svg>
                    通道配置
                </button>
                <button class="nav-item" :class="{ active: activeTab === 'history' }" @click="switchTab('history')">
                    <svg class="nav-icon" viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.6"><circle cx="10" cy="10" r="7"/><path d="M10 6v4l3 2"/></svg>
                    研讨历史
                </button>
            </nav>
            <p class="sidebar-foot">{{ appSubtitle }}</p>
        </aside>

        <div class="main-area">
            <header class="page-header">
                <button class="menu-toggle" type="button" @click="toggleSidebar()" aria-label="切换菜单">
                    <svg viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.6"><path d="M4 6h12M4 10h12M4 14h12"/></svg>
                </button>
                <div class="page-header-text">
                    <h1>{{ pageMeta.title }}</h1>
                    <p>{{ pageMeta.desc }}</p>
                </div>
            </header>

            <main class="content">
                <KeepAlive :max="4">
                    <section v-if="activeTab === 'profiles'" key="profiles" class="section active">
                        <ProfilesPanel ref="profilesRef" @ready-changed="onReadyChanged" />
                    </section>
                    <section v-else-if="activeTab === 'debate'" key="debate" class="section active">
                        <DebateForm :debate-ready="debateReady"
                            @submit-success="onSubmitSuccess"
                            @need-profiles="onNeedProfiles"
                            @judge-key-changed="onJudgeKeyChanged" />
                    </section>
                    <section v-else-if="activeTab === 'history'" key="history" class="section active">
                        <HistoryPanel ref="historyRef"
                            :judge-api-key="judgeApiKey"
                            :visible="activeTab === 'history'"
                            @bubble-open="onBubbleOpen" />
                    </section>
                </KeepAlive>
            </main>
        </div>

        <BubbleModal :visible="bubbleVisible" :title="bubbleTitle" :tag="bubbleTag"
            :content="bubbleContent" @close="closeBubble" />
        <ToastContainer />
        <div class="sidebar-overlay" :class="{ open: sidebarOpen }" @click="toggleSidebar(false)"></div>
    </div>
    `
});
