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
 * @returns {object} 各文档类型的排版配置映射
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
 * @param {object} all 全部排版配置
 */
function saveAllLayouts(all) {
    localStorage.setItem(LAYOUT_STORAGE_KEY, JSON.stringify(all));
}

/**
 * 获取指定文档类型的排版配置（与默认值合并）。
 * @param {string} typeId 文档类型 ID
 * @returns {object} 合并后的排版配置
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
 * 将排版配置转为预览区 CSS 类名集合。
 * @param {object} layout 排版配置
 * @returns {object} 预览区与布局根节点的 class 映射
 */
export function layoutToClasses(layout) {
    return {
        preview: [
            'doc-font-' + layout.fontSize,
            'doc-line-' + layout.lineHeight,
            layout.tableStyle === 'striped' ? 'doc-table-striped' : ''
        ].filter(Boolean),
        layoutRoot: ['doc-width-' + layout.contentWidth]
    };
}
