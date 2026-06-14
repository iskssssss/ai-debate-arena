import { defineComponent, ref, computed, onMounted } from '../vue.js';
import { API, CHANNEL_AVATARS } from '../config.js';
import { showToast } from '../composables/useToast.js';
import { loginBadgeClass, loginBadgeText } from '../utils.js';

/**
 * 复制通道对象供表单编辑。
 * @param {object} ch 通道数据
 * @returns {object} 可编辑副本
 */
function cloneChannelForm(ch) {
    return {
        displayName: ch.displayName || '',
        type: ch.type || 'BROWSER',
        baseUrl: ch.baseUrl || 'https://api.deepseek.com',
        model: ch.model || 'deepseek-v4-flash',
        apiKey: ''
    };
}

/**
 * 通道配置面板：重命名、浏览器/API 模式切换、新增自定义 API 通道。
 */
export default defineComponent({
    name: 'ProfilesPanel',
    emits: ['ready-changed', 'api-config-changed'],
    setup(_, { emit, expose }) {
        const channels = ref({});
        const channelForms = ref({});
        const loading = ref(true);
        const error = ref('');
        const savingId = ref('');
        const testingId = ref('');

        const apiConfig = ref({
            baseUrl: 'https://api.deepseek.com',
            model: 'deepseek-v4-flash',
            apiKey: '',
            apiKeyConfigured: false,
            apiKeyMasked: ''
        });
        const apiSaving = ref(false);
        const apiTesting = ref(false);

        const newChannel = ref({
            displayName: '',
            baseUrl: 'https://api.deepseek.com',
            apiKey: '',
            model: 'deepseek-v4-flash'
        });
        const addingChannel = ref(false);

        const channelList = computed(() => Object.values(channels.value));

        /** 统计已就绪通道数。 */
        function countReady(data) {
            return Object.values(data).filter(ch => ch.ready).length;
        }

        /** 拉取通道注册表。 */
        async function fetchChannels() {
            const res = await fetch(API + '/profiles/channels');
            if (!res.ok) throw new Error('读取通道列表失败');
            const data = await res.json();
            channels.value = data;
            const forms = {};
            Object.entries(data).forEach(([id, ch]) => { forms[id] = cloneChannelForm(ch); });
            channelForms.value = forms;
            emit('ready-changed', countReady(data) >= 2);
            return data;
        }

        /** 拉取整理服务 API 配置。 */
        async function fetchApiConfig() {
            const res = await fetch(API + '/profiles/api-config');
            if (!res.ok) return;
            const data = await res.json();
            apiConfig.value = {
                baseUrl: data.baseUrl || 'https://api.deepseek.com',
                model: data.model || 'deepseek-v4-flash',
                apiKey: '',
                apiKeyConfigured: Boolean(data.apiKeyConfigured),
                apiKeyMasked: data.apiKeyMasked || ''
            };
            emit('api-config-changed', {
                configured: data.apiKeyConfigured,
                model: data.model,
                baseUrl: data.baseUrl
            });
        }

        /** 刷新通道与整理 API 配置。 */
        async function refresh(silent = false) {
            if (!silent) loading.value = true;
            error.value = '';
            try {
                await fetchChannels();
                await fetchApiConfig();
            } catch (e) {
                error.value = e.message;
            } finally {
                loading.value = false;
            }
        }

        /** 通道头像字符。 */
        function channelAvatar(ch) {
            if (CHANNEL_AVATARS[ch.id]) return CHANNEL_AVATARS[ch.id];
            return (ch.displayName || ch.id || '?').charAt(0);
        }

        /** API Key 占位提示。 */
        function apiKeyPlaceholder(ch) {
            return ch.apiKeyConfigured ? `已保存 ${ch.apiKeyMasked}，留空不修改` : 'sk-...';
        }

        /** 保存单个通道配置（仅自定义通道）。 */
        async function saveChannel(channelId) {
            const ch = channels.value[channelId];
            if (ch?.builtin) return;
            const form = channelForms.value[channelId];
            if (!form?.displayName?.trim()) {
                showToast('通道名称不能为空', 'error');
                return;
            }
            savingId.value = channelId;
            try {
                const res = await fetch(API + '/profiles/channels/' + channelId, {
                    method: 'PUT',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({
                        displayName: form.displayName.trim(),
                        baseUrl: form.baseUrl?.trim(),
                        model: form.model?.trim(),
                        apiKey: form.apiKey?.trim() || undefined
                    })
                });
                const data = await res.json();
                if (!res.ok) throw new Error(data.message || '保存失败');
                showToast('配置已保存，请点击「检测连接」验证通过后方可参与研讨', 'success');
                await refresh(true);
            } catch (e) {
                showToast('保存失败：' + e.message, 'error');
            } finally {
                savingId.value = '';
            }
        }

        /** 检测通道 API 连通性。 */
        async function testChannel(channelId) {
            const form = channelForms.value[channelId];
            testingId.value = channelId;
            try {
                const res = await fetch(API + '/profiles/channels/' + channelId + '/test', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({
                        baseUrl: form.baseUrl?.trim(),
                        model: form.model?.trim(),
                        apiKey: form.apiKey?.trim() || undefined
                    })
                });
                const data = await res.json();
                showToast(data.message, data.success ? 'success' : 'error');
                if (data.success) await refresh(true);
            } catch (e) {
                showToast('检测失败：' + e.message, 'error');
            } finally {
                testingId.value = '';
            }
        }

        /** 新增自定义 API 通道。 */
        async function addApiChannel() {
            if (!newChannel.value.displayName.trim()) {
                showToast('请填写通道名称', 'error');
                return;
            }
            addingChannel.value = true;
            try {
                const res = await fetch(API + '/profiles/channels', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({
                        displayName: newChannel.value.displayName.trim(),
                        baseUrl: newChannel.value.baseUrl.trim(),
                        apiKey: newChannel.value.apiKey.trim(),
                        model: newChannel.value.model.trim()
                    })
                });
                const data = await res.json();
                if (!res.ok) throw new Error(data.message || '添加失败');
                newChannel.value = { displayName: '', baseUrl: 'https://api.deepseek.com', apiKey: '', model: 'deepseek-v4-flash' };
                showToast('API 通道已添加，请点击「检测连接」验证通过后方可参与研讨', 'success');
                await refresh(true);
            } catch (e) {
                showToast('添加失败：' + e.message, 'error');
            } finally {
                addingChannel.value = false;
            }
        }

        /** 删除自定义 API 通道。 */
        async function deleteChannel(channelId) {
            if (!confirm('确定删除该 API 通道？')) return;
            try {
                await fetch(API + '/profiles/channels/' + channelId, { method: 'DELETE' });
                showToast('通道已删除', 'info');
                await refresh(true);
            } catch (e) {
                showToast('删除失败：' + e.message, 'error');
            }
        }

        /** 浏览器通道：打开登录窗口。 */
        async function setupLogin(channelId) {
            const ch = channels.value[channelId];
            showToast(`正在打开 ${ch?.displayName || channelId} 浏览器窗口，请完成登录`, 'info');
            try {
                const res = await fetch(API + '/profiles/' + channelId + '/setup', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ timeoutSeconds: 300 })
                });
                const data = await res.json();
                showToast(
                    data.loginStatus === 'LOGGED_IN' ? '登录成功' : '登录未完成，请在浏览器窗口中完成登录',
                    data.loginStatus === 'LOGGED_IN' ? 'success' : 'error'
                );
                await refresh(true);
            } catch (e) {
                showToast('请求失败：' + e.message, 'error');
            }
        }

        /** 浏览器通道：健康检测。 */
        async function checkHealth(channelId) {
            try {
                const res = await fetch(API + '/profiles/' + channelId + '/health');
                const data = await res.json();
                const ch = channels.value[channelId];
                showToast(
                    (ch?.displayName || channelId) + (data.healthy ? ' 状态正常' : ' 异常：' + data.details),
                    data.healthy ? 'success' : 'error'
                );
                await refresh(true);
            } catch (e) {
                showToast('检测失败：' + e.message, 'error');
            }
        }

        /** 浏览器通道：清除登录配置。 */
        async function deleteProfile(channelId) {
            const ch = channels.value[channelId];
            if (!confirm(`清除 ${ch?.displayName || channelId} 的本地登录配置？`)) return;
            try {
                await fetch(API + '/profiles/' + channelId, { method: 'DELETE' });
                showToast('登录配置已清除', 'info');
                await refresh(true);
            } catch (e) {
                showToast('清除失败：' + e.message, 'error');
            }
        }

        const apiKeyPlaceholderJudge = computed(() =>
            apiConfig.value.apiKeyConfigured
                ? `已保存 ${apiConfig.value.apiKeyMasked}，留空则不修改`
                : 'sk-...'
        );

        /** 保存整理服务 API 配置。 */
        async function saveApiConfig() {
            apiSaving.value = true;
            try {
                const res = await fetch(API + '/profiles/api-config', {
                    method: 'PUT',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({
                        baseUrl: apiConfig.value.baseUrl.trim(),
                        model: apiConfig.value.model.trim(),
                        apiKey: apiConfig.value.apiKey.trim() || undefined
                    })
                });
                if (!res.ok) throw new Error('保存失败');
                apiConfig.value.apiKey = '';
                showToast('整理 API 配置已保存', 'success');
                await fetchApiConfig();
            } catch (e) {
                showToast('保存失败：' + e.message, 'error');
            } finally {
                apiSaving.value = false;
            }
        }

        /** 检测整理服务 API。 */
        async function testApiConfig() {
            apiTesting.value = true;
            try {
                const res = await fetch(API + '/profiles/api-config/test', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({
                        baseUrl: apiConfig.value.baseUrl.trim(),
                        model: apiConfig.value.model.trim(),
                        apiKey: apiConfig.value.apiKey.trim() || undefined
                    })
                });
                const data = await res.json();
                showToast(data.message, data.success ? 'success' : 'error');
                if (data.success) await fetchApiConfig();
            } catch (e) {
                showToast('检测失败：' + e.message, 'error');
            } finally {
                apiTesting.value = false;
            }
        }

        onMounted(refresh);
        expose({ refresh, fetchChannels });

        return {
            loading, error, channelList, channelForms, channels,
            savingId, testingId, newChannel, addingChannel,
            apiConfig, apiSaving, apiTesting, apiKeyPlaceholderJudge,
            channelAvatar, apiKeyPlaceholder, loginBadgeClass, loginBadgeText,
            refresh, saveChannel, testChannel, addApiChannel, deleteChannel,
            setupLogin, checkHealth, deleteProfile, saveApiConfig, testApiConfig
        };
    },
    template: `
    <div class="profiles-layout">
        <div class="card">
            <div class="callout callout-info">
                <strong>至少配置 2 个就绪通道</strong>方可发起研讨。内置通道须浏览器登录；自定义 API 通道须<strong>检测连接成功</strong>后方可参与。
            </div>
            <div v-if="loading" class="channel-grid"><div class="loading">加载中</div></div>
            <div v-else-if="error" class="callout callout-error">加载失败：{{ error }}</div>
            <div v-else class="channel-grid">
                <div v-for="ch in channelList" :key="ch.id"
                    :class="['channel-card', ch.builtin ? 'channel-card-builtin' : 'channel-card-edit']">
                    <div class="channel-card-head">
                        <div class="channel-avatar">{{ channelAvatar(ch) }}</div>
                        <div class="channel-card-head-main">
                            <div class="channel-card-title-row">
                                <div v-if="ch.builtin" class="channel-name-static">{{ ch.displayName }}</div>
                                <input v-else v-model="channelForms[ch.id].displayName" class="channel-name-input"
                                    placeholder="通道名称" maxlength="24">
                                <div class="channel-card-badges">
                                    <span class="badge" :class="ch.ready ? 'badge-success' : 'badge-warn'">
                                        {{ ch.ready ? '可参与' : '未就绪' }}
                                    </span>
                                    <span v-if="ch.type === 'BROWSER' && ch.loginStatus" class="badge"
                                        :class="loginBadgeClass(ch.loginStatus)">{{ loginBadgeText(ch.loginStatus) }}</span>
                                    <span v-if="!ch.builtin && ch.apiVerified" class="badge badge-success">已验证</span>
                                    <span v-else-if="!ch.builtin && ch.apiKeyConfigured && !ch.apiVerified"
                                        class="badge badge-warn">待检测</span>
                                </div>
                            </div>
                            <span class="channel-card-kind">{{ ch.builtin ? '内置 · 浏览器' : '自定义 API' }}</span>
                        </div>
                    </div>

                    <div class="channel-card-body">
                    <template v-if="ch.builtin">
                        <div class="channel-access-static">
                            <span class="channel-access-label">接入方式</span>
                            <span class="channel-access-value">浏览器登录</span>
                        </div>
                        <p class="channel-card-meta">{{ ch.message || '等待登录' }}</p>
                    </template>

                    <template v-else>
                        <div class="channel-access-static">
                            <span class="channel-access-label">接入方式</span>
                            <span class="channel-access-value">三方 API</span>
                        </div>

                        <div class="form-group">
                            <label>API 地址</label>
                            <input v-model="channelForms[ch.id].baseUrl" placeholder="https://api.deepseek.com">
                        </div>
                        <div class="form-group">
                            <label>API Key</label>
                            <input v-model="channelForms[ch.id].apiKey" type="password"
                                :placeholder="apiKeyPlaceholder(ch)">
                        </div>
                        <div class="form-group">
                            <label>模型</label>
                            <input v-model="channelForms[ch.id].model" placeholder="deepseek-v4-flash">
                        </div>
                        <p class="channel-card-meta">{{ ch.message || '请完善配置并检测连接' }}</p>
                    </template>
                    </div>

                    <div class="channel-card-actions">
                        <template v-if="ch.builtin">
                            <button class="btn btn-outline btn-sm" @click="setupLogin(ch.id)">
                                {{ ch.loginStatus === 'LOGGED_IN' ? '重新登录' : '登录' }}
                            </button>
                            <button class="btn btn-outline btn-sm" @click="checkHealth(ch.id)">检测</button>
                            <button class="btn btn-outline btn-sm" @click="deleteProfile(ch.id)">清除</button>
                        </template>
                        <template v-else>
                            <button class="btn btn-primary btn-sm" :disabled="savingId === ch.id"
                                @click="saveChannel(ch.id)">{{ savingId === ch.id ? '保存中…' : '保存' }}</button>
                            <button class="btn btn-outline btn-sm" :disabled="testingId === ch.id"
                                @click="testChannel(ch.id)">{{ testingId === ch.id ? '检测中…' : '检测连接' }}</button>
                            <button class="btn btn-outline btn-sm" @click="deleteChannel(ch.id)">删除</button>
                        </template>
                    </div>
                </div>
            </div>
            <div class="card-footer">
                <button class="btn btn-outline btn-sm" @click="refresh()">刷新状态</button>
            </div>
        </div>

        <div class="card api-config-card">
            <h3 class="api-config-title">添加 API 通道</h3>
            <div class="api-config-form">
                <div class="form-group">
                    <label>通道名称</label>
                    <input v-model="newChannel.displayName" placeholder="如：Claude API" maxlength="24">
                </div>
                <div class="form-group">
                    <label>API 地址</label>
                    <input v-model="newChannel.baseUrl" placeholder="https://api.example.com">
                </div>
                <div class="form-group">
                    <label>API Key</label>
                    <input v-model="newChannel.apiKey" type="password" placeholder="sk-...">
                </div>
                <div class="form-group">
                    <label>模型</label>
                    <input v-model="newChannel.model" placeholder="model-name">
                </div>
            </div>
            <button class="btn btn-primary btn-sm" :disabled="addingChannel" @click="addApiChannel">
                {{ addingChannel ? '添加中…' : '添加 API 通道' }}
            </button>
            <p class="hint" style="margin-top:10px;">添加后须在通道卡片中点击「检测连接」验证成功，方可参与研讨。</p>
        </div>

        <div class="card api-config-card">
            <h3 class="api-config-title">整理服务 API（研讨材料归纳）</h3>
            <p class="hint api-config-desc">与上方讨论方通道独立，专用于每轮整理与产出文档生成。</p>
            <div class="api-config-form">
                <div class="form-group">
                    <label>API 地址</label>
                    <input v-model="apiConfig.baseUrl" placeholder="https://api.deepseek.com">
                </div>
                <div class="form-group">
                    <label>API Key</label>
                    <input v-model="apiConfig.apiKey" type="password" :placeholder="apiKeyPlaceholderJudge">
                </div>
                <div class="form-group">
                    <label>模型</label>
                    <input v-model="apiConfig.model" placeholder="deepseek-v4-flash">
                </div>
            </div>
            <div class="api-config-actions">
                <button class="btn btn-primary btn-sm" :disabled="apiSaving" @click="saveApiConfig">
                    {{ apiSaving ? '保存中…' : '保存配置' }}
                </button>
                <button class="btn btn-outline btn-sm" :disabled="apiTesting" @click="testApiConfig">
                    {{ apiTesting ? '检测中…' : '检测连接' }}
                </button>
            </div>
        </div>
    </div>
    `
});
