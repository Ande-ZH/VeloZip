// Publish allowlisted evidence only; never copy raw runtime logs/configuration.
// node scripts/e2e-evidence.mjs /absolute/build/e2e-matrix-<id>
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {fileURLToPath} from 'node:url';
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const matrix = process.argv[2];
const reruns = process.argv.slice(3);
if (!matrix || !path.isAbsolute(matrix) || !matrix.startsWith(path.join(root,'build/e2e-matrix-'))) throw Error('expected absolute local matrix directory');
const output = path.join(root,'docs/e2e/v0.3.0-20260907');
fs.mkdirSync(output,{recursive:true});
const sha = data => crypto.createHash('sha256').update(data).digest('hex');
const results = [];
const mappings = [];
for (const directory of [matrix,...reruns]) for (const file of fs.readdirSync(directory).filter(f=>f.endsWith('.log')).sort()) {
  const run = fs.readFileSync(path.join(directory,file),'utf8').match(/evidence (.+)/)?.[1];
  if (!run) throw Error(`missing result path: ${file}`);
  const result = JSON.parse(fs.readFileSync(path.join(run,'result.json'),'utf8'));
  if (directory === matrix && ['missing-required','auth-refusal','proxy-disabled'].includes(result.case)) {
    result.case += '-invalid-fixture';
    result.excluded = 'Proxy configuration written to unused plugins/velozip-velocity; rerun uses actual plugins/velozip';
  }
  if (results.some(r=>r.case === result.case)) {
    result.case += '-guard-verification';
    result.supplemental = true;
  }
  const evidence = [];
  result.rawLogSha256 = {};
  for (const log of fs.readdirSync(run).filter(f=>f.endsWith('.log')).sort()) {
    const data = fs.readFileSync(path.join(run,log));
    result.rawLogSha256[log] = sha(data);
    const text = data.toString().replace(/\x1b\[[0-9;]*[A-Za-z]/g,'').replace(/\r/g,'');
    evidence.push(`--- ${log} (allowlisted excerpts, not full log) ---`);
    for (const line of text.split('\n')) {
      const match = line.match(/(BOT reached PLAY state.*|BOT healthy hold milliseconds=\d+|BOT finished: success=(?:true|false)|VeloZip transport enabled for (?:VeloZipBot|backend)|VeloZip 0\.3\.0.*|Transport Protocol: \d+|Active connections: \d+|Total connections: \d+|[TR]X: DirectionSnapshot\[[^\]]+\]|\{backend=ServerStatus\[[^\]]+\]\}|VeloZip: backend refused VeloZip for backend:.*|VeloZip: no VeloZip response from backend.*|VeloZip: failed to restore vanilla decoder|VeloZip: refusing activation for VeloZipBot \(4\): (?:HMAC verification failed|this backend requires a shared secret)|enabled=(?:true|false)|require-velozip=(?:true|false) debug=(?:true|false)|authentication=(?:disabled|enabled \(redacted\))|Starting Minecraft server version [\w.]+|This server is running (?:Paper|Purpur) version .*|Booting up Velocity [\w.()\-+ /]+|Loading server plugin VeloZip v[\w.]+|Outdated client!.*)/);
      if (match) evidence.push(match[1]);
    }
  }
  const last = result.snapshots.at(-1);
  if (result.mode === 'active' && last) {
    result.applicationFrameBytes = {};
    for (const side of ['backend','proxy']) {
      const d = last[side].directions;
      const originalBytes = d.TX.originalBytes + d.RX.originalBytes;
      const wireBytes = d.TX.wireBytes + d.RX.wireBytes;
      result.applicationFrameBytes[side] = {originalBytes,wireBytes,savingsPercent:100*(1-wireBytes/originalBytes)};
    }
    result.finalBilateralCountersEqual = JSON.stringify(last.backend.directions.TX) === JSON.stringify(last.proxy.directions.RX) && JSON.stringify(last.backend.directions.RX) === JSON.stringify(last.proxy.directions.TX);
  }
  fs.writeFileSync(path.join(output,`${result.case}.txt`), evidence.join('\n')+'\n');
  results.push(result);
  mappings.push({case:result.case,rawDirectory:run});
}
fs.writeFileSync(path.join(output,'results.json'),JSON.stringify({schema:1,date:'2026-09-07',transportProtocol:1,pluginVersion:'0.3.0',metric:'application frame bytes; not TCP capture',results},null,2)+'\n');
const sources = ['scripts/e2e.mjs','scripts/e2e-matrix.mjs','scripts/e2e-evidence.mjs','velozip-itest/src/main/java/dev/velozip/itest/BotMain.java'];
const botDeps = {};
for (const task of ['bot','botLegacy']) botDeps[task] = fs.readFileSync(path.join(root,`velozip-itest/build/${task}-classpath.txt`),'utf8').trim().split(':').filter(f=>f.endsWith('.jar')).map(f=>({name:path.basename(f),sha256:sha(fs.readFileSync(f))}));
fs.writeFileSync(path.join(output,'fixture.json'),JSON.stringify({sourceCommit:'6a3065bc2393027d34183824a7c77a8e163bfc64',sources:Object.fromEntries(sources.map(f=>[f,sha(fs.readFileSync(path.join(root,f)))])),botClassSha256:sha(fs.readFileSync(path.join(root,'velozip-itest/build/classes/java/main/dev/velozip/itest/BotMain.class'))),botDeps,configuration:{bind:'loopback ephemeral',freshWorld:true,worldType:'minecraft:flat',viewDistance:3,simulationDistance:3,backendHeapMiB:1200,proxyHeapMiB:512,onlineMode:false,modernForwarding:true,forwardingSecret:'random per run, omitted',authentication:'empty except intentionally mismatched random secrets in auth-refusal; values omitted',compression:'v0.3.0 defaults',sampling:'before, ~10s after launch, 2s after bot exit; counters cumulative',hold:'30 seconds after PLAY',reconnect:'3 logins, same bot name, same isolated world, 2s settle plus diagnostics between connections'}},null,2)+'\n');
fs.writeFileSync(path.join(matrix,'raw-index.json'),JSON.stringify(mappings,null,2)+'\n');
const checksums = fs.readdirSync(output).filter(f=>f!=='SHA256SUMS').sort().map(f=>`${sha(fs.readFileSync(path.join(output,f)))}  ${f}`).join('\n')+'\n';
fs.writeFileSync(path.join(output,'SHA256SUMS'),checksums);
console.log(`Sanitized ${results.length} cases to ${output}`);
