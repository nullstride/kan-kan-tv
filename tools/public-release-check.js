// SPDX-License-Identifier: MIT
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const cp = require('node:child_process');
const root = path.resolve(__dirname, '..');
const read = name => fs.readFileSync(path.join(root, name), 'utf8');
const policy = JSON.parse(read('tools/privacy-policy.json'));
assert.ok(!('upstreamPlaylists' in policy), 'Playlist credential bypass must not be restored');
for (const name of fs.readdirSync(path.join(root, 'app/src/main/assets'))) {
  if (!name.endsWith('.m3u')) continue;
  const playlist = read('app/src/main/assets/' + name);
  assert.doesNotMatch(playlist, /[?&](?:accountinfo|accounttype|token|password|auth|access_token|hms_devid)=/i, name);
  assert.doesNotMatch(playlist, /https?:\/\/[^\s/@:]+:[^\s/@]+@/i, name);
}
for (const option of ['ENABLE_DEVICE_REPORTING', 'ENABLE_UPSTREAM_UPDATES']) {
  assert.match(read('app/src/main/java/com/bjunicom/iptv/config/IptvSettings.java'),
    new RegExp(`\\b${option}\\s*=\\s*false\\s*;`), option);
}
const canonical = read('tools/playlist-inspect.sh');
assert.equal(read('check_iptv_m3u.sh'), canonical);
assert.equal(read('app/src/main/assets/check_iptv_m3u.sh'), canonical);
for (const name of ['LICENSE', 'THIRD_PARTY_NOTICES.md', 'native/mp2_jni.c', 'native/ffmpeg-abi.h',
  'native/patches/ffmpeg-mp2-decoder.patch', 'LICENSES/LGPL-2.1-or-later.txt', 'LICENSES/Apache-2.0.txt',
  'app/src/main/assets/licenses/THIRD_PARTY_NOTICES.txt']) assert.ok(read(name).length > 80, name);
const manifest = JSON.parse(read('native/manifest.json'));
for (const artifact of manifest.artifacts) {
  const directory = path.dirname(artifact.files[0].file);
  const expected = [...artifact.files.map(file => path.basename(file.file)), 'libijkmp2jni.so'].sort();
  assert.deepEqual(fs.readdirSync(path.join(root, directory)).sort(), expected, 'Unreviewed native library');
}
const nativeCheck = cp.spawnSync(process.execPath, ['tools/native-libraries.js', 'verify'],
  {cwd: root, encoding: 'utf8', windowsHide: true});
assert.equal(nativeCheck.status, 0, nativeCheck.stderr);
console.log('Public release checks passed: playlist parameters, disabled reporting, source/notice files, and native provenance.');
