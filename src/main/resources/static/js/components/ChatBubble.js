import { defineComponent, computed } from '../vue.js';
import { renderMarkdown } from '../composables/useMarkdown.js';

/**
 * 单条聊天气泡：Markdown 仅在 content 变化时重新渲染。
 */
export default defineComponent({
    name: 'ChatBubble',
    props: {
        sender: { type: String, default: '' },
        tag: { type: String, default: '' },
        charCount: { type: Number, default: 0 },
        content: { type: String, default: '' },
        avatar: { type: String, default: '?' },
        avatarParty: { type: String, default: 'party-default' },
        bubbleClass: { type: String, default: '' },
        expandable: { type: Boolean, default: true }
    },
    emits: ['expand'],
    setup(props, { emit }) {
        /** 缓存 Markdown HTML，避免父组件轮询更新时重复解析。 */
        const html = computed(() => renderMarkdown(props.content));

        /** 点击气泡触发放大查看。 */
        function onExpand(event) {
            if (props.expandable && props.content?.trim()) {
                emit('expand', { title: props.sender, tag: props.tag, content: props.content });
            }
            event?.currentTarget?.blur();
        }

        /** 键盘触发放大（Enter / 空格）。 */
        function onKeydown(e) {
            if (e.key === 'Enter' || e.key === ' ') {
                e.preventDefault();
                onExpand();
            }
        }

        return { html, onExpand, onKeydown };
    },
    template: `
    <div class="chat-msg" :class="{ 'chat-msg-judge': avatarParty === 'judge', 'chat-msg-right': avatarParty === 'judge-right' }">
        <div class="chat-avatar" :class="avatarParty.startsWith('judge') ? 'chat-avatar-judge' : 'chat-avatar-' + avatarParty">{{ avatar }}</div>
        <div class="chat-body">
            <div v-if="sender || tag || charCount" class="chat-meta">
                <span v-if="sender" class="chat-sender">{{ sender }}</span>
                <span v-if="tag" class="chat-tag">{{ tag }}</span>
                <span v-if="charCount">{{ charCount }} 字</span>
            </div>
            <div v-if="expandable && content?.trim()"
                 class="chat-bubble chat-bubble-expandable" :class="bubbleClass"
                 role="button" tabindex="0" title="点击查看完整内容"
                 @click="onExpand($event)" @keydown="onKeydown">
                <div class="chat-bubble-scroll">
                    <div class="md-preview chat-bubble-md" v-html="html"></div>
                </div>
            </div>
            <div v-else class="chat-bubble" :class="bubbleClass">
                <div class="chat-bubble-scroll">
                    <div v-if="content" class="md-preview chat-bubble-md" v-html="html"></div>
                    <slot v-else></slot>
                </div>
            </div>
            <slot name="footer"></slot>
        </div>
    </div>
    `
});
