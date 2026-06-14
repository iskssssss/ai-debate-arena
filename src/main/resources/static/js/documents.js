import { API, DOC_STATUS_LABELS } from './config.js';
import { state } from './state.js';
import { toast, escapeHtml } from './utils.js';
import {
    applyDocumentLayout, renderDocumentLayoutPanel, syncLayoutControls
} from './document-layout.js';

/** 渲染产出文档区块 HTML。 */
export function renderDocumentSection(documents) {
    if (!documents || !documents.length) {
        return '<div class="empty-state"><p>本场研讨未配置产出文档</p><p class="hint">发起研讨时可勾选需要生成的文档类型</p></div>';
    }
    const options = documents.map(d => {
        const status = DOC_STATUS_LABELS[d.status] || d.status;
        return `<option value="${escapeHtml(d.type)}">${escapeHtml(d.title)}（${status}）</option>`;
    }).join('');
    return `<div class="doc-toolbar">
            <select id="document-select" onchange="onDocumentSelectChange()">${options}</select>
            <button class="btn btn-outline btn-sm" onclick="previewSelectedDocument()">预览</button>
            <button class="btn btn-primary btn-sm" onclick="downloadSelectedDocument()">下载</button>
        </div>
        <p id="document-desc" class="hint" style="margin-top:8px;"></p>
        ${renderDocumentLayoutPanel()}
        <div class="doc-preview-layout">
            <div class="doc-preview-main">
                <div id="document-preview" class="report-box">选择文档后点击预览</div>
            </div>
            <nav class="doc-toc" id="document-toc" aria-label="文档目录">
                <div class="doc-toc-title">目录</div>
                <ul class="doc-toc-list" id="document-toc-list"></ul>
            </nav>
        </div>`;
}

/** 根据当前选中的产出文档更新用途说明与排版配置。 */
export function updateDocumentDescription() {
    const type = document.getElementById('document-select')?.value;
    const el = document.getElementById('document-desc');
    if (!el) return;
    const item = state.currentDocumentList.find(d => d.type === type);
    el.textContent = item?.description || '';
    if (type) {
        syncLayoutControls(type);
        applyDocumentLayout(type);
    }
}

/** 切换产出文档时同步说明与排版。 */
export function onDocumentSelectChange() {
    updateDocumentDescription();
}

/**
 * 将 Markdown 文本渲染为安全的 HTML，用于产出文档预览。
 */
export function renderMarkdown(text) {
    if (!text) return '';
    if (typeof marked === 'undefined') {
        return `<pre>${escapeHtml(text)}</pre>`;
    }
    marked.setOptions({ breaks: true, gfm: true });
    const html = marked.parse(text);
    return typeof DOMPurify !== 'undefined' ? DOMPurify.sanitize(html) : html;
}

/** 在预览区显示纯文本提示（加载中、错误等）。 */
export function setPreviewMessage(el, message) {
    el.classList.remove('md-preview');
    el.textContent = message;
    clearDocumentToc();
}

/** 在预览区渲染 Markdown 正文，并生成右侧目录。 */
export function setPreviewMarkdown(el, markdown) {
    el.classList.add('md-preview');
    el.innerHTML = renderMarkdown(markdown);
    buildDocumentToc(el);
}

/** 清空右侧目录。 */
export function clearDocumentToc() {
    const list = document.getElementById('document-toc-list');
    if (!list) return;
    list.innerHTML = '<li class="doc-toc-empty">暂无目录</li>';
}

/**
 * 根据正文标题生成右侧目录，并为标题添加锚点 ID。
 */
