// SPDX-License-Identifier: MIT
// Restore byte-verified upstream libraries and compile the checked-in JNI source.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const zlib = require('node:zlib');
const cp = require('node:child_process');
const root = path.resolve(__dirname, '..');
const manifest = require('../native/manifest.json');
const hash = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
const cache = path.join(root, '.native-cache');

function zipEntries(bytes) {
  let end = bytes.length - 22;
  while (end >= 0 && bytes.readUInt32LE(end) !== 0x06054b50) end--;
  if (end < 0) throw new Error('Missing ZIP central directory');
  const count = bytes.readUInt16LE(end + 10);
  let cursor = bytes.readUInt32LE(end + 16);
  const entries = new Map();
  for (let i = 0; i < count; i++) {
    if (bytes.readUInt32LE(cursor) !== 0x02014b50) throw new Error('Invalid ZIP entry');
    const method = bytes.readUInt16LE(cursor + 10);
    if (![0, 8].includes(method)) throw new Error('Unsupported ZIP compression');
    const length = bytes.readUInt32LE(cursor + 20);
    const nameLength = bytes.readUInt16LE(cursor + 28);
    const extra = bytes.readUInt16LE(cursor + 30);
    const comment = bytes.readUInt16LE(cursor + 32);
    const offset = bytes.readUInt32LE(cursor + 42);
    const name = bytes.subarray(cursor + 46, cursor + 46 + nameLength).toString();
    const start = offset + 30 + bytes.readUInt16LE(offset + 26) + bytes.readUInt16LE(offset + 28);
    const packed = bytes.subarray(start, start + length);
    entries.set(name, method === 0 ? Buffer.from(packed) : zlib.inflateRawSync(packed));
    cursor += 46 + nameLength + extra + comment;
  }
  return entries;
}

async function restore() {
  fs.mkdirSync(cache, {recursive: true});
  for (const artifact of manifest.artifacts) {
    const name = `ijkplayer-${artifact.artifact}-0.8.8.aar`;
    const cached = path.join(cache, name);
    let bytes = fs.existsSync(cached) ? fs.readFileSync(cached) : null;
    if (!bytes) {
      const base = 'https://maven.aliyun.com/repository/public/tv/danmaku/ijk/media';
      const response = await fetch(`${base}/ijkplayer-${artifact.artifact}/0.8.8/${name}`);
      if (!response.ok) throw new Error(`Artifact download failed: ${response.status}`);
      bytes = Buffer.from(await response.arrayBuffer());
    }
    if (hash(bytes) !== artifact.archiveSha256) throw new Error(`Checksum mismatch: ${name}`);
    fs.writeFileSync(cached, bytes);
    const entries = zipEntries(bytes);
    for (const file of artifact.files) {
      const entry = file.file.replace('app/src/main/jniLibs/', 'jni/');
      const data = entries.get(entry);
      if (!data || hash(data) !== file.upstreamSha256) throw new Error(`Upstream mismatch: ${entry}`);
      for (const change of file.changes) {
        if (data[change.offset] !== change.before) throw new Error(`Patch mismatch: ${entry}`);
        data[change.offset] = change.after;
      }
      if (hash(data) !== file.sha256) throw new Error(`Patched checksum mismatch: ${entry}`);
      fs.writeFileSync(path.join(root, file.file), data);
    }
  }
}

function compile() {
  const compiler = process.argv[3] || process.env.CLANG || 'clang';
  const targets = [
    ['arm64-v8a', 'aarch64-linux-android21', []],
    ['armeabi-v7a', 'armv7a-linux-androideabi21', ['-mfloat-abi=softfp', '-mfpu=neon']],
    ['x86', 'i686-linux-android21', []],
    ['x86_64', 'x86_64-linux-android21', []]
  ];
  for (const [abi, target, extra] of targets) {
    const directory = path.join(root, 'app/src/main/jniLibs', abi);
    const result = cp.spawnSync(compiler, [
      '--no-default-config', `--target=${target}`, '-fuse-ld=lld',
      '-shared', '-nostdlib', '-ffreestanding', '-fno-builtin', '-fno-stack-protector',
      '-fPIC', '-fvisibility=hidden', '-O2', '-Wall', '-Wextra', '-Werror', ...extra,
      '-Wl,-z,defs', '-Wl,-z,max-page-size=16384', '-Wl,-soname,libijkmp2jni.so',
      '-Wl,--build-id=sha1', 'native/mp2_jni.c', `-L${directory}`, '-lijkffmpeg',
      '-o', path.join(directory, 'libijkmp2jni.so')
    ], {cwd: root, stdio: 'inherit', windowsHide: true});
    if (result.error || result.status !== 0) throw new Error(`JNI compilation failed: ${abi}`);
    console.log(`Built JNI bridge: ${abi}`);
  }
}

function verify() {
  for (const artifact of manifest.artifacts) for (const file of artifact.files) {
    if (hash(fs.readFileSync(path.join(root, file.file))) !== file.sha256) {
      throw new Error(`Native library checksum mismatch: ${file.file}`);
    }
  }
  for (const [name, expected] of Object.entries(manifest.sourceArchives)) {
    if (hash(fs.readFileSync(path.join(root, 'third_party/sources', name))) !== expected) {
      throw new Error(`Source archive checksum mismatch: ${name}`);
    }
  }
  if (manifest.jniLibraries) for (const [abi, expected] of Object.entries(manifest.jniLibraries)) {
    if (hash(fs.readFileSync(path.join(root, 'app/src/main/jniLibs', abi, 'libijkmp2jni.so'))) !== expected) {
      throw new Error(`JNI bridge checksum mismatch: ${abi}`);
    }
  }
  console.log('Native binaries and corresponding source archives verified.');
}

async function main() {
  const mode = process.argv[2] || 'verify';
  if (mode === 'restore') await restore();
  else if (mode === 'build') { await restore(); compile(); }
  else if (mode !== 'verify') throw new Error('Usage: node tools/native-libraries.js verify | restore | build');
  if (mode !== 'build') verify();
}
main().catch(error => {console.error(error.message); process.exitCode = 1;});
