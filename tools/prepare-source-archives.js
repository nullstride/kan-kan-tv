// SPDX-License-Identifier: MIT
// Normalize non-code metadata without extracting or changing other archive entries.
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const zlib = require('node:zlib');
const root = path.resolve(__dirname, '..');
const homePrefix = ['', 'Users', ''].join('/');
const rules = [
  ['ijkplayer-k0.8.8.tar.gz', '/ios/IJKMediaPlayer/IJKMediaPlayer.xcodeproj/project.pbxproj', text =>
    text.split('\n').map(line => line.includes(homePrefix)
      ? line.replace(/path = [^;]+;/, 'path = IJKMediaFrameworkWithSSL.plist;')
        .replace('sourceTree = "<absolute>";', 'sourceTree = "<group>";') : line).join('\n')],
  ['ffmpeg-ijk0.8.8.tar.gz', '/tests/ref/fate/mxf-probe-dnxhd', text =>
    text.split('\n').map(line => line.startsWith('TAG:comment_UNC Path=')
      ? 'TAG:comment_UNC Path=' : line).join('\n')]
];

for (const [archive, suffix, normalize] of rules) {
  const file = path.join(root, 'third_party/sources', archive);
  const packed = fs.readFileSync(file);
  const tar = zlib.gunzipSync(packed);
  const chunks = [];
  let cursor = 0;
  let found = 0;
  let changed = false;
  while (cursor + 512 <= tar.length) {
    const original = tar.subarray(cursor, cursor + 512);
    if (original.every(byte => byte === 0)) break;
    const name = original.toString('utf8', 0, 100).replace(/\0.*$/, '');
    const size = parseInt(original.toString('ascii', 124, 136).replace(/\0.*$/, ''), 8);
    assert.ok(Number.isSafeInteger(size) && size >= 0 && cursor + 512 + size <= tar.length);
    const end = cursor + 512 + Math.ceil(size / 512) * 512;
    if (name.endsWith(suffix)) {
      found++;
      assert.equal(original.toString('ascii', 156, 157), '0');
      const data = tar.subarray(cursor + 512, cursor + 512 + size);
      const normalized = Buffer.from(normalize(data.toString('utf8')));
      assert.ok(!normalized.includes(Buffer.from(homePrefix)));
      if (!data.equals(normalized)) {
        const header = Buffer.from(original);
        header.write(normalized.length.toString(8).padStart(11, '0') + '\0', 124, 12, 'ascii');
        header.fill(32, 148, 156);
        const checksum = header.reduce((total, byte) => total + byte, 0);
        header.write(checksum.toString(8).padStart(6, '0') + '\0 ', 148, 8, 'ascii');
        chunks.push(header, normalized, Buffer.alloc((512 - normalized.length % 512) % 512));
        changed = true;
      } else chunks.push(tar.subarray(cursor, end));
    } else chunks.push(tar.subarray(cursor, end));
    cursor = end;
  }
  assert.equal(found, 1, archive);
  chunks.push(tar.subarray(cursor));
  const result = Buffer.concat(chunks);
  assert.ok(!result.includes(Buffer.from(homePrefix)), archive);
  if (changed) fs.writeFileSync(file, zlib.gzipSync(result, {level: 9}));
  console.log(archive + ': ' + crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex'));
}
