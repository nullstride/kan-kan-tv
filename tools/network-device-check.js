// Device smoke test for the disposable .networkcheck package; never clears the user's main app.
const cp = require('node:child_process');
const fs = require('node:fs/promises');
const path = require('node:path');
const crypto = require('node:crypto');
const assert = require('node:assert/strict');
const serial = process.argv[2];
const evidence = path.resolve(process.argv[3] || 'network-device-evidence');
const adb = path.join(process.env.LOCALAPPDATA, 'Android', 'Sdk', 'platform-tools', 'adb.exe');
const qa = 'com.kankan.iptv.networkcheck';
const main = 'com.kankan.iptv';
if (!serial) throw new Error('Usage: node tools/network-device-check.js <serial> <evidence-dir>');
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
function command(args, binary = false) {
  return new Promise((resolve, reject) => cp.execFile('cmd.exe', ['/d', '/c', adb, '-s', serial, ...args], {
    encoding: binary ? null : 'utf8', timeout: 20000, maxBuffer: 24 * 1024 * 1024, windowsHide: true
  }, (error, stdout, stderr) => error ? reject(new Error(String(stderr || error))) : resolve(stdout)));
}
function nodes(xml) {
  return [...xml.matchAll(/<node\s+([^>]+)>/g)].map(match => {
    const entry = {};
    for (const attr of match[1].matchAll(/([\w-]+)="([^"]*)"/g)) entry[attr[1]] = attr[2];
    return entry;
  });
}
async function capture(name) {
  await command(['shell', 'uiautomator', 'dump', '/sdcard/networkcheck-ui.xml']);
  const xml = await command(['shell', 'cat', '/sdcard/networkcheck-ui.xml']);
  await fs.writeFile(path.join(evidence, name + '.xml'), xml);
  await fs.writeFile(path.join(evidence, name + '.png'), await command(['exec-out', 'screencap', '-p'], true));
  return nodes(xml);
}
function id(list, value) { return list.find(node => node['resource-id'] === qa + ':id/' + value); }
function text(list, value) { return list.find(node => node.text === value); }
async function tap(node) {
  assert(node, 'Required control missing');
  const bounds = node.bounds.match(/\d+/g).map(Number);
  assert(bounds[2] > bounds[0] && bounds[3] > bounds[1], 'Control is off screen');
  await command(['shell', 'input', 'tap', String((bounds[0] + bounds[2]) >> 1), String((bounds[1] + bounds[3]) >> 1)]);
  await delay(250);
}
async function chosenProvider() {
  const prefs = await command(['exec-out', 'run-as', qa, 'cat', 'shared_prefs/bj_unicom_iptv_prefs.xml']);
  return Number(prefs.match(/<int name="isp_provider" value="(\d+)"/)?.[1] ?? -1);
}
async function mainPreferencesHash() {
  const prefs = await command(['exec-out', 'run-as', main, 'cat', 'shared_prefs/bj_unicom_iptv_prefs.xml'], true);
  return crypto.createHash('sha256').update(prefs).digest('hex');
}
async function launch() {
  await command(['shell', 'am', 'force-stop', qa]);
  await command(['shell', 'am', 'start', '-n', qa + '/com.kankan.iptv.ui.MainActivity']);
  await delay(1000);
}
async function run() {
  await fs.mkdir(evidence, {recursive: true});
  assert((await command(['shell', 'pm', 'path', qa])).includes('package:'), 'Install the QA APK first');
  const before = await mainPreferencesHash();
  // Only this disposable package is reset, so first-use behavior is reproducible.
  await command(['shell', 'pm', 'clear', qa]);
  await launch();
  let list = await capture('01-first-use');
  assert(text(list, '北京移动家庭网络'));
  assert.equal(text(list, '下一步').enabled, 'false');
  await tap(text(list, '稍后设置'));
  await command(['shell', 'input', 'keyevent', '186']);
  list = await capture('01-skipped-settings');
  await tap(id(list, 'btn_settings_save'));
  assert.equal(await chosenProvider(), -1, 'Saving other settings must not select Unicom');
  await launch();
  list = await capture('01-welcome-still-required');
  await tap(text(list, '北京移动家庭网络'));
  list = await capture('02-mobile-selected');
  await tap(text(list, '使用并播放'));
  assert.equal(await chosenProvider(), 1);
  await launch();
  list = await capture('03-mobile-persisted');
  assert(!text(list, '北京移动家庭网络'), 'Welcome should not replace an explicit source choice');
  console.log('PASS: first-use selection and Mobile persistence');
  await command(['shell', 'input', 'keyevent', '186']);
  list = await capture('04-network-settings');
  for (let attempt = 0; !id(list, 'btn_check_network_sources') && attempt < 4; attempt++) {
    const panel = id(list, 'settings_panel_network');
    assert(panel, 'Network settings panel missing');
    const b = panel.bounds.match(/\d+/g).map(Number);
    await command(['shell', 'input', 'swipe', String((b[0] + b[2]) >> 1), String(b[3] - 40), String((b[0] + b[2]) >> 1), String(b[1] + 40), '350']);
    list = await capture('04-network-settings-scrolled-' + attempt);
  }
  await tap(id(list, 'btn_check_network_sources'));
  // An offline check can finish before a UI dump. Do not mistake closing the result
  // for cancelling an in-progress check; cancellation has separate lifecycle tests.
  for (let attempt = 0; attempt < 9; attempt++) {
    if (attempt) await delay(3500);
    list = await capture('07-result-wait-' + attempt);
    if (text(list, '检测结果')) break;
  }
  assert(text(list, '检测结果'), 'A bounded check must finish and show a result');
  assert.equal(await chosenProvider(), 1);
  assert(list.some(node => node.text.includes('收到数据')));
  assert(text(list, '查看详情'));
  assert(!list.some(node => node.text.includes('Failed to connect')));
  await capture('08-check-result');
  await tap(text(list, '查看详情'));
  list = await capture('09-details-expanded');
  assert(text(list, '收起详情'));
  assert(text(list, '关闭'));
  await tap(text(list, '关闭'));
  assert.equal(await chosenProvider(), 1);
  const after = await mainPreferencesHash();
  assert.equal(before, after, 'The primary app settings must remain untouched');
  await fs.writeFile(path.join(evidence, 'results.json'), JSON.stringify({
    serial, package: qa,
    passed: ['first-use requires explicit source', 'Mobile source persists after restart',
      'saving after skipped welcome does not choose Unicom', 'network check action visible',
      'source checks finish with results', 'details expand while close action remains available',
      'diagnostics never switch the source automatically', 'primary app preferences unchanged'],
    cancellationVerifiedByThisScript: false,
    actualOperatorPlaybackVerified: false
  }, null, 2));
  console.log('PASS: results visible; source and primary app settings unchanged');
}
run().catch(error => { console.error(error.stack); process.exitCode = 1; });
