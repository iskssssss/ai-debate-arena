import { escapeHtml } from '../utils.js';

/** Markdown 渲染结果 LRU 缓存，避免轮询时重复解析相同内容。 */
const markdownCache = new Map();
const MARKDOWN_CACHE_MAX = 256;

/**
 * 将 Markdown 文本渲染为安全的 HTML（带缓存）。
 * @param {string} text Markdown 原文
 * @returns {string} 安全 HTML
 */
export function renderMarkdown(text) {
    if (!text) return '';
    const cached = markdownCache.get(text);
    if (cached !== undefined) return cached;

    let html;
    if (typeof marked === 'undefined') {
        html = `<pre>${escapeHtml(text)}</pre>`;
    } else {
        marked.setOptions({ breaks: true, gfm: true });
        const raw = marked.parse(text);
        html = typeof DOMPurify !== 'undefined' ? DOMPurify.sanitize(raw) : raw;
    }

    if (markdownCache.size >= MARKDOWN_CACHE_MAX) {
        const oldest = markdownCache.keys().next().value;
        markdownCache.delete(oldest);
    }
    markdownCache.set(text, html);
    return html;
}

/** 清空 Markdown 缓存（切换研讨任务时调用）。 */
export function clearMarkdownCache() {
    markdownCache.clear();
}
