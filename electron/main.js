const { app, BrowserWindow, dialog } = require('electron');
const { spawn } = require('child_process');
const fs = require('fs');
const http = require('http');
const net = require('net');
const path = require('path');
const { installAppMenu } = require('./app-menu');

let mainWindow;
let backendProcess;

/**
 * 判断当前是否为开发模式。
 */
function isDevMode() {
  return !app.isPackaged;
}

/**
 * 获取 Spring Boot 后端 jar 的路径。
 */
function getBackendJarPath() {
  if (isDevMode()) {
    return path.join(app.getAppPath(), 'target', 'ai-debate-arena-1.0.0-SNAPSHOT.jar');
  }
  return path.join(process.resourcesPath, 'backend', 'app.jar');
}

/**
 * 寻找一个可用的本机端口。
 */
function findFreePort() {
  return new Promise((resolve, reject) => {
    const server = net.createServer();
    server.unref();
    server.on('error', reject);
    server.listen(0, '127.0.0.1', () => {
      const address = server.address();
      server.close(() => resolve(address.port));
    });
  });
}

/**
 * 检测系统 Java 是否可用，并尽量确认版本满足 Java 17。
 */
function checkJavaRuntime() {
  return new Promise((resolve, reject) => {
    const java = spawn('java', ['-version'], { stdio: ['ignore', 'ignore', 'pipe'] });
    let output = '';

    java.stderr.on('data', (chunk) => {
      output += chunk.toString();
    });
    java.on('error', () => reject(new Error('未检测到 Java。请安装 JDK 17 或更高版本后重试。')));
    java.on('close', (code) => {
      if (code !== 0) {
        reject(new Error('Java 检测失败。请确认 JDK 17 已正确安装。'));
        return;
      }
      const match = output.match(/version "(\d+)/);
      const major = match ? Number(match[1]) : 0;
      if (major < 17) {
        reject(new Error('当前 Java 版本低于 17。请安装 JDK 17 或更高版本后重试。'));
        return;
      }
      resolve();
    });
  });
}

/**
 * 启动内置 Spring Boot 后端。
 */
async function startBackend(port) {
  await checkJavaRuntime();

  const jarPath = getBackendJarPath();
  if (!fs.existsSync(jarPath)) {
    throw new Error(`未找到后端程序：${jarPath}\n请先执行 npm run backend:package。`);
  }

  backendProcess = spawn('java', [
    '-jar',
    jarPath,
    '--spring.profiles.active=desktop',
    `--server.port=${port}`,
    '--server.address=127.0.0.1',
    '--desktop.enabled=true'
  ], {
    cwd: path.dirname(jarPath),
    windowsHide: true,
    stdio: isDevMode() ? 'inherit' : 'ignore'
  });

  backendProcess.on('exit', (code) => {
    if (code !== 0 && mainWindow && !mainWindow.isDestroyed()) {
      mainWindow.webContents.send('backend-exited', code);
    }
  });
}

/**
 * 等待后端 HTTP 服务可访问。
 */
function waitForBackend(url, timeoutMs = 90000) {
  const startedAt = Date.now();

  return new Promise((resolve, reject) => {
    const probe = () => {
      const req = http.get(url, (res) => {
        res.resume();
        resolve();
      });

      req.on('error', () => {
        if (Date.now() - startedAt > timeoutMs) {
          reject(new Error('后端启动超时，请查看日志或重新启动客户端。'));
          return;
        }
        setTimeout(probe, 800);
      });
      req.setTimeout(3000, () => {
        req.destroy();
      });
    };

    probe();
  });
}

/**
 * 创建 Electron 主窗口。
 */
function createWindow(url) {
  mainWindow = new BrowserWindow({
    width: 1180,
    height: 820,
    minWidth: 960,
    minHeight: 680,
    title: '方案研讨台',
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false
    }
  });

  mainWindow.loadURL(`${url}?desktop=1`);
  installAppMenu(mainWindow);
}

/**
 * 关闭后端进程。
 */
function stopBackend() {
  if (backendProcess && !backendProcess.killed) {
    backendProcess.kill();
    backendProcess = null;
  }
}

/**
 * 启动桌面应用。
 */
async function bootstrap() {
  try {
    const port = await findFreePort();
    const url = `http://127.0.0.1:${port}`;
    await startBackend(port);
    await waitForBackend(url);
    createWindow(url);
  } catch (error) {
    dialog.showErrorBox('启动失败', error.message);
    app.quit();
  }
}

app.whenReady().then(bootstrap);

app.on('window-all-closed', () => {
  stopBackend();
  if (process.platform !== 'darwin') {
    app.quit();
  }
});

app.on('before-quit', stopBackend);

app.on('activate', () => {
  if (BrowserWindow.getAllWindows().length === 0 && mainWindow) {
    mainWindow.show();
  }
});
