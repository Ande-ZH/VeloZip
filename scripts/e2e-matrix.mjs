// Sequential isolated v0.3.0 experiment. Fixtures are local inputs, never shared services.
import fs from 'node:fs';
import path from 'node:path';
import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const build = path.join(root,'build');
const tools = path.join(build,'private-tools');
const java = process.env.JAVA || '/env/zulu25/bin/java';
const java21 = process.env.JAVA21 || path.join(tools,'zulu21.52.203-ca-crac-jdk21.0.12.1-linux_x64/bin/java');
const proxy = process.env.PROXY_JAR || path.join(build,'e2e-neyvLK/proxy/proxy.jar');
const cases = [
  ['paper121-baseline','paper-1.21.11-132.jar','e2e-neyvLK','baseline','botLegacy',java21],
  ['paper121-reconnect','paper-1.21.11-132.jar','e2e-neyvLK','active','botLegacy',java21,3],
  ['purpur121','purpur-1.21.11-2568.jar','e2e-r6uGKY','active','botLegacy',java21],
  ['paper261','paper-26.1.2-74.jar','e2e-db1YgI','active','bot',java],
  ['purpur261',path.join(build,'e2e-fbKX7u/backend/server.jar'),'e2e-fbKX7u','active','bot',java],
  ['velocity340-paper121','paper-1.21.11-132.jar','e2e-neyvLK','active','botLegacy',java21,1,path.join(tools,'velocity-3.4.0-563.jar')],
  ['paper262-via','paper-26.2-121.jar','e2e-5aelNj','active','bot',java],
  ['paper262-native-outdated','paper-26.2-121.jar','e2e-5aelNj','outdated','bot',java],
  ['missing-fallback','paper-1.21.11-132.jar','e2e-neyvLK','missing','botLegacy',java21],
  ['missing-required','paper-1.21.11-132.jar','e2e-neyvLK','required-missing','botLegacy',java21],
  ['auth-refusal','paper-1.21.11-132.jar','e2e-neyvLK','auth-refusal','botLegacy',java21],
  ['proxy-disabled','paper-1.21.11-132.jar','e2e-neyvLK','disabled','botLegacy',java21],
];
const output = path.join(build,`e2e-matrix-${Date.now()}`);
fs.mkdirSync(output);
for (const [id,jar,seed,mode,bot,backendJava,repeats=1,proxyJar=proxy] of cases) {
  if (process.env.CASE_FILTER && !process.env.CASE_FILTER.split(',').some(filter=>id.includes(filter))) continue;
  const env = {...process.env,JAVA:java,BACKEND_JAVA:backendJava,BACKEND_JAR:path.isAbsolute(jar)?jar:path.join(tools,jar),PROXY_JAR:proxyJar,BACKEND_SEED:path.join(build,seed,'backend'),BOT_TASK:bot,BOT_SECONDS:'30',BOT_REPEATS:String(repeats),E2E_CASE:id,E2E_MODE:mode,VIA_JARS:id==='paper262-via'?['ViaVersion','ViaBackwards'].map(v=>path.join(build,`e2e-5aelNj/backend/plugins/${v}-5.12.0-SNAPSHOT.jar`)).join(':'):''};
  console.log(`START ${id} ${new Date().toISOString()}`);
  const r = spawnSync(process.execPath,[path.join(root,'scripts/e2e.mjs')],{env,encoding:'utf8'});
  fs.writeFileSync(path.join(output,`${id}.log`),(r.stdout||'')+(r.stderr||''));
  console.log((r.stdout||'')+(r.stderr||''));
  if (r.error) throw r.error;
  if (r.status !== 0) process.exitCode = 1; // Continue collecting cases, but never hide a failed expectation.
}
console.log(`Matrix runner logs: ${output}`);
