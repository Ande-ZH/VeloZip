// Publish structured results and allowlisted log excerpts; never raw configs or secrets.
// node scripts/e2e-evidence.mjs /absolute/build/e2e-matrix-... [more matrices in run order]
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {fileURLToPath} from 'node:url';
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const matrices = process.argv.slice(2);
if (!matrices.length) throw Error('Pass one or more matrix directories in chronological order');
for (const dir of matrices) {
  if (!path.isAbsolute(dir) || !path.resolve(dir).startsWith(path.join(root, 'build/e2e-matrix-'))) {
    throw Error('Expected an absolute local build/e2e-matrix-* directory');
  }
}
const version = fs.readFileSync(path.join(root, 'gradle.properties'), 'utf8').match(/^version=(.+)$/m)[1].trim();
const date = process.env.E2E_EVIDENCE_DATE || new Date().toISOString().slice(0, 10);
if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) throw Error('Invalid evidence date');
const output = path.join(root, 'docs/e2e', 'v' + version + '-' + date.replaceAll('-', ''));
fs.mkdirSync(output, {recursive: true});
const sha = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
const results = [], latest = new Map(), attempts = new Map();
for (const matrix of matrices) for (const log of fs.readdirSync(matrix).filter(n => n.endsWith('.log')).sort()) {
  const raw = fs.readFileSync(path.join(matrix, log), 'utf8').match(/evidence (.+)/)?.[1];
  if (!raw || !path.resolve(raw).startsWith(path.join(root, 'build/e2e-'))) throw Error('Invalid result path');
  const result = JSON.parse(fs.readFileSync(path.join(raw, 'result.json'), 'utf8'));
  if (result.pluginVersion !== version || !/^[\w-]+$/.test(result.case)) throw Error('Invalid result version/id');
  result.attempt = (attempts.get(result.case) || 0) + 1;
  attempts.set(result.case, result.attempt);
  if (latest.has(result.case)) latest.get(result.case).superseded = true;
  latest.set(result.case, result);
  result.rawLogSha256 = {};
  const excerpts = [];
  for (const name of fs.readdirSync(raw).filter(n => n.endsWith('.log')).sort()) {
    const data = fs.readFileSync(path.join(raw, name));
    result.rawLogSha256[name] = sha(data);
    excerpts.push('--- ' + name + ' (allowlisted excerpts) ---');
    for (const line of data.toString().replace(/\x1b\[[0-9;]*[A-Za-z]/g, '').replace(/\r/g, '').split('\n')) {
      const match = line.match(/(BOT reached PLAY state.*|BOT healthy hold milliseconds=\d+|BOT finished: success=(?:true|false)|VeloZip transport enabled for (?:VeloZipBot|backend)|VeloZip \d+\.\d+\.\d+.*|Transport [Pp]rotocol: \d+|Active connections: \d+|Total connections: \d+|[TR]X: DirectionSnapshot\[[^\]]+\]|\{backend=ServerStatus\[[^\]]+\]\}|VeloZip: no VeloZip response from backend.*|VeloZip: backend refused VeloZip for backend:.*|enabled=(?:true|false)|require-velozip=(?:true|false) debug=(?:true|false)|authentication=(?:disabled|enabled \(redacted\))|Starting Minecraft server version [\w.]+|This server is running (?:Paper|Purpur) version .*|Booting up Velocity [\w.()\-+ /]+|Loading (?:server )?plugin [Vv]elo[Zz]ip v?[\w.]+)/);
      if (match) excerpts.push(match[1]);
    }
  }
  if (result.mode === 'active' && result.passed) {
    const final = result.snapshots.at(-1);
    result.finalBilateralCountersEqual = JSON.stringify(final.backend.directions.TX) === JSON.stringify(final.proxy.directions.RX)
      && JSON.stringify(final.backend.directions.RX) === JSON.stringify(final.proxy.directions.TX);
    result.finalCountersSettled = final.backend.active === 0 && final.proxy.active === 0 && result.finalBilateralCountersEqual;
    result.bilateralTraffic = true;
    for (const side of ['backend', 'proxy']) for (const direction of ['TX', 'RX']) {
      if (!(final[side].directions[direction].originalBytes > 0)) result.bilateralTraffic = false;
    }
  }
  result.evidenceFile = result.case + '-attempt-' + result.attempt + '.txt';
  fs.writeFileSync(path.join(output, result.evidenceFile), excerpts.join('\n') + '\n');
  results.push(result);
}
// Preserve exploratory attempts exactly as reported by their harness. Only the
// latest attempt is eligible for current acceptance, including stronger checks.
for (const result of latest.values()) {
  if (result.passed && (!result.cleanup || (result.mode === 'active'
      && (!result.finalCountersSettled || !result.bilateralTraffic)))) {
    throw Error('Latest result does not meet final acceptance criteria: ' + result.case);
  }
}
const passed = [...latest.values()].filter(r => r.passed).length;
const summary = {cases: latest.size, passed, failed: latest.size - passed,
  supersededFailures: results.filter(r => r.superseded && !r.passed).length};
fs.writeFileSync(path.join(output, 'results.json'), JSON.stringify({schema: 2, date, pluginVersion: version,
  transportProtocol: 1, summary, metric: 'application frame bytes; not TCP capture', results}, null, 2) + '\n');
const sources = {};
function record(relative) {
  const absolute = path.join(root, relative);
  if (fs.statSync(absolute).isDirectory()) {
    for (const name of fs.readdirSync(absolute).sort()) record(path.join(relative, name));
  } else sources[relative] = sha(fs.readFileSync(absolute));
}
for (const file of ['build.gradle.kts', 'gradle.properties', 'gradle/libs.versions.toml',
  'velozip-backend/build.gradle.kts', 'velozip-velocity/build.gradle.kts',
  'velozip-common/src/main', 'velozip-backend/src/main', 'velozip-velocity/src/main',
  'scripts/e2e.mjs', 'scripts/e2e-matrix.mjs', 'scripts/e2e-evidence.mjs', 'scripts/legacy-bot.mjs',
  'scripts/package-lock.json', 'scripts/test-evidence.mjs',
  'velozip-itest/src/main/java/dev/velozip/itest/BotMain.java']) record(file);
fs.writeFileSync(path.join(output, 'fixture.json'), JSON.stringify({sourcesAtExport: sources,
  configuration: {bind: 'loopback ephemeral', freshWorld: true, onlineMode: false, modernForwarding: true,
    forwardingSecret: 'random per run, omitted', backendHeapMiB: 1200, proxyHeapMiB: 512,
    finalRuns: {activeProcessorCount: 2, worldType: 'flat', explicitLayers: true, sequential: true},
    client: 'native minecraft-protocol for old versions; MCProtocolLib for current versions',
    sampling: 'before, live, after disconnect; see per-case hold time and repeats',
    authentication: 'empty except intentionally mismatched secrets in auth-refusal; values omitted'}}, null, 2) + '\n');
const checksums = fs.readdirSync(output).filter(n => n !== 'SHA256SUMS').sort()
  .map(n => sha(fs.readFileSync(path.join(output, n))) + '  ' + n).join('\n') + '\n';
fs.writeFileSync(path.join(output, 'SHA256SUMS'), checksums);
console.log('Sanitized ' + results.length + ' attempts to ' + output + ': ' + JSON.stringify(summary));
if (summary.failed) process.exitCode = 1;
