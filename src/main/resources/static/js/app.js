import { APP_INTRO, APP_FUNC_LOGIN, IS_DESKTOP, JUDGE_KEY_STORAGE } from './config.js';
import { toast } from './utils.js';
import { switchTab, toggleSidebar } from './navigation.js';
import { isDebateReady, refreshProfiles, setupLogin, checkHealth, deleteProfile } from './profiles.js';
import {
    loadOutputDocumentTypes, syncThresholdFromRange, syncThresholdFromInput,
    restoreThresholdPct, startDebate, onJudgeModeChange
} from './debate-form.js';
import {
    openSession, lookupDebate, retryRoundJudge, cancelDebate, switchDetailTab, scrollToChatAnchor,
    openBubbleModal, closeBubbleModal, bindBubbleModalEvents
} from './debate-detail.js';
import {
    updateDocumentDescription, previewSelectedDocument, downloadSelectedDocument, scrollToHeading
} from './documents.js';
import { loadHistory } from './history.js';
import { loadPageState, initPageStatePersistence, restoreScrollPosition } from './page-state.js';

/**
 * 将模块函数挂载到 window，供 HTML 内联事件处理器调用。
 */
function bindGlobals() {
    Object.assign(window, {
        switchTab,
        toggleSidebar,
        refreshProfiles,
        setupLogin,
        checkHealth,
        deleteProfile,
        syncThresholdFromRange,
        syncThresholdFromInput,
        startDebate,
        onJudgeModeChange,
        openSession,
        lookupDebate,
        loadHistory,
        retryRoundJudge,
        cancelDebate,
        switchDetailTab,
        scrollToChatAnchor,
        openBubbleModal,
        closeBubbleModal,
        updateDocumentDescription,
        previewSelectedDocument,
        downloadSelectedDocument,
        scrollToHeading
    });
}

/**
 * 应用初始化：优先恢复刷新前的页面状态，否则按通道就绪情况进入默认页。
 */
async function initApp() {
    initPageStatePersistence();
    loadHistory(true);
    loadOutputDocumentTypes();

    const saved = loadPageState();
    if (saved.tab) {
        await switchTab(saved.tab, { skipReadyCheck: true, skipSave: true });
        if (saved.tab === 'debates' && saved.lookupId) {
            const input = document.getElementById('lookup-id');
            if (input) input.value = saved.lookupId;
            await lookupDebate(true, {
                restoreDetailTab: saved.detailTab || 'main',
                restoreScroll: true
            });
        } else {
            restoreScrollPosition();
        }
        refreshProfiles();
        return;
    }

    const ready = await isDebateReady();
    if (ready) {
        switchTab('debate', { skipReadyCheck: true });
    } else {
        switchTab('profiles', { skipReadyCheck: true });
    }
    refreshProfiles();
}

/** 恢复本地保存的 API Key 与收敛阈值。 */
function restoreLocalSettings() {
    const savedKey = localStorage.getItem(JUDGE_KEY_STORAGE);
    if (savedKey) document.getElementById('judgeApiKey').value = savedKey;
    restoreThresholdPct();
}

/** 初始化桌面客户端事件监听。 */
function initDesktopClient() {
    if (!IS_DESKTOP) return;
    const subtitle = document.getElementById('app-subtitle');
    if (subtitle) subtitle.textContent = APP_INTRO + '（桌面客户端在本机自动启动后端服务）';
    window.desktopClient?.onBackendExited?.(() => toast('本机服务已退出，请重启客户端', 'error'));
    window.desktopClient?.onNavigate?.((tab) => switchTab(tab));
    window.desktopClient?.onAppAction?.((action) => {
        if (action === 'refresh-profiles') {
            switchTab('profiles', { skipReadyCheck: true });
            refreshProfiles();
        }
        if (action === 'show-login-hint') {
            toast(APP_FUNC_LOGIN, 'info');
        }
    });
}

bindGlobals();
restoreLocalSettings();
initDesktopClient();
bindBubbleModalEvents();
initApp();
