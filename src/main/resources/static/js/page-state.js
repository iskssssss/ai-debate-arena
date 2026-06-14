/** sessionStorage 键名，用于刷新后恢复页面状态。 */
const PAGE_STATE_KEY = 'debate_arena_page_state';

/**
 * 读取已保存的页面状态。
 */
export function loadPageState() {
    try {
        const raw = sessionStorage.getItem(PAGE_STATE_KEY);
        return raw ? JSON.parse(raw) : {};
    } catch (e) {
        return {};
    }
}

/**
 * 合并保存页面状态（页签、研讨编号、子标签、滚动位置等）。
 */
export function savePageState(patch) {
    const current = loadPageState();
    sessionStorage.setItem(PAGE_STATE_KEY, JSON.stringify({ ...current, ...patch }));
}

/** 保存当前窗口滚动位置。 */
export function saveScrollPosition() {
    savePageState({ scrollY: window.scrollY || 0 });
}

/**
 * 恢复窗口滚动位置（等待布局完成后再滚动）。
 */
export function restoreScrollPosition(scrollY) {
    const y = typeof scrollY === 'number' ? scrollY : loadPageState().scrollY;
    if (typeof y !== 'number' || y < 0) return;
    const apply = () => window.scrollTo({ top: y, behavior: 'instant' in window ? 'instant' : 'auto' });
    requestAnimationFrame(() => {
        apply();
        setTimeout(apply, 120);
    });
}

/**
 * 注册滚动与离开页面前的自动保存。
 */
export function initPageStatePersistence() {
    let scrollTimer = null;
    window.addEventListener('scroll', () => {
        clearTimeout(scrollTimer);
        scrollTimer = setTimeout(saveScrollPosition, 150);
    }, { passive: true });
    window.addEventListener('beforeunload', saveScrollPosition);
}
