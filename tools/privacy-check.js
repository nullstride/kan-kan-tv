// Reads Git objects, so staged content and historical versions are checked exactly.
const cp = require('node:child_process');
const crypto = require('node:crypto');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const policy = require('./privacy-policy.json');

function git(args, options = {}) {
  const result = cp.spawnSync('git', args, {
    encoding: 'utf8', windowsHide: true, maxBuffer: 128 * 1024 * 1024, ...options
  });
  if (result.error || result.status !== 0) throw new Error(`Git ${args[0]} failed`);
  return result.stdout;
}

function gitPath(name) {
  return path.resolve(git(['rev-parse', '--git-path', name]).trim());
}

function privateLiterals() {
  const local = gitPath('privacy-local.json');
  const literals = fs.existsSync(local) ? JSON.parse(fs.readFileSync(local, 'utf8')).forbiddenLiterals : [];
  const username = os.userInfo().username;
  if (username.length >= 5 && username.toLowerCase() !== policy.identity.name.toLowerCase()
      && !['runner', 'administrator', 'ubuntu'].includes(username.toLowerCase())) literals.push(username);
  return literals.filter(x => typeof x === 'string' && x.length >= 5);
}

function detect(text, file, options = {}) {
  const issues = [];
  function match(rule, regex, accept = () => false) {
    for (const found of text.matchAll(regex)) {
      if (!accept(found[0], found)) issues.push({
        file, line: text.slice(0, found.index).split('\n').length, rule
      });
    }
  }
  if (/(?:^|\/)(?:backups?|deliverables|captures|\.private|\.codex)(?:\/|$)|(?:^|\/)(?:\.env(?:\..*)?|local\.properties|privacy-local\.json|id_rsa|id_ed25519)$|\.(?:jks|keystore|p12|pfx|pem|key|apk|log|sqlite|db)$/i.test(file)) {
    issues.push({file, line: 1, rule: 'private-file'});
  }
  const lower = text.toLowerCase();
  const licenseBlob = crypto.createHash('sha1').update(`blob ${Buffer.byteLength(text)}\0`).update(text).digest('hex');
  const reviewedLicense = policy.upstreamLicenseFiles?.[file] === licenseBlob;
  for (const literal of options.literals || []) {
    const index = lower.indexOf(literal.toLowerCase());
    if (index !== -1) issues.push({file, line: text.slice(0, index).split('\n').length, rule: 'local-private-identifier'});
  }
  match('personal-home-path', /\b[A-Z]:[\\/]+(?:Users|Documents and Settings)[\\/]+[^\s/\\"'`<>]+|\/(?:home|Users)\/[^\s/"'`<>]+[^\s"'`<>]*/gi,
    value => value.startsWith(policy.allowedVendorPathPrefix));
  match('email', /[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}/gi,
    (value, found) => reviewedLicense || policy.allowedEmails.includes(value) || value === policy.identity.email
      || (policy.attributionLineHashes?.[file] || []).includes(crypto.createHash('sha256')
        .update(text.slice(0, found.index).split('\n').at(-1) + text.slice(found.index).split('\n')[0]).digest('hex'))
      || /^[^@]+@(?:users\.)?noreply\.github\.com$/i.test(value)
      || /^[^@]+@(?:example\.(?:com|net|org)|[^@]+\.invalid)$/i.test(value));
  match('private-key', /-----BEGIN (?:RSA |EC |OPENSSH |DSA |ENCRYPTED )?PRIVATE KEY-----/g);
  match('access-token', /\b(?:gh[pousr]_[A-Za-z0-9]{25,}|github_pat_[A-Za-z0-9_]{35,}|AKIA[0-9A-Z]{16}|AIza[0-9A-Za-z_-]{35}|sk-(?:proj-)?[A-Za-z0-9_-]{32,}|xox[baprs]-[0-9A-Za-z-]{20,})\b/g);
  match('literal-credential', /\b(?:password|passwd|secret|api[_-]?key|access[_-]?token|auth[_-]?token|storePassword|keyPassword)\s*[=:]\s*["'][^"'\r\n]{4,}["']/gi,
    value => /["'](?:<[^>]+>|\$\{[^}]+\}|REDACTED|example|placeholder)["']$/i.test(value));
  match('url-credentials', /\b(?:https?|rtsp):\/\/[^\s/@:]+:[^\s/@]+@[^\s/]+/gi);
  if (!options.binary) {
    match('mobile-number', /(?<![\w])(?:\+?86[- ]?)?1[3-9]\d{9}(?![\w])/g);
    match('mac-address', /(?<![A-F0-9:])(?:[A-F0-9]{2}:){5}[A-F0-9]{2}(?![A-F0-9:])/gi,
      value => /^(?:00:){5}00$|^(?:ff:){5}ff$/i.test(value));
    match('device-serial', /\b\d{3}[A-Z]{2,4}[A-Z0-9]{5,18}\b/g);
    match('device-serial-field', /(?:["']?serial["']?\s*[:=]\s*["'][^"'\r\n]{6,}["']|\badb(?:\.exe)?\s+-s\s+[^\s"'`<>]{6,}|\binstall\s+[A-Z0-9]{10,24}\b)/gi,
      value => /["'](?:<[^>]+>|REDACTED|DEVICE_SERIAL)["']$|\$\{|process\.argv/.test(value));
  }
  if (options.playlist && /[?&](?:accountinfo|token|password|auth|access_token)=/i.test(text)) {
    issues.push({file, line: 1, rule: 'playlist-auth-parameters'});
  }
  return issues;
}

