// Proves that the split of ControlService (consolidation review 2026-11-08) moved code: each original method is
// compared token by token (layout and comments ignored) with the method it became, and the cadence is compared with
// its new helpers inlined at their call sites. Every difference is printed for review.
// Usage, from the repository root:
//   git show <commit before the split>:src/main/java/com/wormzjl/icheme/runtime/control/ControlService.java > tmp/<name>/ControlService.orig.java
//   node tools/simulator-sizing/consolidation/move-check.js tmp/<name>/ControlService.orig.java src/main/java/com/wormzjl/icheme/runtime/control
const fs = require('fs');
const path = require('path');

const TOKEN = /\s+|\/\/[^\n]*|\/\*[\s\S]*?\*\/|"(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*'|[A-Za-z_$][\w$]*|\d[\w.]*|->|::|[^\sA-Za-z_$\d]/g;
const tokens = text => (text.match(TOKEN) || []).filter(t => !/^\s/.test(t) && !t.startsWith('//') && !t.startsWith('/*'));
const KEYWORDS = ['if', 'for', 'while', 'switch', 'catch', 'return', 'new'];

/** Methods of a class body by name (overloads get #2, #3): the token run from the name to the closing brace. */
function methods(text) {
    const t = tokens(text);
    const out = new Map();
    let depth = 0;
    for (let i = 0; i < t.length; i++) {
        if (t[i] === '{') { depth++; continue; }
        if (t[i] === '}') { depth--; continue; }
        if (depth !== 1 || !/^[A-Za-z_$][\w$]*$/.test(t[i]) || t[i + 1] !== '(' || KEYWORDS.includes(t[i])) continue;
        let j = i + 1, paren = 0;
        for (; j < t.length; j++) { if (t[j] === '(') paren++; else if (t[j] === ')' && --paren === 0) break; }
        let k = j + 1;
        while (k < t.length && t[k] !== '{' && t[k] !== ';') k++;
        if (t[k] !== '{') continue;
        let d = 0, e = k;
        for (; e < t.length; e++) { if (t[e] === '{') d++; else if (t[e] === '}' && --d === 0) break; }
        let name = t[i], n = 2;
        while (out.has(name)) name = t[i] + '#' + n++;
        out.set(name, t.slice(i, e + 1));
        i = e;
    }
    return out;
}

/** Token hunks between a and b (longest common subsequence). */
function diff(a, b) {
    const n = a.length, m = b.length;
    const dp = Array.from({ length: n + 1 }, () => new Int32Array(m + 1));
    for (let i = n - 1; i >= 0; i--) for (let j = m - 1; j >= 0; j--)
        dp[i][j] = a[i] === b[j] ? dp[i + 1][j + 1] + 1 : Math.max(dp[i + 1][j], dp[i][j + 1]);
    const hunks = [];
    let i = 0, j = 0, cur = null;
    const flush = () => { if (cur) { hunks.push(cur); cur = null; } };
    while (i < n || j < m) {
        if (i < n && j < m && a[i] === b[j]) { flush(); i++; j++; continue; }
        if (!cur) cur = { at: a.slice(Math.max(0, i - 4), i).join(' '), del: [], add: [] };
        if (j < m && (i === n || dp[i][j + 1] >= dp[i + 1][j])) cur.add.push(b[j++]);
        else cur.del.push(a[i++]);
    }
    flush();
    return hunks;
}

function report(title, a, b) {
    const hunks = diff(a, b);
    console.log(`${title}: ${a.length} tokens, ${hunks.length} differences`);
    for (const h of hunks) console.log(`    after [${h.at}]\n      - ${h.del.join(' ')}\n      + ${h.add.join(' ')}`);
    return hunks.length;
}

const [originalFile, dir] = process.argv.slice(2);
if (!originalFile || !dir) throw new Error('usage: move-check.js <original ControlService.java> <control package dir>');
const old = methods(fs.readFileSync(originalFile, 'utf8'));
const now = {};
for (const c of ['ControlService', 'ControlHosts', 'ControlLaw', 'ControlPlans', 'ControlPublication'])
    now[c] = methods(fs.readFileSync(path.join(dir, c + '.java'), 'utf8'));

