const { Menu, dialog, app, shell } = require('electron');

/**
 * 向渲染进程发送页面导航指令。
 */
function sendNavigate(mainWindow, tab) {
  if (mainWindow && !mainWindow.isDestroyed()) {
    mainWindow.webContents.send('app-navigate', tab);
  }
}

/**
 * 向渲染进程发送应用级操作指令。
 */
function sendAction(mainWindow, action) {
  if (mainWindow && !mainWindow.isDestroyed()) {
    mainWindow.webContents.send('app-action', action);
  }
}

/**
 * 显示关于对话框。
 */
function showAbout(mainWindow) {
  dialog.showMessageBox(mainWindow, {
    type: 'info',
    title: '关于方案研讨台',
    message: '方案研讨台',
    detail: [
      '跨平台桌面客户端',
      '多方 AI 独立出方案，交叉审阅后整理为开发文档。',
      '',
      `版本 ${app.getVersion()}`
    ].join('\n'),
    buttons: ['确定'],
    noLink: true
  });
}

/**
 * 构建桌面端应用菜单（替换默认英文 File/Edit/View 菜单）。
 */
function buildAppMenu(mainWindow) {
  const isMac = process.platform === 'darwin';
  const isDev = !app.isPackaged;

  const template = [
    ...(isMac ? [{
      label: app.name,
      submenu: [
        { label: '关于方案研讨台', click: () => showAbout(mainWindow) },
        { type: 'separator' },
        { role: 'services', label: '服务' },
        { type: 'separator' },
        { role: 'hide', label: '隐藏' },
        { role: 'hideOthers', label: '隐藏其他' },
        { role: 'unhide', label: '显示全部' },
        { type: 'separator' },
        { role: 'quit', label: '退出方案研讨台' }
      ]
    }] : []),
    {
      label: '文件',
      submenu: [
        { label: '刷新页面', accelerator: 'CmdOrCtrl+R', role: 'reload' },
        { type: 'separator' },
        isMac
          ? { role: 'close', label: '关闭窗口', accelerator: 'CmdOrCtrl+W' }
          : { role: 'quit', label: '退出', accelerator: 'Alt+F4' }
      ]
    },
    {
      label: '编辑',
      submenu: [
        { role: 'undo', label: '撤销' },
        { role: 'redo', label: '重做' },
        { type: 'separator' },
        { role: 'cut', label: '剪切' },
        { role: 'copy', label: '复制' },
        { role: 'paste', label: '粘贴' },
        { role: 'selectAll', label: '全选' }
      ]
    },
    {
      label: '导航',
      submenu: [
        {
          label: '新建研讨',
          accelerator: 'CmdOrCtrl+1',
          click: () => sendNavigate(mainWindow, 'debate')
        },
        {
          label: '通道配置',
          accelerator: 'CmdOrCtrl+2',
          click: () => sendNavigate(mainWindow, 'profiles')
        },
        {
          label: '研讨历史',
          accelerator: 'CmdOrCtrl+3',
          click: () => sendNavigate(mainWindow, 'history')
        }
      ]
    },
    {
      label: '通道',
      submenu: [
        {
          label: '刷新通道状态',
          accelerator: 'CmdOrCtrl+Shift+R',
          click: () => sendAction(mainWindow, 'refresh-profiles')
        },
        { type: 'separator' },
        {
          label: '打开 Profile 目录',
          click: () => {
            const profileDir = require('path').join(
              require('os').homedir(),
              '.ai-debate-arena',
              'profiles'
            );
            shell.openPath(profileDir);
          }
        }
      ]
    },
    {
      label: '视图',
      submenu: [
        { role: 'resetZoom', label: '实际大小', accelerator: 'CmdOrCtrl+0' },
        { role: 'zoomIn', label: '放大', accelerator: 'CmdOrCtrl+Plus' },
        { role: 'zoomOut', label: '缩小', accelerator: 'CmdOrCtrl+-' },
        { type: 'separator' },
        { role: 'togglefullscreen', label: '全屏', accelerator: 'F11' },
        ...(isDev ? [
          { type: 'separator' },
          { role: 'toggleDevTools', label: '开发者工具', accelerator: 'F12' }
        ] : [])
      ]
    },
    {
      label: '帮助',
      submenu: [
        ...(isMac ? [] : [{ label: '关于方案研讨台', click: () => showAbout(mainWindow) }]),
        ...(isMac ? [] : [{ type: 'separator' }]),
        {
          label: '使用说明',
          click: () => {
            sendNavigate(mainWindow, 'profiles');
            sendAction(mainWindow, 'show-login-hint');
          }
        }
      ]
    }
  ];

  return Menu.buildFromTemplate(template);
}

/**
 * 安装应用菜单到当前主窗口。
 */
function installAppMenu(mainWindow) {
  Menu.setApplicationMenu(buildAppMenu(mainWindow));
}

module.exports = { installAppMenu };