function identityIssues(name, email, file = '(commit identity)') {
  const alias = /^(?:[0-9]+\+)?([A-Z0-9-]+(?:\[bot\])?)@users\.noreply\.github\.com$/i.exec(email)?.[1];
  return (name === 'GitHub' && email === 'noreply@github.com')
    || (alias && name.toLowerCase() === alias.toLowerCase())
    ? [] : [{file, line: 1, rule: 'personal-github-identity-required'}];
}

function currentIdentityIssues() {
  return ['GIT_AUTHOR_IDENT', 'GIT_COMMITTER_IDENT'].flatMap(key => {
    const value = git(['var', key]);
    const found = /^(.*) <([^>]+)> /.exec(value);
    return found ? identityIssues(found[1], found[2], key) : [{file: key, line: 1, rule: 'invalid-identity'}];
  });
}

function readObjects(ids) {
  if (!ids.length) return new Map();
  const output = git(['cat-file', '--batch'], {encoding: null, input: ids.join('\n') + '\n'});
  const result = new Map();
  let offset = 0;
  for (const id of ids) {
    const newline = output.indexOf(10, offset);
    const header = output.subarray(offset, newline).toString('utf8').split(' ');
    const size = Number(header[2]);
    if (!Number.isSafeInteger(size)) throw new Error('Could not read Git object');
    result.set(id, {type: header[1], data: output.subarray(newline + 1, newline + 1 + size)});
    offset = newline + size + 2;
  }
  return result;
}

function detectBuffer(data, file, literals = []) {
  if (/\.(?:tar\.gz|tgz|zip|7z|rar)$/i.test(file)) {
    const digest = crypto.createHash('sha256').update(data).digest('hex');
    const reviewed = policy.upstreamSourceArchives?.[file];
    return (Array.isArray(reviewed) ? reviewed : [reviewed]).includes(digest) ? []
      : [{file, line: 1, rule: 'unreviewed-source-archive'}];
  }
  return detect(data.toString('utf8'), file, {
    literals, playlist: /\.m3u$/i.test(file), binary: data.subarray(0, 8000).includes(0)
  });
}

function scanEntries(entries, literals) {
  const objects = readObjects([...new Set(entries.map(x => x.id))]);
  return entries.flatMap(entry => {
    const object = objects.get(entry.id);
    if (object.type !== 'blob') throw new Error('Expected a file object');
    // Native libraries are scanned too; diagnostics never echo matched values.
    return detectBuffer(object.data, entry.file, literals);
  });
}

function treeEntries(ref) {
  return git(['ls-tree', '-r', '-z', ref]).split('\0').filter(Boolean).map(record => {
    const tab = record.indexOf('\t');
    const header = record.slice(0, tab).split(' ');
    if (header[1] !== 'blob') throw new Error('Submodules require a separate privacy review');
    return {id: header[2], file: record.slice(tab + 1)};
  });
}

