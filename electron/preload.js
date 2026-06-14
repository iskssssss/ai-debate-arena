const { contextBridge, ipcRenderer } = require('electron');

/**
 * 暴露最小桌面端能力，保持页面与 Node 环境隔离。
 */
contextBridge.exposeInMainWorld('desktopClient', {
  isDesktop: true,
  onBackendExited(callback) {
    ipcRenderer.on('backend-exited', (_, code) => callback(code));
  },
  onNavigate(callback) {
    ipcRenderer.on('app-navigate', (_, tab) => callback(tab));
  },
  onAppAction(callback) {
    ipcRenderer.on('app-action', (_, action) => callback(action));
  }
});