export function buildDocumentToc(previewEl) {
    const list = document.getElementById('document-toc-list');
    if (!list || !previewEl) return;

    const headings = previewEl.querySelectorAll('h1, h2, h3, h4');
    if (!headings.length) {
        list.innerHTML = '<li class="doc-toc-empty">暂无目录</li>';
        return;
    }

    const usedIds = new Set();
    let html = '';
    headings.forEach((heading, index) => {
        const level = parseInt(heading.tagName.charAt(1), 10);
        const text = heading.textContent.trim();
        if (!text) return;

        const id = makeHeadingId(text, index, usedIds);
        heading.id = id;

        const cls = level >= 2 ? ` class="toc-l${level}"` : '';
        html += `<li${cls}><a href="#${id}" data-toc-id="${id}" onclick="scrollToHeading(event, '${id}')">${escapeHtml(text)}</a></li>`;
    });
    list.innerHTML = html;
    bindTocScrollSpy(previewEl, list);
}

/** 生成唯一标题锚点 ID。 */
function makeHeadingId(text, index, usedIds) {
    const base = text.toLowerCase()
        .replace(/[^\w\u4e00-\u9fff]+/g, '-')
        .replace(/^-+|-+$/g, '') || ('section-' + index);
    let id = base;
    let seq = 1;
    while (usedIds.has(id)) {
        id = base + '-' + (++seq);
    }
    usedIds.add(id);
    return id;
}

/** 点击目录项时平滑滚动到对应标题（在正文容器内滚动）。 */
export function scrollToHeading(event, id) {
    event.preventDefault();
    const target = document.getElementById(id);
    const container = document.getElementById('document-preview');
    if (!target || !container) return;
    const delta = target.getBoundingClientRect().top - container.getBoundingClientRect().top;
    container.scrollTo({ top: container.scrollTop + delta - 8, behavior: 'smooth' });
    highlightTocItem(id);
}

/** 高亮当前目录项。 */
function highlightTocItem(activeId) {
    document.querySelectorAll('#document-toc-list a').forEach(a => {
        a.classList.toggle('active', a.dataset.tocId === activeId);
    });
}

/** 监听正文滚动，同步高亮右侧目录当前章节。 */
function bindTocScrollSpy(previewEl, listEl) {
    const container = previewEl.closest('.report-box') || previewEl;
    const links = Array.from(listEl.querySelectorAll('a[data-toc-id]'));
    if (!links.length) return;

    const onScroll = () => {
        const headings = links.map(a => document.getElementById(a.dataset.tocId)).filter(Boolean);
        if (!headings.length) return;

        const containerTop = container.getBoundingClientRect().top;
        let activeId = headings[0].id;
        for (const h of headings) {
            if (h.getBoundingClientRect().top - containerTop <= 32) {
                activeId = h.id;
            }
        }
        highlightTocItem(activeId);
    };

    container.removeEventListener('scroll', container._tocScrollHandler);
    container._tocScrollHandler = onScroll;
    container.addEventListener('scroll', onScroll, { passive: true });
    highlightTocItem(links[0].dataset.tocId);
}

/** 预览当前选中的产出文档。 */
export async function previewSelectedDocument() {
    if (!state.currentSessionId) return;
    const type = document.getElementById('document-select')?.value;
    if (!type) return;
    const el = document.getElementById('document-preview');
    setPreviewMessage(el, '加载中…');
    try {
        const res = await fetch(API + '/debates/' + state.currentSessionId + '/documents/' + type);
        const text = await res.text();
        if (!res.ok) {
            setPreviewMessage(el, '加载失败：' + text);
            return;
        }
        setPreviewMarkdown(el, text);
        applyDocumentLayout(type);
    } catch (e) {
        setPreviewMessage(el, '加载失败：' + e.message);
    }
}

/** 下载当前选中的产出文档。 */
export function downloadSelectedDocument() {
    if (!state.currentSessionId) return;
    const type = document.getElementById('document-select')?.value;
    if (!type) return;
    const item = state.currentDocumentList.find(d => d.type === type);
    const label = item?.title || type;
    window.open(API + '/debates/' + state.currentSessionId + '/documents/' + type, '_blank');
    toast('正在下载：' + label, 'info');
}
