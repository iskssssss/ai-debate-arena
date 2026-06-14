import { defineComponent } from '../vue.js';
import { STATE_LABELS } from '../config.js';

/**
 * 概览时间线单步节点：有锚点时可点击跳转，无锚点时静态展示。
 */
export default defineComponent({
    name: 'OverviewStep',
    props: {
        step: { type: Object, required: true },
        /** 当前选中的讨论记录锚点 ID，用于高亮对应步骤。 */
        selectedAnchor: { type: String, default: null }
    },
    emits: ['anchor-click'],
    setup(props, { emit }) {
        /** 点击可跳转步骤时通知父组件定位讨论记录。 */
        function onClick(event) {
            if (props.step.anchor) {
                emit('anchor-click', props.step.anchor, event);
            }
        }

        return { STATE_LABELS, onClick };
    },
    template: `
    <component :is="step.anchor ? 'button' : 'div'"
        :type="step.anchor ? 'button' : undefined"
        class="overview-step"
        :class="[
            step.state,
            step.anchor ? 'overview-step-link' : '',
            { 'overview-step-selected': step.anchor && selectedAnchor === step.anchor }
        ]"
        @click="step.anchor && onClick($event)">
        <div class="overview-rail">
            <div class="overview-index">{{ step.indexText }}</div>
            <div v-if="!step.isLast" class="overview-line"></div>
        </div>
        <div class="overview-content">
            <div class="overview-title">{{ step.label }}</div>
            <div v-if="step.detail" class="overview-detail">{{ step.detail }}</div>
            <div v-if="step.isActiveStep && step.children?.length" class="overview-children">
                <div v-for="(c, ci) in step.children" :key="ci" class="overview-child">
                    <span class="overview-child-label">{{ c.label }}</span>
                    <span class="overview-child-state">{{ STATE_LABELS[c.state] || c.state }}{{ c.detail ? ' · ' + c.detail : '' }}</span>
                </div>
            </div>
        </div>
    </component>
    `
});
