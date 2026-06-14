import { API, CHANNEL_LABELS, CHANNEL_AVATARS } from './config.js';
import { state } from './state.js';
import { toast, badgeForLogin } from './utils.js';

/**
 * 拉取通道状态并更新本地缓存。
 */
export async function fetchProfiles() {
    const res = await fetch(API + '/profiles');
    state.profilesCache = await res.json();
    return state.profilesCache;
}

/**
 * 统计已登录且可参与研讨的通道数量。
 */
export function countEligibleChannels(profiles) {
    return Object.values(profiles).filter(p => p.loginStatus === 'LOGGED_IN').length;
}

/**
 * 判断发起研讨的前置条件是否满足（至少 2 个通道已登录）。
 */
export async function isDebateReady() {
    try {
        const profiles = await fetchProfiles();
        return countEligibleChannels(profiles) >= 2;
    } catch (e) {
        return false;
    }
}

/** 刷新通道列表 UI（卡片网格）。 */
export async function refreshProfiles() {
    const el = document.getElementById('profile-list');
    el.innerHTML = '<div class="loading">加载中</div>';
    try {
        const data = await fetchProfiles();
        let html = '';
        for (const [key, p] of Object.entries(data)) {
            const label = CHANNEL_LABELS[key] || key;
            const avatar = CHANNEL_AVATARS[key] || key.charAt(0).toUpperCase();
            const badge = badgeForLogin(p.loginStatus);
            const loginDisabled = p.loginStatus === 'LOGGED_IN' ? 'disabled' : '';
            html += `<div class="channel-card">
                <div class="channel-card-head">
                    <div class="channel-avatar">${avatar}</div>
                    <div>
                        <div class="channel-card-title">${label} ${badge}</div>
                        <div class="channel-card-platform">${p.platform}</div>
                    </div>
                </div>
                <div class="channel-card-meta">${p.message || '等待登录'}</div>
                <div class="channel-card-actions">
                    <button class="btn btn-primary btn-sm" onclick="setupLogin('${key}')" ${loginDisabled}>登录</button>
                    <button class="btn btn-outline btn-sm" onclick="checkHealth('${key}')">检测</button>
                    <button class="btn btn-outline btn-sm" onclick="deleteProfile('${key}')">清除</button>
                </div>
            </div>`;
        }
        el.innerHTML = html;
    } catch (e) {
        el.innerHTML = `<div class="callout callout-error">加载失败：${e.message}</div>`;
    }
}

/** 打开独立浏览器窗口完成通道登录。 */
export async function setupLogin(platform) {
    const label = CHANNEL_LABELS[platform] || platform;
    toast(`正在打开 ${label} 独立浏览器窗口，请在窗口中完成登录`, 'info');
    try {
        const res = await fetch(API + '/profiles/' + platform + '/setup', {
            method: 'POST', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ timeoutSeconds: 300 })
        });
        const data = await res.json();
        toast(data.loginStatus === 'LOGGED_IN' ? `${label} 登录成功` : `${label} 登录未完成，请在打开的浏览器窗口中完成登录后重新检测`,
            data.loginStatus === 'LOGGED_IN' ? 'success' : 'error');
        refreshProfiles();
    } catch (e) {
        toast('请求失败：' + e.message, 'error');
    }
}

/** 检测通道健康状态。 */
export async function checkHealth(platform) {
    try {
        const res = await fetch(API + '/profiles/' + platform + '/health');
        const data = await res.json();
        toast((CHANNEL_LABELS[platform] || platform) + (data.healthy ? ' 状态正常' : ' 异常：' + data.details),
            data.healthy ? 'success' : 'error');
        await refreshProfiles();
    } catch (e) {
        toast('检测失败：' + e.message, 'error');
    }
}

/** 清除通道本地登录配置。 */
export async function deleteProfile(platform) {
    const label = CHANNEL_LABELS[platform] || platform;
    if (!confirm(`清除 ${label} 的本地登录配置？清除后需重新登录。`)) return;
    try {
        await fetch(API + '/profiles/' + platform, { method: 'DELETE' });
        toast(`${label} 配置已清除`, 'info');
        refreshProfiles();
    } catch (e) {
        toast('清除失败：' + e.message, 'error');
    }
}
