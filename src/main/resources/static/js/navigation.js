import { PAGE_META } from './config.js';
import { toast } from './utils.js';
import { isDebateReady, refreshProfiles } from './profiles.js';
import { loadHistory } from './history.js';
import { savePageState } from './page-state.js';

/**
 * 更新页头标题与描述。
 */
function updatePageHeader(name) {
    const meta = PAGE_META[name];
    if (!meta) return;
    const titleEl = document.getElementById('page-title');
    const descEl = document.getElementById('page-desc');
    if (titleEl) titleEl.textContent = meta.title;
    if (descEl) descEl.textContent = meta.desc;
}

/**
 * 切换移动端侧栏展开/收起。
 */
export function toggleSidebar(forceOpen) {
    const sidebar = document.getElementById('sidebar');
    const overlay = document.getElementById('sidebar-overlay');
    const open = forceOpen === true ? true : forceOpen === false ? false : !sidebar.classList.contains('open');
    sidebar.classList.toggle('open', open);
    overlay.classList.toggle('open', open);
}

/**
 * 切换到指定页签；进入新建研讨前会校验通道登录状态，不满足则跳转通道配置。
 */
export async function switchTab(name, options = {}) {
    if (name === 'debate' && !options.skipReadyCheck) {
        const ready = await isDebateReady();
        if (!ready) {
            toast('请先在「通道配置」页登录至少 2 个通道', 'error');
            return switchTab('profiles', { skipReadyCheck: true });
        }
    }
    document.querySelectorAll('.section').forEach(s => s.classList.remove('active'));
    document.querySelectorAll('.nav-item').forEach(t => t.classList.remove('active'));
    document.getElementById(name).classList.add('active');
    document.querySelector(`.nav-item[data-tab="${name}"]`)?.classList.add('active');
    updatePageHeader(name);
    toggleSidebar(false);
    if (!options.skipSave) {
        savePageState({ tab: name });
    }
    if (name === 'profiles') refreshProfiles();
    if (name === 'history') loadHistory();
}
