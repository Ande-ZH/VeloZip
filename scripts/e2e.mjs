// Isolated loopback smoke test. Never runs in the source runtime directory.
// JAVA=/absolute/java BACKEND_JAR=/absolute/jar PROXY_JAR=/absolute/jar node scripts/e2e.mjs
import fs from 'node:fs';
import path from 'node:path';
import net from 'node:net';
import {spawn} from 'node:child_process';
import {fileURLToPath} from 'node:url';
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const java = process.env.JAVA;
for (const key of ['JAVA', 'BACKEND_JAR', 'PROXY_JAR']) {
  if (!path.isAbsolute(process.env[key] || '') || !fs.existsSync(process.env[key])) throw Error(`${key} must be an existing absolute path`);
}
const run = fs.mkdtempSync(path.join(root, 'build/e2e-'));
const children = [];
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
  fs.copyFileSync(path.join(root,'velozip-backend/build/libs/VeloZip-Backend-0.3.0.jar'),path.join(backend,'plugins/VeloZip.jar'));
  fs.copyFileSync(path.join(root,'velozip-velocity/build/libs/VeloZip-Velocity-0.3.0.jar'),path.join(proxy,'plugins/VeloZip.jar'));
  fs.writeFileSync(path.join(backend,'eula.txt'),'eula=true\n');
  fs.writeFileSync(path.join(backend,'server.properties'),`server-ip=127.0.0.1\nserver-port=${backendPort}\nonline-mode=false\nlevel-name=fresh-world\nlevel-type=minecraft:flat\ngenerate-structures=false\nview-distance=3\nsimulation-distance=3\nspawn-protection=0\n`);
  const secret = 'isolated-e2e-only-not-production';
  fs.writeFileSync(path.join(backend,'config/paper-global.yml'),`_version: 31\nproxies:\n  velocity:\n    enabled: true\n    online-mode: false\n    secret: '${secret}'\n`);
  fs.writeFileSync(path.join(proxy,'forwarding.secret'),secret);
  fs.writeFileSync(path.join(proxy,'velocity.toml'),`config-version = "2.7"\nbind = "127.0.0.1:${proxyPort}"\nonline-mode = false\nplayer-info-forwarding-mode = "modern"\nforwarding-secret-file = "forwarding.secret"\n[servers]\nbackend = "127.0.0.1:${backendPort}"\ntry = ["backend"]\n[forced-hosts]\n"lobby.example.com" = ["backend"]\n"factions.example.com" = ["backend"]\n"minigames.example.com" = ["backend"]\n`);
  const b = start(process.env.BACKEND_JAVA || java,['-Xms256M','-Xmx1200M','-jar','server.jar','--nogui'],backend,'backend');
  await ready(b,'backend',/Done \(/);
  const p = start(java,['-Xms128M','-Xmx512M','-jar','proxy.jar'],proxy,'proxy');
  await ready(p,'proxy',/Done \(/);
  const task = process.env.BOT_TASK || "bot";
  const cp = fs.readFileSync(path.join(root, `velozip-itest/build/${task}-classpath.txt`), "utf8");
  const bot = start(java,[`-Dbot.port=${proxyPort}`, "-Dbot.host=127.0.0.1", "-Dbot.seconds=15", "-cp", cp, "dev.velozip.itest.BotMain"],root,"bot");
  const code = await new Promise(r => { bot.once('exit',r); bot.once('error',()=>r(1)); });
  if (code !== 0) throw Error(`bot failed with exit ${code}`);
  for (const name of ['backend','proxy']) if (!fs.readFileSync(path.join(run,`${name}.log`),'utf8').includes('VeloZip transport enabled')) throw Error(`${name}: no transport activation evidence`);
  console.log(`PASS: join, hold and bilateral activation; evidence ${run}`);
} catch (e) {
  console.error(`FAIL: ${e.message}; evidence ${run}`);
  process.exitCode=1;
} finally {
  for (const child of children) if (child.exitCode === null) child.kill('SIGTERM');
  await sleep(5000);
  for (const child of children) if (child.exitCode === null) child.kill('SIGKILL');
}
