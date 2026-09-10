// Isolated loopback smoke test. Never runs in the source runtime directory.
// JAVA=/absolute/java BACKEND_JAR=/absolute/jar PROXY_JAR=/absolute/jar node scripts/e2e.mjs
import fs from 'node:fs';
import path from 'node:path';
import net from 'node:net';
import crypto from 'node:crypto';
import {spawnSync} from 'node:child_process';
import {spawn} from 'node:child_process';
import {fileURLToPath} from 'node:url';
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const pluginVersion = fs.readFileSync(path.join(root, 'gradle.properties'), 'utf8').match(/^version=(.+)$/m)?.[1].trim();
if (!pluginVersion) throw Error('missing project version');
const java = process.env.JAVA;
for (const key of ['JAVA', 'BACKEND_JAR', 'PROXY_JAR']) {
  if (!path.isAbsolute(process.env[key] || '') || !fs.existsSync(process.env[key])) throw Error(`${key} must be an existing absolute path`);
}
const run = fs.mkdtempSync(path.join(root, 'build/e2e-'));
const children = [];
const mode = process.env.E2E_MODE || 'active';
if (!['active', 'missing', 'required-missing', 'auth-refusal', 'disabled', 'baseline', 'outdated'].includes(mode)) throw Error('invalid E2E_MODE');
const seconds = Number(process.env.BOT_SECONDS || 30);
const repeats = Number(process.env.BOT_REPEATS || 1);
if (!Number.isInteger(seconds) || seconds < 15 || seconds > 120 || !Number.isInteger(repeats) || repeats < 1 || repeats > 5) throw Error('invalid bot bounds');
const result = {case: process.env.E2E_CASE || mode, mode, pluginVersion, botVersion: process.env.BOT_VERSION || process.env.BOT_TASK || 'bot', started: new Date().toISOString(), seconds, repeats, bots: [], snapshots: [], passed: false, inputs: {}};
for (const key of ['BACKEND_JAR', 'PROXY_JAR', 'VIA_JARS']) for (const file of (process.env[key] || '').split(':').filter(Boolean)) result.inputs[path.basename(file)] = crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
for (const [name, executable] of [['proxy', java], ['backend', process.env.BACKEND_JAVA || java]]) {
  result[`${name}JavaVersion`] = spawnSync(executable, ['-version'], {encoding:'utf8'}).stderr.trim();
  result[`${name}JavaExecutableSha256`] = crypto.createHash('sha256').update(fs.readFileSync(executable)).digest('hex');
}
const logText = name => fs.readFileSync(path.join(run, `${name}.log`), 'utf8').replace(/\x1b\[[0-9;]*m/g, '');
async function diagnostics(b, p, phase) {
  const offsets = {backend: logText('backend').length, proxy: logText('proxy').length};
  if (!['missing','required-missing','baseline'].includes(mode)) b.stdin.write('velozip status\nvelozip stats\nvelozip config\n');
  if (mode !== 'baseline') p.stdin.write('velozip status\nvelozip stats\nvelozip config\nvelozip servers\n');
  // Console commands are asynchronous (especially during JVM warmup). Wait for
  // the complete response instead of mistaking late output for a bad config.
  const queried = [];
  if (!['missing','required-missing','baseline'].includes(mode)) queried.push('backend');
  if (mode !== 'baseline') queried.push('proxy');
  for (let attempt = 0; attempt < 150; attempt++) {
    const pending = queried.filter(name => {
      const text = logText(name).slice(offsets[name]);
      return !text.includes('Changes require restart; no live reload.')
        || !text.includes('RX: DirectionSnapshot[') || !text.includes('Active connections:')
        || (name === 'proxy' && !/\{(?:backend=ServerStatus\[[^\]]*\])?\}/.test(text));
    });
    if (!pending.length) break;
    if (attempt === 149) throw Error(`timed out waiting for ${pending.join(',')} diagnostics (${phase})`);
    await sleep(100);
  }
  const snapshot = {phase};
  for (const name of ['backend','proxy']) {
    const text = logText(name).slice(offsets[name]);
    snapshot[name] = {active: Number(text.match(/Active connections: (\d+)/)?.[1] ?? -1), directions: {}};
    for (const match of text.matchAll(/(TX|RX): DirectionSnapshot\[originalBytes=(\d+), wireBytes=(\d+), rawFrames=(\d+), zstdFrames=(\d+)\]/g)) {
      const [direction, originalBytes, wireBytes, rawFrames, zstdFrames] = match.slice(1);
      snapshot[name].directions[direction] = Object.fromEntries(Object.entries({originalBytes,wireBytes,rawFrames,zstdFrames}).map(([k,v])=>[k,Number(v)]));
    }
    snapshot[name].serverState = text.match(/backend=ServerStatus\[([^\]]+)/)?.[1] || null;
    snapshot[name].effectiveEnabled = text.match(/(?:^|\n|]: )enabled=(true|false)/)?.[1] ?? null;
    snapshot[name].effectiveRequired = text.match(/require-velozip=(true|false)/)?.[1] ?? null;
    snapshot[name].authentication = text.match(/authentication=(disabled|enabled \(redacted\))/)?.[1] ?? null;
  }
  result.snapshots.push(snapshot);
  return snapshot;
}
const sleep = ms => new Promise(r => setTimeout(r, ms));
async function port() {
  const server = net.createServer();
  await new Promise((r,j) => server.once('error',j).listen(0,'127.0.0.1',r));
  const p = server.address().port;
  await new Promise(r => server.close(r));
  return p;
}
function start(cmd, args, cwd, name) {
  const out = fs.openSync(path.join(run, `${name}.log`), 'w');
  const child = spawn(cmd, args, {cwd, env: {...process.env, JAVA_HOME:path.dirname(path.dirname(java))}, stdio:['pipe',out,out]});
  child.on('error', e => fs.appendFileSync(path.join(run, `${name}.log`), String(e)));
  children.push(child);
  fs.closeSync(out);
  return child;
}
async function ready(child, log, regex) {
  for (let i=0;i<180;i++) {
    if (child.exitCode !== null) throw Error(`${log} exited during startup`);
    if (regex.test(fs.readFileSync(path.join(run, `${log}.log`),'utf8'))) return;
    await sleep(1000);
  }
  throw Error(`${log} startup timed out`);
}
try {
  const backendPort = await port(), proxyPort = await port();
  const backend = path.join(run,'backend'), proxy = path.join(run,'proxy');
  fs.mkdirSync(path.join(backend,'plugins'),{recursive:true});
  fs.mkdirSync(path.join(backend,'config'),{recursive:true});
  fs.mkdirSync(path.join(proxy,'plugins'),{recursive:true});
  fs.copyFileSync(process.env.BACKEND_JAR,path.join(backend,'server.jar'));
  fs.copyFileSync(process.env.PROXY_JAR,path.join(proxy,'proxy.jar'));
  if (process.env.VIA_JARS) for (const jar of process.env.VIA_JARS.split(":")) {
    if (!path.isAbsolute(jar)) throw Error("VIA_JARS requires absolute paths");
    fs.copyFileSync(jar,path.join(backend,"plugins",path.basename(jar)));
  }
  // Optional read-only seed of runtime libraries/cache, never worlds or plugins.
  if (process.env.BACKEND_SEED) for (const dir of ['libraries','cache','versions']) {
    const src = path.join(process.env.BACKEND_SEED,dir);
    if (fs.existsSync(src)) fs.cpSync(src,path.join(backend,dir),{recursive:true});
  }
  for (const [side, destination, installed] of [['backend',backend,!['missing','required-missing','baseline'].includes(mode)], ['velocity',proxy,mode !== 'baseline']]) {
    const artifact = path.join(root,`velozip-${side}/build/libs/VeloZip-${side === 'backend' ? 'Backend' : 'Velocity'}-${pluginVersion}.jar`);
    result.inputs[path.basename(artifact)] = crypto.createHash('sha256').update(fs.readFileSync(artifact)).digest('hex');
    if (installed) fs.copyFileSync(artifact,path.join(destination,'plugins/VeloZip.jar'));
  }
  const authSecret = crypto.randomBytes(32).toString('hex');
  for (const [side, directory] of [['backend',path.join(backend,'plugins/VeloZip')],['proxy',path.join(proxy,'plugins/velozip')]]) {
    fs.mkdirSync(directory,{recursive:true});
    fs.writeFileSync(path.join(directory,'config.yml'),`enabled: ${!(mode === 'disabled' && side === 'proxy')}\nrequire-velozip: ${mode === 'required-missing'}\nauthentication:\n  secret: "${mode === 'auth-refusal' ? (side === 'backend' ? authSecret : crypto.randomBytes(32).toString('hex')) : ''}"\n`);
  }
  fs.writeFileSync(path.join(backend,'eula.txt'),'eula=true\n');
  fs.writeFileSync(path.join(backend,'server.properties'),`server-ip=127.0.0.1\nserver-port=${backendPort}\nonline-mode=false\nlevel-name=fresh-world\nlevel-type=flat\ngenerator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains","structures":{}}\ngenerate-structures=false\nview-distance=3\nsimulation-distance=3\nspawn-protection=0\n`);
  const secret = crypto.randomBytes(32).toString('hex');
  // 1.18 uses paper.yml; 1.19+ uses config/paper-global.yml. Seed both so the
  // server consumes its native configuration. Never copy a production secret.
  fs.writeFileSync(path.join(backend,'paper.yml'),`config-version: 27\nsettings:\n  velocity-support:\n    enabled: true\n    online-mode: false\n    secret: '${secret}'\n`);
  fs.writeFileSync(path.join(backend,'config/paper-global.yml'),`_version: 31\nproxies:\n  velocity:\n    enabled: true\n    online-mode: false\n    secret: '${secret}'\n`);
  fs.writeFileSync(path.join(proxy,'forwarding.secret'),secret);
  fs.writeFileSync(path.join(proxy,'velocity.toml'),`config-version = "2.7"\nbind = "127.0.0.1:${proxyPort}"\nonline-mode = false\nplayer-info-forwarding-mode = "modern"\nforwarding-secret-file = "forwarding.secret"\n[servers]\nbackend = "127.0.0.1:${backendPort}"\ntry = ["backend"]\n[forced-hosts]\n"lobby.example.com" = ["backend"]\n"factions.example.com" = ["backend"]\n"minigames.example.com" = ["backend"]\n`);
  const b = start(process.env.BACKEND_JAVA || java,['-XX:ActiveProcessorCount=2','-Xms256M','-Xmx1200M','-jar','server.jar','--nogui'],backend,'backend');
  await ready(b,'backend',/Done \(/);
  const p = start(java,['-XX:ActiveProcessorCount=2','-Xms128M','-Xmx512M','-jar','proxy.jar'],proxy,'proxy');
  await ready(p,'proxy',/Done \(/);
  const task = process.env.BOT_TASK || "bot";
  const cp = process.env.BOT_VERSION ? null : fs.readFileSync(path.join(root, `velozip-itest/build/${task}-classpath.txt`), "utf8");
  const initial = await diagnostics(b,p,'before');
  if (mode !== 'baseline') {
    if (initial.proxy.effectiveEnabled !== String(mode !== 'disabled')) throw Error('effective enabled configuration mismatch');
    if (initial.proxy.effectiveRequired !== String(mode === 'required-missing')) throw Error('effective require-velozip configuration mismatch');
  }
  if (mode === 'auth-refusal' && [initial.backend,initial.proxy].some(s=>s.authentication !== 'enabled (redacted)')) throw Error('effective authentication configuration mismatch');
  for (let i=1;i<=repeats;i++) {
    const name = `bot-${i}`;
    process.env.BOT_PORT = String(proxyPort);
    const bot = process.env.BOT_VERSION
      ? start(process.execPath, [path.join(root, 'scripts/legacy-bot.mjs')], root, name)
      : start(java,[`-Dbot.port=${proxyPort}`, '-Dbot.host=127.0.0.1', `-Dbot.seconds=${seconds}`, '-cp', cp.trim(), 'dev.velozip.itest.BotMain'],root,name);
    const exit = new Promise(r => { bot.once('exit',r); bot.once('error',()=>r(1)); });
    const watchdog = setTimeout(()=>bot.kill('SIGKILL'),(seconds+45)*1000);
    await sleep(10000);
    const live = await diagnostics(b,p,`live-${i}`);
    const code = await exit;
    clearTimeout(watchdog);
    const text = logText(name);
    const play = text.includes('BOT reached PLAY state');
    const healthy = text.includes('BOT finished: success=true');
    const holdMilliseconds = Number(text.match(/BOT healthy hold milliseconds=(\d+)/)?.[1] || 0);
    result.bots.push({iteration:i,exit:code,play,healthy,holdMilliseconds});
    await sleep(2000);
    await diagnostics(b,p,`after-${i}`);
    if (['required-missing','outdated'].includes(mode)) {
      if (code !== 1 || healthy) throw Error('expected bot failure with exit 1');
    } else if (code !== 0 || !play || !healthy || holdMilliseconds < seconds*1000) throw Error(`bot failed healthy-hold criteria with exit ${code}`);
    if (mode === 'active' && (live.backend.active !== 1 || live.proxy.active !== 1)) throw Error('live active connections must be 1 bilaterally');
  }
  for (const name of ['backend','proxy']) {
    const count = (logText(name).match(/VeloZip transport enabled/g) || []).length;
    if (mode === 'active' ? count !== repeats : count !== 0) throw Error(`${name}: unexpected activation count ${count}`);
  }
  const proxyText = logText('proxy');
  if (['missing','required-missing'].includes(mode) && !proxyText.includes('negotiation timeout')) throw Error('no timeout status evidence');
  if (mode === 'auth-refusal' && !proxyText.includes('refused (code')) throw Error('no refusal status evidence');
  if (proxyText.includes('failed to restore vanilla decoder')) throw Error('vanilla fallback failed');
  if (mode === 'active') {
    const final = result.snapshots.at(-1);
    if (final.backend.active !== 0 || final.proxy.active !== 0) throw Error('active connection counter leaked');
    if (JSON.stringify(final.backend.directions.TX) !== JSON.stringify(final.proxy.directions.RX)
        || JSON.stringify(final.backend.directions.RX) !== JSON.stringify(final.proxy.directions.TX)) throw Error('final bilateral counters differ');
    for (const side of ['backend','proxy']) for (const direction of ['TX','RX']) {
      if (!(final[side].directions[direction].originalBytes > 0)) throw Error('missing bilateral traffic');
    }
  }
  result.passed = true;
  console.log(`PASS: ${result.case}; evidence ${run}`);
} catch (e) {
  result.error = e.message;
  console.error(`FAIL: ${e.message}; evidence ${run}`);
  process.exitCode=1;
} finally {
  for (const child of children) if (child.exitCode === null) child.kill('SIGTERM');
  await sleep(5000);
  for (const child of children) if (child.exitCode === null && child.signalCode === null) child.kill('SIGKILL');
  await sleep(1000);
  result.cleanup = children.every(c => c.exitCode !== null || c.signalCode !== null);
  result.finished = new Date().toISOString();
  fs.writeFileSync(path.join(run,'result.json'), JSON.stringify(result,null,2)+'\n');
}
