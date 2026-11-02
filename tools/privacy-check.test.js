const assert = require('node:assert/strict');
const cp = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const {test} = require('node:test');
const {detect, detectBuffer, identityIssues} = require('./privacy-check.js');
const policy = require('./privacy-policy.json');

const personalPath = ['C:', 'Users', 'fixture-owner', 'project'].join('\\');
const secretEmail = ['fixture-owner', 'personal.test'].join('@');
const serial = ['123', 'XYZ4567890'].join('');
const token = 'ghp_' + 'a'.repeat(36);

for (const [name, sample, rule] of [
  ['Windows home', personalPath, 'personal-home-path'],
  ['Linux home', ['/home', 'fixture-owner', 'project'].join('/'), 'personal-home-path'],
  ['personal email', secretEmail, 'email'],
  ['GitHub token', token, 'access-token'],
  ['private key', ['-----BEGIN', 'PRIVATE KEY-----'].join(' '), 'private-key'],
  ['phone', ['138', '00138000'].join(''), 'mobile-number'],
  ['MAC', ['12', '34', '56', '78', '9A', 'BC'].join(':'), 'mac-address'],
  ['device serial', serial, 'device-serial'],
  ['password', ['password', '='].join(' ') + ' ' + JSON.stringify('fixture-secret'), 'literal-credential'],
  ['private URL', 'https://' + 'fixture:secret@' + 'example.com', 'url-credentials']
]) {
  test(`blocks ${name}`, () => {
    assert.ok(detect(sample, 'docs/example.md').some(x => x.rule === rule));
  });
}

test('allows portable examples and public identity', () => {
  const text = ['%LOCALAPPDATA%/Android/Sdk', '<device-serial>',
    policy.identity.email, 'hello@example.com', 'ffmpeg-devel@ffmpeg.org',
    'SHA-256: ' + 'a'.repeat(30) + ['138', '00138000'].join('') + 'b'.repeat(23)].join('\n');
  assert.deepEqual(detect(text, 'docs/example.md'), []);
  assert.deepEqual(identityIssues(policy.identity.name, policy.identity.email), []);
  assert.deepEqual(identityIssues('contributor', '12345+contributor@users.noreply.github.com'), []);
  assert.deepEqual(identityIssues('dependabot[bot]', '49699333+dependabot[bot]@users.noreply.github.com'), []);
  assert.equal(identityIssues('Fixture Real Name', '12345+contributor@users.noreply.github.com').length, 1);
  assert.equal(identityIssues(policy.identity.name, secretEmail).length, 1);
});

test('rejects private artifacts and modified playlist authentication', () => {
  assert.ok(detect('', 'deliverables/results.json').some(x => x.rule === 'private-file'));
  assert.ok(detect('http://example.com/stream?accountinfo=fixture', 'app/src/main/assets/custom.m3u',
    {playlist: true}).some(x => x.rule === 'playlist-auth-parameters'));
  for (const file of ['bj-mobile-iptv.m3u', 'bj-mobile-iptv-all.m3u']) {
    assert.ok(detect('http://example.com/stream?accountinfo=fixture', 'app/src/main/assets/' + file,
      {playlist: true}).some(x => x.rule === 'playlist-auth-parameters'));
  }
});

test('preserves reviewed upstream copyright while blocking extra contact details', () => {
  const file = 'app/src/main/java/com/kankan/iptv/player/IRenderView.java';
  const upstream = fs.readFileSync(path.join(__dirname, '..', file), 'utf8');
  assert.deepEqual(detect(upstream, file), []);
  assert.ok(detect(upstream + '\n' + secretEmail, file).some(x => x.rule === 'email'));
  assert.ok(detect(upstream, 'docs/unreviewed-copy.java').some(x => x.rule === 'email'));
});

test('license exception is tied to exact reviewed text', () => {
  const file = 'LICENSES/LGPL-2.1-or-later.txt';
  const license = fs.readFileSync(path.join(__dirname, '..', file), 'utf8');
  assert.deepEqual(detect(license, file), []);
  assert.ok(detect(license + '\n' + secretEmail, file).some(x => x.rule === 'email'));
});

test('only accepts exact upstream source archives at their reviewed paths', () => {
  const file = 'third_party/sources/ffmpeg-ijk0.8.8.tar.gz';
  const data = fs.readFileSync(path.join(__dirname, '..', file));
  assert.deepEqual(detectBuffer(data, file), []);
  assert.ok(detectBuffer(Buffer.concat([data, Buffer.from('extra')]), file)
    .some(x => x.rule === 'unreviewed-source-archive'));
  assert.ok(detectBuffer(data, 'third_party/unreviewed.tar.gz')
    .some(x => x.rule === 'unreviewed-source-archive'));
});

