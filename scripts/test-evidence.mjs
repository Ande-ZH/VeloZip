// 导出不含原始控制台输出的 JUnit 结构化结果；先运行对应的 Gradle test 命令。
// node scripts/test-evidence.mjs 17 4.1.68.Final
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {fileURLToPath} from 'node:url';
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const [java, netty] = process.argv.slice(2);
if (!['17', '25'].includes(java) || !/^4\.[12]\.\d+\.Final$/.test(netty || '')) throw Error('需要明确的 Java/Netty 测试环境');
const suites = [];
const totals = {tests: 0, failures: 0, errors: 0, skipped: 0};
const sha = data => crypto.createHash('sha256').update(data).digest('hex');
const attribute = (text, name) => text.match(new RegExp('\\b' + name + '="([^"]*)"'))?.[1];
for (const module of ['common', 'backend', 'velocity']) {
  const folder = path.join(root, `velozip-${module}/build/test-results/test`);
  for (const name of fs.readdirSync(folder).filter(n => /^TEST-.*\.xml$/.test(n)).sort()) {
    const xml = fs.readFileSync(path.join(folder, name), 'utf8');
    const header = xml.match(/<testsuite\s+[^>]+>/)?.[0];
    if (!header) throw Error('无效的 JUnit 报告');
    const suite = {module, name: attribute(header, 'name'), timestamp: attribute(header, 'timestamp'),
      seconds: Number(attribute(header, 'time')), reportSha256: sha(xml), cases: []};
    for (const key of Object.keys(totals)) { suite[key] = Number(attribute(header, key)); totals[key] += suite[key]; }
    for (const match of xml.matchAll(/<testcase\s+[^>]+>/g)) {
      suite.cases.push({name: attribute(match[0], 'name'), className: attribute(match[0], 'classname'),
        seconds: Number(attribute(match[0], 'time'))});
    }
    suites.push(suite);
  }
}
if (!totals.tests || totals.failures || totals.errors || totals.skipped) throw Error('测试未全部通过，不能导出通过记录');
const output = path.join(root, 'docs/tests/v1.0.0');
fs.mkdirSync(output, {recursive: true});
fs.writeFileSync(path.join(output, `java${java}-netty${netty}.json`), JSON.stringify({
  pluginVersion: '1.0.0', java: Number(java), netty, leakDetection: 'PARANOID',
  description: '相同源码构建的 JUnit 报告，Gradle 可能从缓存恢复；时间戳保留原始值。',
  totals, suites,
}, null, 2) + '\n');
console.log(JSON.stringify(totals));
