/**
 * 从 Markdown 预览 DOM 提取标题并生成目录项，同时为标题元素写入锚点 id。
 * @param {HTMLElement|null} root 预览根元素
 * @returns {Array<{id: string, text: string, level: number}>} 目录项列表
 */
export function buildMarkdownToc(root) {
    if (!root) return [];
    const headings = root.querySelectorAll('h1, h2, h3, h4');
    const usedIds = new Set();
    const items = [];
    headings.forEach((heading, index) => {
        const level = parseInt(heading.tagName.charAt(1), 10);
        const text = heading.textContent.trim();
        if (!text) return;
        const base = text.toLowerCase()
            .replace(/[^\w\u4e00-\u9fff]+/g, '-')
            .replace(/^-+|-+$/g, '') || ('section-' + index);
        let id = base;
        let seq = 1;
        while (usedIds.has(id)) id = base + '-' + (++seq);
        usedIds.add(id);
        heading.id = id;
        items.push({ id, text, level });
    });
    return items;
}

/**
 * 在指定滚动容器内平滑滚动到锚点标题。
 * @param {string} id 标题锚点 id
 * @param {HTMLElement|null} container 滚动容器
 */
export function scrollToTocHeading(id, container) {
    if (!id || !container) return;
    const escaped = typeof CSS !== 'undefined' && CSS.escape
        ? CSS.escape(id)
        : id.replace(/[^\w-]/g, '\\$&');
    const target = container.querySelector('#' + escaped);
    if (!target) return;
    const delta = target.getBoundingClientRect().top - container.getBoundingClientRect().top;
    container.scrollTo({ top: container.scrollTop + delta - 8, behavior: 'smooth' });
}