const pairs = [
    ['apply', 'ControlPlans', 'apply'], ['cable', 'ControlService', 'cable'], ['removeCable', 'ControlService', 'removeCable'],
    ['loaded', 'ControlService', 'loaded'], ['loaded#2', 'ControlService', 'loaded#2'], ['loaded#3', 'ControlService', 'loaded#3'],
    ['removePanel', 'ControlService', 'removePanel'], ['bind', 'ControlHosts', 'bind'], ['syncPort', 'ControlHosts', 'syncPort'],
    ['bindColumn', 'ControlHosts', 'bindColumn'], ['bindMotor', 'ControlHosts', 'bindMotor'],
    ['refreshColumnMembers', 'ControlHosts', 'refreshColumnMembers'], ['connectedHosts', 'ControlHosts', 'connectedHosts'],
    ['removeHost', 'ControlHosts', 'removeHost'], ['bus', 'ControlHosts', 'bus'], ['watch', 'ControlPublication', 'watch'],
    ['unwatch', 'ControlPublication', 'unwatch'], ['edit', 'ControlService', 'edit'], ['accessible', 'ControlService', 'accessible'],
    ['wired', 'ControlHosts', 'wired'], ['control', 'ControlLaw', 'decide'], ['label', 'ControlPublication', 'label'],
    ['page', 'ControlPublication', 'page'], ['sensorLocation', 'ControlPublication', 'sensorLocation'],
    ['snapshot', 'ControlPublication', 'snapshot'],
];
let total = 0;
for (const [from, cls, to] of pairs) {
    const a = old.get(from), b = now[cls].get(to);
    if (!a || !b) { console.log(`MISSING ${from} -> ${cls}.${to}`); total++; continue; }
    total += report(`${from} -> ${cls}.${to}`, a, b);
}

// The cadence, with every helper inlined at its call site so that the order of work is the original's.
const inner = (c, m) => { const t = now[c].get(m); return t.slice(t.indexOf('{') + 1, t.length - 1); };
function splice(list, call, body) {
    const s = call.split(' ');
    for (let i = 0; i + s.length <= list.length; i++)
        if (s.every((x, k) => list[i + k] === x)) return [...list.slice(0, i), ...body, ...list.slice(i + s.length)];
    throw new Error('call not found: ' + call);
}
let inlined = inner('ControlService', 'tick');
inlined = splice(inlined, 'var sensors = hosts . inputs ( ) ;', inner('ControlHosts', 'inputs'));
inlined = splice(inlined, 'applyEdits ( sensors ) ;', inner('ControlService', 'applyEdits'));
inlined = splice(inlined, 'var readings = readings ( sensors ) ;', inner('ControlService', 'readings'));
inlined = splice(inlined, 'publication . watchedHosts ( needed ) ;', inner('ControlPublication', 'watchedHosts'));
inlined = splice(inlined, 'runControllers ( sensors , readings ) ;', inner('ControlService', 'runControllers'));
inlined = splice(inlined, 'world . controlInputsChanged ( plans . commit ( nextValves , nextHeat ) ) ;',
    [...inner('ControlPlans', 'commit'), ...'world . controlInputsChanged ( changed ) ;'.split(' ')]);
inlined = splice(inlined, 'publication . publish ( sensors , readings ) ;', inner('ControlPublication', 'publish'));
const oldTick = old.get('tick');
total += report('tick, helpers inlined', oldTick.slice(oldTick.indexOf('{') + 1, oldTick.length - 1), inlined);

const covered = new Set([...pairs.map(p => p[0]), 'tick']);
console.log('not compared (records, constructor, facts()):', [...old.keys()].filter(k => !covered.has(k)).join(', '));
console.log('total differences:', total);