test('checks staged data, commit messages, identity, and hidden history through real hooks', () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'privacy-hook-test-'));
  const env = {...process.env,
    GIT_AUTHOR_NAME: policy.identity.name, GIT_AUTHOR_EMAIL: policy.identity.email,
    GIT_COMMITTER_NAME: policy.identity.name, GIT_COMMITTER_EMAIL: policy.identity.email};
  // Never inherit a caller's index, alternate repository, or disabled hooks setting.
  for (const key of ['GIT_DIR', 'GIT_WORK_TREE', 'GIT_INDEX_FILE', 'GIT_CONFIG_COUNT', 'GIT_CONFIG_PARAMETERS']) delete env[key];
  function run(program, args, extra = {}) {
    const result = cp.spawnSync(program, args, {cwd: directory, env, encoding: 'utf8', windowsHide: true, timeout: 15000, ...extra});
    if (result.error) throw result.error;
    return result;
  }
  function git(...args) {return run('git', args);}
  function ok(result) {assert.equal(result.status, 0, result.stderr);}
  try {
    ok(git('init', '-q'));
    fs.mkdirSync(path.join(directory, 'tools'));
    fs.mkdirSync(path.join(directory, '.githooks'));
    for (const file of ['privacy-check.js', 'privacy-policy.json']) {
      fs.copyFileSync(path.join(__dirname, file), path.join(directory, 'tools', file));
    }
    for (const hook of ['pre-commit', 'commit-msg', 'pre-push']) {
      const destination = path.join(directory, '.githooks', hook);
      fs.copyFileSync(path.join(__dirname, '..', '.githooks', hook), destination);
      fs.chmodSync(destination, 0o755);
    }
    ok(run(process.execPath, ['tools/privacy-check.js', 'install']));
    fs.writeFileSync(path.join(directory, 'readme.md'), 'Public project\n');
    ok(git('add', '--', 'readme.md'));
    ok(git('commit', '-q', '-m', 'Public baseline'));

    fs.writeFileSync(path.join(directory, 'readme.md'), personalPath + '\n');
    ok(git('add', '--', 'readme.md'));
    fs.writeFileSync(path.join(directory, 'readme.md'), 'Only working tree sanitized\n');
    const staged = git('commit', '-q', '-m', 'Staged data must be checked');
    assert.notEqual(staged.status, 0);
    assert.match(staged.stderr, /personal-home-path/);
    assert.ok(!staged.stderr.includes(personalPath));

    ok(git('add', '--', 'readme.md'));
    const message = git('commit', '-q', '-m', 'Message contains ' + secretEmail);
    assert.notEqual(message.status, 0);
    assert.match(message.stderr, /email/);
    assert.ok(!message.stderr.includes(secretEmail));
    const identity = run('git', ['commit', '-q', '-m', 'Wrong identity'],
      {env: {...env, GIT_AUTHOR_EMAIL: secretEmail}});
    assert.notEqual(identity.status, 0);
    assert.match(identity.stderr, /personal-github-identity-required/);
    ok(git('commit', '-q', '-m', 'Clean staged content'));

    fs.writeFileSync(path.join(directory, 'readme.md'), serial + '\n');
    ok(git('add', '--', 'readme.md'));
    const tree = git('write-tree'); ok(tree);
    const parent = git('rev-parse', 'HEAD'); ok(parent);
    const unsafe = git('commit-tree', tree.stdout.trim(), '-p', parent.stdout.trim(), '-m', 'Historical fixture');
    ok(unsafe);
    fs.writeFileSync(path.join(directory, 'readme.md'), 'Clean latest version\n');
    ok(git('add', '--', 'readme.md'));
    const safeTree = git('write-tree'); ok(safeTree);
    const hidden = git('commit-tree', safeTree.stdout.trim(), '-p', unsafe.stdout.trim(), '-m', 'Clean latest fixture');
    ok(hidden);
    const history = run(process.execPath, ['tools/privacy-check.js', 'history', hidden.stdout.trim()]);
    assert.notEqual(history.status, 0);
    assert.match(history.stderr, /device-serial/);
    assert.ok(!history.stderr.includes(serial));
    const push = run(process.execPath, ['.githooks/pre-push'], {input:
      `refs/heads/main ${hidden.stdout.trim()} refs/heads/main ${'0'.repeat(40)}\n`});
    assert.notEqual(push.status, 0);
    assert.match(push.stderr, /device-serial/);
  } finally {
    assert.equal(path.dirname(path.resolve(directory)), path.resolve(os.tmpdir()));
    assert.ok(path.basename(directory).startsWith('privacy-hook-test-'));
    fs.rmSync(directory, {recursive: true, force: true});
  }
});
