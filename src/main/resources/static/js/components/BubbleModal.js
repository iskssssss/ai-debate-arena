import { defineComponent, ref, computed, watch, nextTick, onUnmounted } from '../vue.js';
import { renderMarkdown } from '../composables/useMarkdown.js';
import { buildMarkdownToc, scrollToTocHeading } from '../composables/useMarkdownToc.js';

/**
 * 聊天气泡内容放大弹窗（Teleport + 过渡动画 + 目录导航 + 焦点陷阱）。
 */
export default defineComponent({
    name: 'BubbleModal',
    props: {
        visible: { type: Boolean, default: false },
        title: { type: String, default: '' },
        tag: { type: String, default: '' },
        content: { type: String, default: '' }
    },
    emits: ['close'],
    setup(props, { emit }) {
        const dialogRef = ref(null);
        const closeBtnRef = ref(null);
        const bodyRef = ref(null);
        const tocItems = ref([]);
        const activeTocId = ref('');
        let bodyScrollHandler = null;
        let focusTrapHandler = null;

        /** 缓存弹窗内 Markdown 渲染结果。 */
        const html = computed(() => renderMarkdown(props.content));

        /** 根据渲染后的正文生成目录。 */
        async function refreshToc() {
            await nextTick();
            const items = buildMarkdownToc(bodyRef.value);
            tocItems.value = items;
            activeTocId.value = items[0]?.id || '';
        }

        /** 根据正文滚动位置同步目录高亮项。 */
        function updateActiveTocFromScroll() {
            const container = bodyRef.value;
            if (!container || !tocItems.value.length) return;
            const containerTop = container.getBoundingClientRect().top;
            let active = tocItems.value[0].id;
            for (const item of tocItems.value) {
                const escaped = typeof CSS !== 'undefined' && CSS.escape
                    ? CSS.escape(item.id)
                    : item.id.replace(/[^\w-]/g, '\\$&');
                const el = container.querySelector('#' + escaped);
                if (!el) continue;
                const offset = el.getBoundingClientRect().top - containerTop;
                if (offset <= 48) active = item.id;
            }
            activeTocId.value = active;
        }

        /** Tab 键焦点循环，将焦点限制在弹窗内。 */
        function trapFocus(e) {
            if (!props.visible || e.key !== 'Tab' || !dialogRef.value) return;
            const nodes = dialogRef.value.querySelectorAll(
                'button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])'
            );
            const list = Array.from(nodes).filter(el => !el.disabled && el.offsetParent !== null);
            if (!list.length) return;
            const first = list[0];
            const last = list[list.length - 1];
            if (e.shiftKey && document.activeElement === first) {
                e.preventDefault();
                last.focus();
            } else if (!e.shiftKey && document.activeElement === last) {
                e.preventDefault();
                first.focus();
            }
        }

        /** 解绑弹窗滚动与焦点监听。 */
        function cleanupModal() {
            tocItems.value = [];
            activeTocId.value = '';
            if (bodyScrollHandler && bodyRef.value) {
                bodyRef.value.removeEventListener('scroll', bodyScrollHandler);
            }
            bodyScrollHandler = null;
            if (focusTrapHandler) {
                document.removeEventListener('keydown', focusTrapHandler);
                focusTrapHandler = null;
            }
        }

        /** 弹窗打开或正文渲染后重建目录并初始化交互。 */
        watch([() => props.visible, html], async ([visible]) => {
            if (!visible) {
                cleanupModal();
                return;
            }
            await nextTick();
            if (bodyRef.value) bodyRef.value.scrollTop = 0;
            await refreshToc();
            closeBtnRef.value?.focus();
            bodyScrollHandler = () => updateActiveTocFromScroll();
            bodyRef.value?.addEventListener('scroll', bodyScrollHandler, { passive: true });
            focusTrapHandler = trapFocus;
            document.addEventListener('keydown', focusTrapHandler);
        });

        /** 关闭弹窗。 */
        function close() {
            emit('close');
        }

        /** 点击目录项滚动到对应标题。 */
        function scrollToHeading(id, event) {
            event?.preventDefault();
            event?.currentTarget?.blur();
            scrollToTocHeading(id, bodyRef.value);
            activeTocId.value = id;
        }

        onUnmounted(cleanupModal);

        return {
            close, html, dialogRef, closeBtnRef, bodyRef,
            tocItems, activeTocId, scrollToHeading
        };
    },
    template: `
    <Teleport to="body">
        <Transition name="modal-fade">
            <div v-if="visible" class="bubble-modal" aria-hidden="false">
                <div class="bubble-modal-backdrop" @click="close"></div>
                <div ref="dialogRef" class="bubble-modal-dialog" role="dialog"
                    aria-modal="true" :aria-labelledby="'bubble-modal-title'">
                    <div class="bubble-modal-header">
                        <div class="bubble-modal-heading">
                            <h3 id="bubble-modal-title" class="bubble-modal-title">{{ title || '消息内容' }}</h3>
                            <span v-if="tag" class="chat-tag bubble-modal-tag">{{ tag }}</span>
                        </div>
                        <button ref="closeBtnRef" type="button" class="bubble-modal-close"
                            @click="close" aria-label="关闭">×</button>
                    </div>
                    <div class="bubble-modal-content-layout">
                        <nav v-if="tocItems.length" class="doc-toc bubble-modal-toc" aria-label="内容目录">
                            <div class="doc-toc-title">目录</div>
                            <ul class="doc-toc-list">
                                <li v-for="item in tocItems" :key="item.id" :class="item.level >= 2 ? 'toc-l' + item.level : ''">
                                    <a href="#" :class="{ active: activeTocId === item.id }"
                                       @click.prevent="scrollToHeading(item.id, $event)">{{ item.text }}</a>
                                </li>
                            </ul>
                        </nav>
                        <div ref="bodyRef" class="bubble-modal-body md-preview chat-bubble-md" v-html="html"></div>
                    </div>
                </div>
            </div>
        </Transition>
    </Teleport>
    `
});
