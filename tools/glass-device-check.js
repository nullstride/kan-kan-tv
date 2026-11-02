// Deterministic visual/performance evidence on an explicitly selected Android device.
const cp = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');
const adb = path.join(process.env.LOCALAPPDATA, 'Android', 'Sdk', 'platform-tools', 'adb.exe');
const [serial, output] = process.argv.slice(2);
if (!serial || !output) throw Error('Usage: node tools/glass-device-check.js <serial> <output-directory>');
fs.mkdirSync(output, {recursive: true});
function run(args, binary = false) {
  const result = cp.spawnSync(adb, ['-s', serial, ...args], {
    windowsHide: true, timeout: 30000, maxBuffer: 32 * 1024 * 1024,
    encoding: binary ? undefined : 'utf8'
  });
  if (result.status !== 0) throw Error(String(result.stderr || result.error));
  return result.stdout;
}
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
async function scene(name, mode, extras = [], video = false) {
  run(['shell', 'am', 'start', '-S', '-n', 'com.bjunicom.iptv/.ui.GlassLabActivity',
    '--es', 'mode', mode, ...extras]);
  await pause(1500);
  const pid = run(['shell', 'pidof', 'com.bjunicom.iptv']).trim();
  if (!pid) throw Error('App did not start: ' + name);
  run(['shell', 'dumpsys', 'gfxinfo', 'com.bjunicom.iptv', 'reset']);
  fs.writeFileSync(path.join(output, name + '.png'), run(['exec-out', 'screencap', '-p'], true));
  if (video) {
    const remote = '/sdcard/' + name + '.mp4';
    await new Promise((resolve, reject) => {
      const recording = cp.spawn(adb, ['-s', serial, 'shell', 'screenrecord', '--size', '1400x984',
        '--bit-rate', '8000000', '--time-limit', '15', remote], {windowsHide: true, stdio: 'ignore'});
      recording.on('error', reject);
      recording.on('exit', code => code === 0 ? resolve() : reject(Error('recording exit ' + code)));
    });
    run(['pull', remote, path.join(output, name + '.mp4')]);
    fs.writeFileSync(path.join(output, name + '-gfx.txt'), run(['shell', 'dumpsys', 'gfxinfo',
      'com.bjunicom.iptv', 'framestats']));
  }
  const log = run(['logcat', '-d', '--pid=' + pid, '-s', 'VideoGlass:D', 'GlassLab:I', 'AndroidRuntime:E']);
  fs.writeFileSync(path.join(output, name + '.log'), log);
  if (/FATAL EXCEPTION|AGSL draw failed|AGSL unavailable|video failed/.test(log))
    throw Error('Runtime failure in ' + name);
  console.log('PASS ' + name + ' pid=' + pid);
}
(async () => {
  await scene('white', 'white');
  await scene('black', 'black');
  await scene('photo', 'photo');
  await scene('checker', 'checker');
  await scene('fallback-white', 'white', ['--ez', 'glass_force_fallback', 'true']);
  await scene('surface', 'surface', [], true);
  await scene('texture', 'texture', [], true);
  await scene('hidden', 'surface', ['--ez', 'hide_panels', 'true'], true);
  run(['shell', 'am', 'start', '-S', '-n', 'com.bjunicom.iptv/.ui.MainActivity']);
  console.log('COMPLETE');
})().catch(error => { console.error(error); process.exitCode = 1; });
