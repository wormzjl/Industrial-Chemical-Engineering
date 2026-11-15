// Heap histogram reader (B1, PROFILE_REVIEW.md). Output of `jcmd <pid> GC.class_histogram` (or the DiagnosticCommand MBean).
// node histo.mjs <after.txt> [before.txt] [rows] [package-prefix]
// With two files: the delta by class (after minus before). The mod's classes (com.wormzjl.) are totalled by package.
import { readFileSync } from "node:fs";
const [afterFile, beforeFile, rowsArg, prefix] = process.argv.slice(2);
const rows = Number(rowsArg || 20);
function read(file) {
  const map = new Map();
  if (!file || file === "-") return map;
  for (const line of readFileSync(file, "utf8").split(/\r?\n/)) {
    const m = line.match(/^\s*\d+:\s+(\d+)\s+(\d+)\s+(\S+)/);
    if (m) map.set(m[3], { n: Number(m[1]), b: Number(m[2]) });
  }
  return map;
}
const after = read(afterFile), before = read(beforeFile);
const delta = [];
for (const [cls, a] of after) {
  const b = before.get(cls) || { n: 0, b: 0 };
  delta.push({ cls, n: a.n - b.n, b: a.b - b.b });
}
const total = delta.reduce((s, x) => s + x.b, 0);
console.log(`total ${(total / 1e6).toFixed(2)} MB (${beforeFile && beforeFile !== "-" ? "after minus before" : "live after a full GC"})`);
const byPackage = new Map();
for (const x of delta) if (x.cls.startsWith("com.wormzjl.")) {
  const pkg = x.cls.replace(/\.[^.]+$/, "").replace("com.wormzjl.icheme.", "");
  byPackage.set(pkg, (byPackage.get(pkg) || 0) + x.b);
}
const modTotal = [...byPackage.values()].reduce((s, v) => s + v, 0);
console.log(`mod classes ${(modTotal / 1e6).toFixed(2)} MB`);
console.log("\n| Package | MB |\n|---|---|");
for (const [p, b] of [...byPackage].sort((a, b) => b[1] - a[1]).slice(0, 12)) console.log(`| ${p} | ${(b / 1e6).toFixed(3)} |`);
console.log("\n| # | Class | Instances | MB |\n|---|---|---|---|");
delta.filter((x) => !prefix || x.cls.startsWith(prefix)).sort((a, b) => b.b - a.b).slice(0, rows)
  .forEach((x, i) => console.log(`| ${i + 1} | \`${x.cls.replace("com.wormzjl.icheme.", "")}\` | ${x.n} | ${(x.b / 1e6).toFixed(3)} |`));
