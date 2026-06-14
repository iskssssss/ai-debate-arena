/** 产出文档排版配置在 localStorage 中的键名。 */
const LAYOUT_STORAGE_KEY = 'debate-arena-doc-layouts';

/** 单份文档的默认排版设置。 */
export const DEFAULT_DOC_LAYOUT = {
    fontSize: 'md',
    lineHeight: 'normal',
    contentWidth: 'normal',
    showToc: true,
    tableStyle: 'default'
};

/**
 * 读取全部文档类型的排版配置。
 */
function loadAllLayouts() {
    try {
        const raw = localStorage.getItem(LAYOUT_STORAGE_KEY);
        return raw ? JSON.parse(raw) : {};
    } catch {
        return {};
    }
}

/**
 * 持久化全部文档排版配置。
 */
function saveAllLayouts(all) {
    localStorage.setItem(LAYOUT_STORAGE_KEY, JSON.stringify(all));
}

/**
 * 获取指定文档类型的排版配置（与默认值合并）。
 * @param {string} typeId 文档类型 ID
 */
export function getDocumentLayout(typeId) {
    const all = loadAllLayouts();
    return { ...DEFAULT_DOC_LAYOUT, ...(all[typeId] || {}) };
}

/**
 * 保存指定文档类型的排版配置。
 * @param {string} typeId 文档类型 ID
 * @param {object} layout 排版字段
 */
export function saveDocumentLayout(typeId, layout) {
    if (!typeId) return;
    const all = loadAllLayouts();
    all[typeId] = { ...DEFAULT_DOC_LAYOUT, ...layout };
    saveAllLayouts(all);
}

/**
 * 将当前文档类型的排版配置应用到预览区 DOM。
 * @param {string} typeId 文档类型 ID
 */
export function applyDocumentLayout(typeId) {
    const layout = getDocumentLayout(typeId);
    const preview = document.getElementById('document-preview');
    const layoutRoot = document.querySelector('.doc-preview-layout');
    const toc = document.getElementById('document-toc');

    if (preview) {
        preview.classList.remove('doc-font-sm', 'doc-font-md', 'doc-font-lg');
        preview.classList.add('doc-font-' + layout.fontSize);

        preview.classList.remove('doc-line-compact', 'doc-line-normal', 'doc-line-relaxed');
        preview.classList.add('doc-line-' + layout.lineHeight);

        preview.classList.remove('doc-table-striped');
        if (layout.tableStyle === 'striped') {
            preview.classList.add('doc-table-striped');
        }
    }

    if (layoutRoot) {
        layoutRoot.classList.remove('doc-width-narrow', 'doc-width-normal', 'doc-width-wide');
        layoutRoot.classList.add('doc-width-' + layout.contentWidth);
    }

    if (toc) {
        toc.hidden = !layout.showToc;
    }
}

/**
 * 同步排版表单控件与当前文档类型的已保存配置。
 * @param {string} typeId 文档类型 ID
 */
export function syncLayoutControls(typeId) {
    const layout = getDocumentLayout(typeId);
    const fontEl = document.getElementById('doc-layout-font');
    const lineEl = document.getElementById('doc-layout-line');
    const widthEl = document.getElementById('doc-layout-width');
    const tocEl = document.getElementById('doc-layout-toc');
    const tableEl = document.getElementById('doc-layout-table');
    if (fontEl) fontEl.value = layout.fontSize;
    if (lineEl) lineEl.value = layout.lineHeight;
    if (widthEl) widthEl.value = layout.contentWidth;
    if (tocEl) tocEl.checked = layout.showToc;
    if (tableEl) tableEl.value = layout.tableStyle;
}

/**
 * 从排版表单读取配置并保存、应用到当前文档。
 */
export function saveLayoutFromControls() {
    const typeId = document.getElementById('document-select')?.value;
    if (!typeId) return;
    const layout = {
        fontSize: document.getElementById('doc-layout-font')?.value || DEFAULT_DOC_LAYOUT.fontSize,
        lineHeight: document.getElementById('doc-layout-line')?.value || DEFAULT_DOC_LAYOUT.lineHeight,
        contentWidth: document.getElementById('doc-layout-width')?.value || DEFAULT_DOC_LAYOUT.contentWidth,
        showToc: document.getElementById('doc-layout-toc')?.checked ?? DEFAULT_DOC_LAYOUT.showToc,
        tableStyle: document.getElementById('doc-layout-table')?.value || DEFAULT_DOC_LAYOUT.tableStyle
    };
    saveDocumentLayout(typeId, layout);
    applyDocumentLayout(typeId);
}

/**
 * 恢复当前文档类型为默认排版。
 */
export function resetDocumentLayout() {
    const typeId = document.getElementById('document-select')?.value;
    if (!typeId) return;
    saveDocumentLayout(typeId, { ...DEFAULT_DOC_LAYOUT });
    syncLayoutControls(typeId);
    applyDocumentLayout(typeId);
}

/**
 * 渲染产出文档排版配置面板 HTML（每种文档独立记忆）。
 */
export function renderDocumentLayoutPanel() {
    return `<div class="doc-layout-panel" id="doc-layout-panel">
        <div class="doc-layout-title">排版设置 <span class="hint">（按文档类型独立保存）</span></div>
        <div class="doc-layout-grid">
            <label class="doc-layout-field">
                <span>字号</span>
                <select id="doc-layout-font" onchange="saveLayoutFromControls()">
                    <option value="sm">较小</option>
                    <option value="md" selected>标准</option>
                    <option value="lg">较大</option>
                </select>
            </label>
            <label class="doc-layout-field">
                <span>行距</span>
                <select id="doc-layout-line" onchange="saveLayoutFromControls()">
                    <option value="compact">紧凑</option>
                    <option value="normal" selected>标准</option>
                    <option value="relaxed">宽松</option>
                </select>
            </label>
            <label class="doc-layout-field">
                <span>版心宽度</span>
                <select id="doc-layout-width" onchange="saveLayoutFromControls()">
                    <option value="narrow">较窄</option>
                    <option value="normal" selected>标准</option>
                    <option value="wide">较宽</option>
                </select>
            </label>
            <label class="doc-layout-field">
                <span>表格样式</span>
                <select id="doc-layout-table" onchange="saveLayoutFromControls()">
                    <option value="default">默认</option>
                    <option value="striped">斑马纹</option>
                </select>
            </label>
            <label class="doc-layout-field doc-layout-check">
                <input type="checkbox" id="doc-layout-toc" checked onchange="saveLayoutFromControls()">
                <span>显示目录</span>
            </label>
            <button type="button" class="btn btn-outline btn-sm doc-layout-reset" onclick="resetDocumentLayout()">恢复默认</button>
        </div>
    </div>`;
}