function checkHistory(refs, literals) {
  const commits = git(['rev-list', ...refs]).trim().split(/\r?\n/).filter(Boolean);
  const objects = readObjects(commits);
  const treeIds = new Set();
  const entries = new Map();
  const issues = [];
  for (const id of commits) {
    const text = objects.get(id).data.toString('utf8');
    const file = `commit:${id.slice(0, 7)}`;
    issues.push(...detect(text, file, {literals}));
    for (const role of ['author', 'committer']) {
      const found = new RegExp(`^${role} (.*) <([^>]+)> `, 'm').exec(text);
      if (!found) throw new Error('Invalid commit identity');
      issues.push(...identityIssues(found[1], found[2], file));
    }
    treeIds.add(/^tree ([a-f0-9]+)$/m.exec(text)[1]);
  }
  // Read each unique tree, then recurse in batches instead of spawning Git per file.
  let pending = [...treeIds].map(id => ({id, prefix: ''}));
  while (pending.length) {
    const trees = readObjects([...new Set(pending.map(x => x.id))]);
    const next = [];
    for (const {id, prefix} of pending) {
      const tree = trees.get(id);
      if (tree.type !== 'tree') throw new Error('Expected a Git tree');
      let offset = 0;
      while (offset < tree.data.length) {
        const space = tree.data.indexOf(32, offset);
        const nul = tree.data.indexOf(0, space);
        const mode = tree.data.toString('ascii', offset, space);
        const file = prefix + tree.data.toString('utf8', space + 1, nul);
        const child = tree.data.subarray(nul + 1, nul + 21).toString('hex');
        offset = nul + 21;
        if (mode === '40000') next.push({id: child, prefix: file + '/'});
        else if (mode === '160000') throw new Error('Submodules require a separate privacy review');
        else entries.set(`${child}:${file}`, {id: child, file});
      }
    }
    pending = next;
  }
  issues.push(...scanEntries([...entries.values()], literals));
  return issues;
}

function install() {
  git(['config', '--local', 'user.name', policy.identity.name]);
  git(['config', '--local', 'user.email', policy.identity.email]);
  git(['config', '--local', 'user.useConfigOnly', 'true']);
  git(['config', '--local', 'core.hooksPath', path.resolve(__dirname, '..', '.githooks').replaceAll('\\', '/')]);
  console.log('Privacy hooks and personal GitHub identity installed for this checkout.');
}

function main(args = process.argv.slice(2)) {
  try {
    const [mode = 'tracked', ...rest] = args;
    if (mode === 'install') return install();
    const literals = privateLiterals();
    let issues;
    if (mode === 'staged') {
      const changed = new Set(git(['diff', '--cached', '--name-only', '--diff-filter=ACMR', '-z']).split('\0').filter(Boolean));
      const entries = git(['ls-files', '--stage', '-z']).split('\0').filter(Boolean).flatMap(record => {
        const tab = record.indexOf('\t');
        const file = record.slice(tab + 1);
        if (!changed.has(file)) return [];
        const [fileMode, id, stage] = record.slice(0, tab).split(' ');
        if (stage !== '0' || fileMode === '160000') throw new Error('Unmerged files or submodules need review');
        return [{file, id}];
      });
      issues = [...currentIdentityIssues(), ...scanEntries(entries, literals)];
    } else if (mode === 'message') {
      issues = [...currentIdentityIssues(), ...detect(fs.readFileSync(rest[0], 'utf8'), '(commit message)', {literals})];
    } else if (mode === 'history') {
      issues = checkHistory(rest.length ? rest : ['HEAD'], literals);
    } else if (mode === 'push') {
      const refs = fs.readFileSync(0, 'utf8').trim().split(/\r?\n/).filter(Boolean)
        .map(line => line.split(' ')[1]).filter(sha => !/^0+$/.test(sha));
      issues = refs.length ? checkHistory(refs, literals) : [];
    } else if (mode === 'tracked') {
      issues = scanEntries(treeEntries(rest[0] || 'HEAD'), literals);
    } else throw new Error('Use tracked, staged, message, history, push, or install');
    const unique = [...new Map(issues.map(x => [`${x.file}:${x.line}:${x.rule}`, x])).values()];
    if (unique.length) {
      console.error('Privacy check blocked this operation. Replace real values with placeholders.');
      for (const item of unique.slice(0, 40)) console.error(`${item.file}:${item.line}: ${item.rule}`);
      if (unique.length > 40) console.error(`${unique.length - 40} more findings`);
      process.exitCode = 1;
    } else console.log(`Privacy check passed (${mode}).`);
  } catch (error) {
    console.error(`Privacy check failed: ${error.message}`);
    process.exitCode = 1;
  }
}

module.exports = {detect, detectBuffer, identityIssues, main};
if (require.main === module) main();
