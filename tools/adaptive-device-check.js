// ADB UI smoke check. Compact mode temporarily simulates a 640 x 320dp landscape
// display on the attached device and always restores its original WM overrides.
const cp = require('node:child_process');
const fs = require('node:fs/promises');
const path = require('node:path');
const assert = require('node:assert/strict');
const serial = process.argv[2];
const directory = path.resolve(process.argv[3] || 'adaptive-device-check');
const compact = process.argv.includes('--compact');
const phone = compact || process.argv.includes('--phone');
if (!serial) throw new Error('Usage: node tools/adaptive-device-check.js <serial> <evidence-dir> [--compact | --phone]');
const adb = path.join(process.env.LOCALAPPDATA, 'Android', 'Sdk', 'platform-tools', 'adb.exe');
const pkg = 'com.bjunicom.iptv';
const passed = [];
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
const command = (args, binary = false) => new Promise((resolve, reject) => {
  cp.execFile('cmd.exe', ['/d', '/c', adb, '-s', serial, ...args],
    {encoding: binary ? null : 'utf8', timeout: 25000, maxBuffer: 24 * 1024 * 1024, windowsHide: true},
    (error, stdout, stderr) => error ? reject(new Error(String(stderr || error))) : resolve(stdout));
});
function nodes(xml) {
  return [...xml.matchAll(/<node\s+([^>]+)>/g)].map(match => {
    const entry = {};
    for (const attr of match[1].matchAll(/([\w-]+)="([^"]*)"/g)) entry[attr[1]] = attr[2];
    return entry;
  });
}
const byId = (list, id) => list.find(node => node['resource-id'] === pkg + ':id/' + id);
const bounds = node => { assert(node, 'Control missing'); return node.bounds.match(/\d+/g).map(Number); };
function visible(list, id, minimumHeight = 1) {
  const node = byId(list, id);
  const rect = bounds(node);
  assert(rect[2] > rect[0] && rect[3] - rect[1] >= minimumHeight, id + ' is clipped');
  return node;
}
async function capture(name) {
  await command(['shell', 'uiautomator', 'dump', '/sdcard/utv-adaptive.xml']);
  const xml = await command(['shell', 'cat', '/sdcard/utv-adaptive.xml']);
  await fs.writeFile(path.join(directory, name + '.xml'), xml);
  await fs.writeFile(path.join(directory, name + '.png'), await command(['exec-out', 'screencap', '-p'], true));
  return nodes(xml);
}
async function tap(node) {
  const rect = bounds(node);
  assert(rect[2] > rect[0] && rect[3] > rect[1], 'Cannot tap a clipped control');
  await command(['shell', 'input', 'tap', String((rect[0] + rect[2]) >> 1), String((rect[1] + rect[3]) >> 1)]);
  await delay(250);
}
async function key(code) { await command(['shell', 'input', 'keyevent', String(code)]); await delay(200); }
async function launch() {
  await command(['shell', 'am', 'force-stop', pkg]);
  await command(['shell', 'am', 'start', '-n', pkg + '/.ui.MainActivity']);
  await delay(1000);
}
async function run() {
  await fs.mkdir(directory, {recursive: true});
  const original = {
    size: await command(['shell', 'wm', 'size']),
    density: await command(['shell', 'wm', 'density'])
  };
  await fs.writeFile(path.join(directory, 'original-display.json'), JSON.stringify(original, null, 2));
  try {
    if (compact) {
      await command(['shell', 'wm', 'size', '1280x640']);
      await command(['shell', 'wm', 'density', '320']);
      await delay(800);
    }
    await launch();
    let list = await capture('01-player');
    visible(list, 'btn_toggle_drawer', 80);
    visible(list, 'btn_prev_channel', 80);
    visible(list, 'btn_numpad_dialog', 80);
    assert.equal(!!byId(list, 'btn_phone_more'), phone, 'Wrong device presentation');
    if (phone) {
      const bar = bounds(visible(list, 'glass_player_bar'));
      const key = bounds(visible(list, 'btn_numpad_dialog'));
      assert(bar[3] - bar[1] <= (key[3] - key[1]) * 1.6, 'Phone bar must remain a single compact row');
      visible(list, 'phone_favorite_surface');
      await tap(byId(list, 'btn_phone_more'));
      list = await capture('01-more-menu');
      const actions = new Set();
      for (let attempt = 0; attempt < 4; attempt++) {
        const menu = bounds(visible(list, 'phone_more_menu'));
        list.filter(node => node.clickable === 'true' && node['content-desc'])
          .forEach(node => actions.add(node['content-desc']));
        if (['设置', '节目源', '导入 M3U', '数字选台', '画面比例', '重新连接', '播放信息', '频道扫描']
          .every(title => actions.has(title))) break;
        await command(['shell', 'input', 'swipe', String((menu[0] + menu[2]) >> 1), String(menu[3] - 12),
          String((menu[0] + menu[2]) >> 1), String(menu[1] + 12), '350']);
        list = await capture('01-more-menu-scroll-' + attempt);
      }
      for (const title of ['设置', '节目源', '导入 M3U', '数字选台', '画面比例', '重新连接', '播放信息', '频道扫描'])
        assert(actions.has(title), 'Missing phone menu action: ' + title);
      await tap(byId(list, 'btn_phone_more'));
      list = await capture('01-player-after-menu');
    }
    passed.push('device presentation and player actions visible');
    await tap(byId(list, 'btn_toggle_drawer'));
    list = await capture('02-channels');
    const channels = visible(list, 'rv_channels', 160);
    assert(Number(bounds(channels)[3]) > Number(bounds(channels)[1]));
    visible(list, 'et_search_channel', 80);
    visible(list, 'cb_only_available', 80);
    if (phone) {
      visible(list, 'btn_numpad_dialog', 80);
      assert(!byId(list, 'btn_toggle_drawer'), 'Channel trigger should yield space to the drawer');
    }
    await tap(byId(list, 'btn_close_drawer'));
    list = await capture('03-player');
    await tap(byId(list, 'btn_numpad_dialog'));
    list = await capture('04-numpad');
    for (const id of ['btn_numpad_1', 'btn_numpad_5', 'btn_numpad_go']) visible(list, id, 80);
    if (phone) {
      const go = bounds(visible(list, 'btn_numpad_go'));
      const one = bounds(visible(list, 'btn_numpad_1'));
      assert(go[2] <= one[0], 'Play belongs in the left summary column');
      visible(list, 'btn_numpad_clear', 80);
    }
    if (phone) await tap(visible(list, 'btn_numpad_close', 80));
    else await key(4);
    passed.push('channel drawer and complete numpad accessible');
    await key(186); // Same public settings action as the gear.
    list = await capture('05-settings-network');
    visible(list, 'btn_settings_save', 80);
    if (phone) {
      const network = bounds(visible(list, 'settings_tab_network'));
      const channels = bounds(visible(list, 'settings_tab_channels'));
      assert(channels[1] > network[1] && channels[0] === network[0], 'Settings need a vertical category rail');
      assert(bounds(byId(list, 'btn_settings_save'))[1] < network[1], 'Save belongs in the page header');
    }
    await tap(byId(list, 'settings_tab_channels'));
    list = await capture('06-settings-channels');
    visible(list, 'btn_settings_source', 80);
    await tap(byId(list, 'btn_settings_source'));
    list = await capture('07-source-picker');
    const sourceNames = new Set();
    for (let attempt = 0; attempt < 5; attempt++) {
      list.filter(node => node.class === 'android.widget.CheckedTextView').forEach(node => sourceNames.add(node.text));
      if (sourceNames.size === 5) break;
      const picker = list.find(node => node.class === 'android.widget.ListView');
      const rect = bounds(picker);
      await command(['shell', 'input', 'swipe', String((rect[0] + rect[2]) >> 1), String(rect[3] - 25),
        String((rect[0] + rect[2]) >> 1), String(rect[1] + 25), '350']);
      list = await capture('07-source-picker-scroll-' + attempt);
    }
    assert.equal(sourceNames.size, 5, 'All five sources must be reachable by scrolling');
    await key(4);
    list = await capture('08-settings-channels');
    for (let attempt = 0; !byId(list, 'btn_settings_import_m3u') && attempt < 4; attempt++) {
      const rect = bounds(byId(list, 'settings_panel_channels'));
      await command(['shell', 'input', 'swipe', String((rect[0] + rect[2]) >> 1), String(rect[3] - 30),
        String((rect[0] + rect[2]) >> 1), String(rect[1] + 30), '400']);
      list = await capture('08-settings-channels-scroll-' + attempt);
    }
    await tap(visible(list, 'btn_settings_import_m3u', 60));
    list = await capture('09-m3u-import');
    for (let attempt = 0; !byId(list, 'et_custom_m3u_content') && attempt < 4; attempt++) {
      const scroll = list.find(node => node.class === 'android.widget.ScrollView' && node.scrollable === 'true');
      const rect = bounds(scroll);
      await command(['shell', 'input', 'swipe', String((rect[0] + rect[2]) >> 1), String(rect[3] - 25),
        String((rect[0] + rect[2]) >> 1), String(rect[1] + 25), '350']);
      list = await capture('09-m3u-import-scroll-' + attempt);
    }
    visible(list, 'et_custom_m3u_content');
    if (phone) {
      visible(list, 'm3u_tab_content');
      await tap(visible(list, 'm3u_tab_report'));
      list = await capture('09-m3u-report');
      visible(list, 'tv_custom_m3u_report');
      visible(list, 'btn_inspect_m3u_content', 80);
      await tap(visible(list, 'm3u_tab_content'));
      list = await capture('09-m3u-content');
      visible(list, 'et_custom_m3u_content');
    }
    assert(list.some(node => node['resource-id'] === 'android:id/button1'));
    await key(4);
    await key(4);
    passed.push('source picker and M3U import preserve latest features');
    await key(172); // GUIDE opens the scanner.
    list = await capture('10-scanner-parameters');
    visible(list, 'btn_start_scan', 80);
    if (phone) {
      visible(list, 'btn_scan_parameters', 80);
      await tap(visible(list, 'btn_scan_results', 80));
      list = await capture('11-scanner-results');
      visible(list, 'sv_scan_log', 80);
      visible(list, 'btn_start_scan', 80);
      visible(list, 'btn_scan_result_actions', 80);
      await tap(visible(list, 'btn_scan_parameters', 80));
    } else {
      assert(byId(list, 'scanner_params_panel'));
      assert(byId(list, 'scanner_results_panel'));
    }
    passed.push('scanner geometry and persistent actions accessible');
    await key(4);
    await fs.writeFile(path.join(directory, 'results.json'), JSON.stringify({serial, compact, phone, passed}, null, 2));
    console.log(JSON.stringify({serial, compact, phone, passed}, null, 2));
  } finally {
    if (compact) {
      const size = original.size.match(/Override size:\s*(\d+x\d+)/)?.[1] || 'reset';
      const density = original.density.match(/Override density:\s*(\d+)/)?.[1] || 'reset';
      await command(['shell', 'wm', 'size', size]);
      await command(['shell', 'wm', 'density', density]);
      await launch();
      await fs.writeFile(path.join(directory, 'restored-display.json'), JSON.stringify({
        size: await command(['shell', 'wm', 'size']), density: await command(['shell', 'wm', 'density'])
      }, null, 2));
    }
  }
}
run().catch(error => { console.error(error.stack); process.exitCode = 1; });
