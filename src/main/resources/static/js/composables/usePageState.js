/** sessionStorage 键名，用于刷新后恢复页面状态。 */
const PAGE_STATE_KEY = 'debate_arena_page_state';

/**
 * 读取已保存的页面状态。
 * @returns {object} 页面状态对象
 */
export function loadPageState() {
    try {
        const raw = sessionStorage.getItem(PAGE_STATE_KEY);
        return raw ? JSON.parse(raw) : {};
    } catch {
        return {};
    }
}

/**
 * 合并保存页面状态（页签、研讨编号、子标签、滚动位置等）。
 * @param {object} patch 要合并的字段
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
 * @param {number} [scrollY] 指定位置，缺省时从存储读取
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
 * 保存指定研讨聊天区的滚动位置。
 * @param {string} sessionId 任务编号
 * @param {number} scrollTop 滚动偏移
 */
export function saveChatScroll(sessionId, scrollTop) {
    if (!sessionId || typeof scrollTop !== 'number') return;
    const current = loadPageState();
    const map = { ...(current.chatScrollBySession || {}), [sessionId]: scrollTop };
    savePageState({ chatScrollBySession: map });
}

/**
 * 读取指定研讨聊天区的滚动位置。
 * @param {string} sessionId 任务编号
 * @returns {number|undefined} 滚动偏移
 */
export function getChatScroll(sessionId) {
    if (!sessionId) return undefined;
    const top = loadPageState().chatScrollBySession?.[sessionId];
    return typeof top === 'number' ? top : undefined;
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
