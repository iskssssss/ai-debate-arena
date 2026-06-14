import { ref } from '../vue.js';

/** 全局 Toast 消息队列（响应式）。 */
const toasts = ref([]);
let toastId = 0;

/**
 * 显示右下角 Toast 提示。
 * @param {string} msg 提示文本
 * @param {'info'|'success'|'error'} type 类型
 */
export function showToast(msg, type = 'info') {
    const id = ++toastId;
    toasts.value.push({ id, msg, type });
    setTimeout(() => {
        toasts.value = toasts.value.filter(t => t.id !== id);
    }, 3400);
}

/** 提供 Toast 状态与方法的 composable。 */
export function useToast() {
    return { toasts, showToast };
}
