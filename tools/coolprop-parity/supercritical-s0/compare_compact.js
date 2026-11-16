// Proves a compact fixture holds the same content as the tracked one: equal parsed documents (key order included)
// and the same number tokens, text for text. Run from the worktree root: node tmp/coolprop-compact/compare.js
const fs = require("fs");
const tracked = "src/test/resources/science/thermo/coolprop/";
const compact = "tmp/coolprop-compact/out/";
const number = /-?\d+(?:\.\d+)?(?:[eE][-+]?\d+)?/g;
// Number tokens outside strings: drop string literals first.
const tokens = text => text.replace(/"(?:[^"\\]|\\.)*"/g, '""').match(number) || [];
let before = 0, after = 0, linesBefore = 0, linesAfter = 0, bad = 0;
for (const name of fs.readdirSync(compact).sort()) {
  const a = fs.readFileSync(tracked + name, "utf8"), b = fs.readFileSync(compact + name, "utf8");
  const same = JSON.stringify(JSON.parse(a)) === JSON.stringify(JSON.parse(b));
  const ta = tokens(a), tb = tokens(b);
  const text = ta.length === tb.length && ta.every((t, i) => t === tb[i]);
  const la = a.split("\n").length - 1, lb = b.split("\n").length - 1;
  before += a.length; after += b.length; linesBefore += la; linesAfter += lb;
  if (!same || !text || b.includes("\r")) bad++;
  console.log(`${name}: document ${same ? "equal" : "DIFFERS"}, ${ta.length} numbers ${text ? "identical" : "DIFFER"}, ${la} -> ${lb} lines, ${a.length} -> ${b.length} bytes`);
}
console.log(`total: ${linesBefore} -> ${linesAfter} lines, ${before} -> ${after} bytes, ${bad} bad`);
process.exit(bad ? 1 : 0);
