// Native Windows/ADB smoke check. Restores the original source after its save test.
const cp = require('node:child_process');
const fs = require('node:fs/promises');
const path = require('node:path');
const assert = require('node:assert/strict');
const serial = process.argv[2];
const directory = path.resolve(process.argv[3] || 'device-source-settings');
if (!serial) throw new Error('Usage: node tools/source-settings-device-check.js <serial> <evidence-dir>');
const adb = path.join(process.env.LOCALAPPDATA, 'Android', 'Sdk', 'platform-tools', 'adb.exe');
const packageName = 'com.kankan.iptv';
const results = [];
function command(args, binary = false) {
  return new Promise((resolve, reject) => cp.execFile('cmd.exe', ['/d', '/c', adb, '-s', serial, ...args],
    {encoding: binary ? null : 'utf8', timeout: 20000, maxBuffer: 24 * 1024 * 1024, windowsHide: true},
    (error, stdout, stderr) => error ? reject(new Error(String(stderr || error))) : resolve(stdout)));
}
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
function nodes(xml) {
  return [...xml.matchAll(/<node\s+([^>]+)>/g)].map(match => {
    const entry = {};
    for (const attr of match[1].matchAll(/([\w-]+)="([^"]*)"/g)) entry[attr[1]] = attr[2];
    return entry;
  });
}
async function capture(name) {
  await command(['shell', 'uiautomator', 'dump', '/sdcard/utv-source-settings.xml']);
  const xml = await command(['shell', 'cat', '/sdcard/utv-source-settings.xml']);
  await fs.writeFile(path.join(directory, name + '.xml'), xml);
  await fs.writeFile(path.join(directory, name + '.png'), await command(['exec-out', 'screencap', '-p'], true));
  return nodes(xml);
}
function byId(list, id) { return list.find(node => node['resource-id'] === packageName + ':id/' + id); }
async function tap(node) {
  assert(node, 'Control missing');
  const rect = node.bounds.match(/\d+/g).map(Number);
  assert(rect[2] > rect[0] && rect[3] > rect[1], 'Control is off screen');
  await command(['shell', 'input', 'tap', String(Math.round((rect[0] + rect[2]) / 2)), String(Math.round((rect[1] + rect[3]) / 2))]);
  await delay(200);
}
async function key(code) { await command(['shell', 'input', 'keyevent', String(code)]); await delay(200); }
async function channels(name) {
  await key(186); // PROG_BLUE opens settings using the same public entry as the gear.
  let list = await capture(name + '-network');
  await tap(byId(list, 'settings_tab_channels'));
  return capture(name + '-channels');
}
async function sourcePicker(list, name) {
  await tap(byId(list, 'btn_settings_source'));
  const picker = await capture(name);
  return picker.filter(node => node.class === 'android.widget.CheckedTextView');
}
async function checkTools(list) {
  await tap(byId(list, 'settings_tab_playback'));
  list = await capture('playback-settings');
  assert(byId(list, 'rb_core_exo').text.includes('UTVPlayer'));
  assert.equal(byId(list, 'settings_tab_playback').selected, 'true');
  results.push('UTV core label and decoder options visible');
  await tap(byId(list, 'settings_tab_channels'));
  list = await capture('channel-tools-top');
  assert.equal(byId(list, 'settings_tab_channels').selected, 'true', 'One tap must switch back to channel settings');
  for (let attempt = 0; attempt < 3; attempt++) {
    const exportButton = byId(list, 'btn_settings_export');
    if (exportButton && Number(exportButton.bounds.match(/\d+/g)[3]) - Number(exportButton.bounds.match(/\d+/g)[1]) >= 100) break;
    const panel = byId(list, 'settings_panel_channels');
    const rect = panel.bounds.match(/\d+/g).map(Number);
    await command(['shell', 'input', 'swipe', String((rect[0] + rect[2]) >> 1), String(rect[3] - 70), String((rect[0] + rect[2]) >> 1), String(rect[1] + 70), '400']);
    list = await capture('channel-tools');
  }
  await tap(byId(list, 'btn_settings_export'));
  list = await capture('export-result');
  assert(list.some(node => node.text === '导出成功'));
  await tap(list.find(node => node['resource-id'] === 'android:id/button1'));
  list = await capture('channel-tools-after-export');
  results.push('current M3U and CSV export accessible');
  await tap(byId(list, 'btn_settings_scan'));
  list = await capture('scanner');
  assert(byId(list, 'btn_start_scan'));
  results.push('scan is reachable by a normal settings action');
  await key(4);
  list = await channels('verified');
}
async function run() {
  await fs.mkdir(directory, {recursive: true});
  await command(['shell', 'am', 'force-stop', packageName]);
  await command(['shell', 'am', 'start', '-n', packageName + '/.ui.MainActivity']);
  await delay(1200);
  let list = await channels('initial');
  if (process.argv[4] === '--tools-only') {
    await checkTools(list);
    await fs.writeFile(path.join(directory, 'tool-results.json'), JSON.stringify({serial, passed: results}, null, 2));
    console.log(JSON.stringify({passed: results}, null, 2));
    return;
  }
  assert(byId(list, 'btn_settings_source'));
  const initialSummary = byId(list, 'tv_settings_source_summary').text;
  let choices = await sourcePicker(list, 'source-picker');
  assert.equal(choices.length, 5);
  const original = choices.findIndex(choice => choice.checked === 'true');
  assert(original >= 0);
  const target = original === 2 ? 0 : 2;
  await tap(choices[target]);
  list = await capture('source-draft');
  assert(byId(list, 'tv_settings_source_summary').text.startsWith('待应用'));
  await tap(byId(list, 'btn_settings_cancel'));
  list = await channels('cancelled');
  assert.equal(byId(list, 'tv_settings_source_summary').text, initialSummary);
  results.push('source selection and cancel preserve active source');
  choices = await sourcePicker(list, 'save-picker');
  await tap(choices[target]);
  list = await capture('before-save');
  await tap(byId(list, 'btn_settings_save'));
  try {
    list = await channels('saved');
    choices = await sourcePicker(list, 'saved-picker');
    assert.equal(choices.findIndex(choice => choice.checked === 'true'), target);
    results.push('save persists the selected source');
  } finally {
    await tap(choices[original]);
    list = await capture('restore-draft');
    await tap(byId(list, 'btn_settings_save'));
  }
  list = await channels('restored');
  assert.equal(byId(list, 'tv_settings_source_summary').text, initialSummary);
  results.push('original source restored');
  await tap(byId(list, 'btn_settings_import_m3u'));
  list = await capture('m3u-import');
  assert(byId(list, 'et_custom_m3u_content'));
  await tap(byId(list, 'btn_load_sample_m3u'));
  list = await capture('m3u-inspected');
  assert(byId(list, 'tv_custom_m3u_report').text.includes('有效频道'));
  await tap(list.find(node => node['resource-id'] === 'android:id/button1'));
  list = await capture('m3u-staged');
  assert(byId(list, 'tv_settings_source_summary').text.startsWith('待应用'));
  await tap(byId(list, 'btn_settings_cancel'));
  list = await channels('m3u-cancelled');
  assert.equal(byId(list, 'tv_settings_source_summary').text, initialSummary);
  results.push('M3U inspect and stage do not apply before save; cancel discards draft');
  await checkTools(list);
  await fs.writeFile(path.join(directory, 'results.json'), JSON.stringify({serial, passed: results, sourceRestored: true}, null, 2));
  console.log(JSON.stringify({passed: results, sourceRestored: true}, null, 2));
}
run().catch(error => { console.error(error.stack); process.exitCode = 1; });
