import { defineComponent, TransitionGroup } from '../vue.js';
import { useToast } from '../composables/useToast.js';

/**
 * 全局 Toast 提示容器（带入场/离场过渡动画）。
 */
export default defineComponent({
    name: 'ToastContainer',
    components: { TransitionGroup },
    setup() {
        const { toasts } = useToast();
        return { toasts };
    },
    template: `
    <div id="toast-root" aria-live="polite">
        <TransitionGroup name="toast" tag="div" class="toast-stack">
            <div v-for="t in toasts" :key="t.id" class="toast" :class="'toast-' + t.type">{{ t.msg }}</div>
        </TransitionGroup>
    </div>
    `
});
