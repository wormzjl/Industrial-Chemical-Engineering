// W3b baseline: writes a copy of PengRobinsonKernel.java with a public static LongAdder EVALUATIONS counted at the
// start of evaluatePrepared (every phase evaluation), for the counting overlay of IslandBaseline. The repository file is
// read, never written. Usage (repository root): node tools/aqueous-solvents/probes/kernel-count.mjs <out-dir>
import fs from 'node:fs';
import path from 'node:path';
const source = 'src/main/java/com/wormzjl/icheme/science/thermo/PengRobinsonKernel.java';
const out = process.argv[2];
let s = fs.readFileSync(source, 'utf8');
const head = /public final class PengRobinsonKernel \{/;
if (!head.test(s)) throw new Error('class head not found');
s = s.replace(head, m => m + '\n    public static final java.util.concurrent.atomic.LongAdder EVALUATIONS = new java.util.concurrent.atomic.LongAdder();');
const entry = /private void evaluatePrepared\(([^)]*)\) \{/;
if (!entry.test(s)) throw new Error('evaluatePrepared not found');
s = s.replace(entry, m => m + '\n        EVALUATIONS.increment();');
fs.mkdirSync(out, { recursive: true });
fs.writeFileSync(path.join(out, 'PengRobinsonKernel.java'), s);
console.log('wrote ' + path.join(out, 'PengRobinsonKernel.java'));
