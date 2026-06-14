const { spawn } = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');

/**
 * 解析当前机器可用的 Maven 命令。
 */
function resolveMavenCommand() {
  if (process.env.MAVEN_CMD) {
    return process.env.MAVEN_CMD;
  }

  const tempMaven = path.join(os.tmpdir(), 'apache-maven', 'bin', process.platform === 'win32' ? 'mvn.cmd' : 'mvn');
  if (fs.existsSync(tempMaven)) {
    return tempMaven;
  }

  return process.platform === 'win32' ? 'mvn.cmd' : 'mvn';
}

/**
 * 构建 Spring Boot 可执行 jar，供 Electron 启动和打包使用。
 */
function buildBackend() {
  const mvn = resolveMavenCommand();
  const child = spawn(mvn, ['-q', '-DskipTests', 'package'], {
    cwd: path.join(__dirname, '..'),
    stdio: 'inherit',
    shell: process.platform === 'win32'
  });

  child.on('exit', (code) => {
    process.exit(code ?? 1);
  });
  child.on('error', (error) => {
    console.error(`后端构建失败：${error.message}`);
    process.exit(1);
  });
}

buildBackend();
