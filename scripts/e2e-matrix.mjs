// Isolated compatibility matrix driven by explicit local fixture paths.
// node scripts/e2e-matrix.mjs /absolute/path/to/fixture.json
import fs from 'node:fs';
import path from 'node:path';
import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const fixturePath = process.argv[2] || process.env.E2E_FIXTURE;
if (!fixturePath) throw Error('Pass a fixture JSON; see scripts/e2e-fixture.example.json');
const fixture = JSON.parse(fs.readFileSync(fixturePath, 'utf8'));
if (!Array.isArray(fixture.cases) || !fixture.cases.length) throw Error('fixture.cases must be nonempty');
const ids = new Set();
function local(file) {
  if (!path.isAbsolute(file || '') || !fs.existsSync(file)) throw Error('Expected existing absolute fixture path: ' + file);
  return file;
}
local(fixture.java); local(fixture.proxyJar);
for (const c of fixture.cases) {
  if (!/^[a-zA-Z0-9_-]+$/.test(c.id) || ids.has(c.id)) throw Error('Invalid or duplicate case id');
  ids.add(c.id);
  local(c.backendJar); local(c.backendJava || fixture.java);
  if (c.backendSeed) local(c.backendSeed);
  if (c.proxyJar) local(c.proxyJar);
  for (const via of c.viaJars || []) local(via);
}
const output = fs.mkdtempSync(path.join(root, 'build/e2e-matrix-'));
for (const c of fixture.cases) {
  if (process.env.CASE_FILTER && !process.env.CASE_FILTER.split(',').some(f => c.id.includes(f))) continue;
  const env = {...process.env, JAVA: fixture.java, BACKEND_JAVA: c.backendJava || fixture.java,
    BACKEND_JAR: c.backendJar, PROXY_JAR: c.proxyJar || fixture.proxyJar,
    BACKEND_SEED: c.backendSeed || '', BOT_TASK: c.botTask || 'bot', BOT_VERSION: c.botVersion || '',
    BOT_SECONDS: String(c.seconds || 30), BOT_REPEATS: String(c.repeats || 1),
    E2E_CASE: c.id, E2E_MODE: c.mode || 'active', VIA_JARS: (c.viaJars || []).join(':')};
  console.log('START ' + c.id + ' ' + new Date().toISOString());
  const r = spawnSync(process.execPath, [path.join(root, 'scripts/e2e.mjs')], {env, encoding: 'utf8'});
  const log = (r.stdout || '') + (r.stderr || '');
  fs.writeFileSync(path.join(output, c.id + '.log'), log);
  console.log(log);
  if (r.error) throw r.error;
  if (r.status !== 0) process.exitCode = 1;
}
console.log('Matrix runner logs: ' + output);
