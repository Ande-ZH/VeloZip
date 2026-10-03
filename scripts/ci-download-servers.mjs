// Downloads official server jars for the realistic-terrain E2E matrix and
// writes build/ci-fixture.json for scripts/e2e-matrix.mjs. Every download is
// checksum-verified against the source API; existing files are reused.
// Requires JDK16_HOME / JDK17_HOME / JDK21_HOME / JDK25_HOME to point at
// installations containing bin/java (JDK25 also drives the Velocity proxy).
// node scripts/ci-download-servers.mjs
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {fileURLToPath} from 'node:url';
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const outDir = path.join(root, 'build/ci-servers');
fs.mkdirSync(outDir, {recursive: true});

const headers = {'user-agent': 'VeloZip-E2E/1.0 (github.com/Ande-ZH/VeloZip)'};
const fetchJson = async url => {
  const r = await fetch(url, {headers});
  if (!r.ok) throw Error(`HTTP ${r.status} for ${url}`);
  return r.json();
};
const digest = (file, algo) => crypto.createHash(algo).update(fs.readFileSync(file)).digest('hex');
const download = async (url, name, algo, expected) => {
  const file = path.join(outDir, name);
  if (fs.existsSync(file) && expected && digest(file, algo) === expected) return file;
  const r = await fetch(url, {headers});
  if (!r.ok) throw Error(`HTTP ${r.status} downloading ${url}`);
  fs.writeFileSync(file, Buffer.from(await r.arrayBuffer()));
  if (expected && digest(file, algo) !== expected) throw Error(`${algo} mismatch for ${name}`);
  return file;
};

// Latest build of an exact version from the PaperMC fill API (paper, velocity).
const papermc = async (project, version) => {
  const meta = await fetchJson(`https://fill.papermc.io/v3/projects/${project}/versions/${version}/builds/latest`);
  const d = meta.downloads['server:default'];
  return {file: await download(d.url, d.name, 'sha256', d.checksums.sha256), label: `${project}-${version}-${meta.id}`};
};
// Latest Purpur build of an exact version (MD5 is what the Purpur API publishes).
const purpur = async version => {
  const meta = await fetchJson(`https://api.purpurmc.org/v2/purpur/${version}/latest`);
  if (meta.result !== 'SUCCESS') throw Error(`purpur ${version} latest build unavailable`);
  const file = await download(`https://api.purpurmc.org/v2/purpur/${version}/${meta.build}/download`,
    `purpur-${version}-${meta.build}.jar`, 'md5', meta.md5);
  return {file, label: `purpur-${version}-${meta.build}`};
};
// Latest release of a Modrinth project (ViaVersion/ViaBackwards publish sha1).
const modrinth = async slug => {
  const versions = await fetchJson(`https://api.modrinth.com/v2/project/${slug}/version`);
  const pick = versions.find(v => v.version_type === 'release') || versions[0];
  if (!pick) throw Error(`no ${slug} versions on modrinth`);
  const f = pick.files.find(x => x.primary) || pick.files[0];
  return {file: await download(f.url, f.filename, 'sha1', f.hashes.sha1), label: `${slug}-${pick.version_number}`};
};

const javaFor = tag => {
  const home = process.env[`JDK${tag}_HOME`];
  if (home && fs.existsSync(path.join(home, 'bin/java'))) return path.join(home, 'bin/java');
  throw Error(`set JDK${tag}_HOME to a JDK ${tag} installation (must contain bin/java)`);
};

// One representative build per major backend family: latest build of the last
// minor of each series. 26.2 needs Via for the 26.1 protocol bot, matching the
// historical v1.0.0 matrix.
const plan = [
  {id: 'purpur1165-normal', source: () => purpur('1.16.5'), java: 16, botVersion: '1.16.5'},
  {id: 'paper1171-normal', source: () => papermc('paper', '1.17.1'), java: 16, botVersion: '1.17.1'},
  {id: 'purpur1182-normal', source: () => purpur('1.18.2'), java: 17, botVersion: '1.18.2'},
  {id: 'purpur1194-normal', source: () => purpur('1.19.4'), java: 17, botVersion: '1.19.4'},
  {id: 'purpur1204-normal', source: () => purpur('1.20.4'), java: 17, botVersion: '1.20.4'},
  {id: 'paper12111-normal', source: () => papermc('paper', '1.21.11'), java: 21, botTask: 'botLegacy'},
  {id: 'purpur2612-normal', source: () => purpur('26.1.2'), java: 25, botTask: 'bot'},
  {id: 'paper262-normal', source: () => papermc('paper', '26.2'), java: 25, botTask: 'bot',
    via: ['viaversion', 'viabackwards']},
];

const proxy = await papermc('velocity', '4.1.1');
console.log(`proxy: ${proxy.label}`);
const javas = Object.fromEntries(await Promise.all([16, 17, 21, 25].map(async tag => {
  const executable = javaFor(tag);
  return [tag, executable];
})));
const cases = [];
for (const entry of plan) {
  const backend = await entry.source();
  console.log(`${entry.id}: ${backend.label} (java ${entry.java})`);
  const via = [];
  for (const slug of entry.via || []) {
    const jar = await modrinth(slug);
    console.log(`${entry.id}: via ${jar.label}`);
    via.push(jar.file);
  }
  cases.push({
    id: entry.id, backendJar: backend.file, backendJava: javas[entry.java],
    ...(entry.botVersion ? {botVersion: entry.botVersion} : {botTask: entry.botTask}),
    ...(via.length ? {viaJars: via} : {}),
    // Realistic profile: default world generator, vanilla default view and
    // simulation distance, fixed seed for reproducible terrain.
    levelType: 'normal', viewDistance: 10, simDistance: 10, levelSeed: 'velozip-ci', seconds: 30,
  });
}
const fixture = {java: javas[25], proxyJar: proxy.file, cases};
const fixturePath = path.join(root, 'build/ci-fixture.json');
fs.writeFileSync(fixturePath, JSON.stringify(fixture, null, 2) + '\n');
console.log(`fixture: ${fixturePath} (${cases.length} cases)`);
