// Native Windows entry point; no PowerShell or shell command interpolation.
const cp = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname, '..');
const command = process.argv[2];
let child;
if (['privacy-check', 'privacy-install', 'privacy-test'].includes(command)) {
  const args = command === 'privacy-test' ? ['--test', path.join(__dirname, 'privacy-check.test.js')]
    : [path.join(__dirname, 'privacy-check.js'), command === 'privacy-install' ? 'install' : 'history',
      ...(command === 'privacy-check' ? ['HEAD'] : [])];
  child = cp.spawn(process.execPath, args, {cwd: root, stdio: 'inherit', windowsHide: true});
} else if (command === 'build' || command === 'rebuild' || command === 'package') {
  const temporary = path.join(process.env.USERPROFILE, '.codex', 'tmp', 'iptv-java');
  fs.mkdirSync(temporary, { recursive: true });
  const jvmPath = temporary.replaceAll('\\', '/');
  const tasks = command === 'package' ? (process.argv.includes('--clean') ? 'clean assembleDebug' : 'assembleDebug')
    : command === 'rebuild' ? 'clean testDebugUnitTest assembleDebug' : 'testDebugUnitTest assembleDebug';
  const qaNetwork = process.argv.includes('--qa-network');
  const isolation = process.argv.includes('--isolated-network') || qaNetwork
    ? ' --init-script tools/network-validation.init.gradle' + (qaNetwork ? ' -PnetworkCheckQa=true' : '') : '';
  child = cp.spawn('cmd.exe', ['/d', '/c', `gradlew.bat --no-daemon${isolation} ${tasks}`], {
    cwd: root, stdio: 'inherit', windowsHide: true,
    env: { ...process.env, JAVA_TOOL_OPTIONS: `-Djava.io.tmpdir=${jvmPath} -Djdk.net.unixdomain.tmpdir=${jvmPath}` }
  });
} else if (command === 'install') {
  const adb = path.join(process.env.LOCALAPPDATA, 'Android', 'Sdk', 'platform-tools', 'adb.exe');
  const serial = process.argv[3];
  if (!serial) throw new Error('Usage: tools\\aio.cmd install <adb-serial>');
  child = cp.spawn(adb, ['-s', serial, 'install', '--no-streaming', '-r',
    path.join(root, 'app', 'build', 'outputs', 'apk', 'debug', 'app-debug.apk')],
    { cwd: root, stdio: 'inherit', windowsHide: true });
} else {
  console.error('Usage: tools\\aio.cmd privacy-check | privacy-install | privacy-test | build | rebuild | package [--clean] [--isolated-network | --qa-network] | install <adb-serial>');
  process.exit(2);
}
child.on('error', error => { console.error(error); process.exitCode = 1; });
child.on('exit', code => { process.exitCode = code === null ? 1 : code; });
