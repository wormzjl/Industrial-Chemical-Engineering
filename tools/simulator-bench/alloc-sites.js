// Allocation weight by the first mod frame and the allocated class, from a JFR recording's jdk.ObjectAllocationSample events
// (README.md), streamed from `jfr print`. JFR's own threads are left out. node alloc-sites.js <recording.jfr> [rows] [jdk folder]
const { spawn } = require('child_process');
const readline = require('readline');
const path = require('path');
const [file, rowsArg, jdkArg] = process.argv.slice(2);
const jdk = jdkArg || 'C:\\Program Files\\Java\\jdk-21.0.11';
const jfr = spawn(path.join(jdk, 'bin', 'jfr.exe'), ['print', '--stack-depth', '12', '--events', 'jdk.ObjectAllocationSample', file]);
const units = { bytes: 1, byte: 1, kB: 1024, MB: 1048576, GB: 1073741824 };
const sites = new Map();
let total = 0, skipped = 0, event = null;
function close() {
  if (!event) return;
  if (/JFR|Recorder|Periodic/i.test(event.thread)) skipped += event.weight;
  else {
    const key = (event.site || '(no mod frame)') + '  ' + event.cls;
    sites.set(key, (sites.get(key) || 0) + event.weight);
    total += event.weight;
  }
  event = null;
}
readline.createInterface({ input: jfr.stdout }).on('line', line => {
  const t = line.trim();
  if (t.startsWith('jdk.ObjectAllocationSample')) { close(); event = { weight: 0, cls: '?', thread: '', site: null }; return; }
  if (!event) return;
  let m;
  if ((m = t.match(/^objectClass = (\S+)/))) event.cls = m[1];
  else if ((m = t.match(/^weight = ([\d.,]+) (\w+)/))) event.weight = parseFloat(m[1].replace(',', '')) * (units[m[2]] || 1);
  else if ((m = t.match(/^eventThread = "([^"]*)"/))) event.thread = m[1];
  else if (!event.site && t.startsWith('com.wormzjl.icheme.')) {
    m = t.match(/^com\.wormzjl\.icheme\.(?:science\.)?([\w.$]+)\(.*line: (\d+)/);
    if (m) event.site = m[1] + ':' + m[2];
  }
});
jfr.on('close', () => {
  close();
  console.log(`total ${(total / 1048576).toFixed(0)} MB sampled weight (JFR threads ${(skipped / 1048576).toFixed(0)} MB left out)`);
  for (const [key, w] of [...sites].sort((a, b) => b[1] - a[1]).slice(0, Number(rowsArg || 30)))
    console.log(`${(100 * w / total).toFixed(1).padStart(5)} %  ${key}`);
});
