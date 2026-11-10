// The tests that a branch's changes can reach, from the classes each test class loaded when it last passed
// (README.md of this folder). Usage, from the worktree root, after testClasses and compileFluidGameTestJava:
//   node tools/merge-lane/affected.mjs select [--explain <test class>] [--since <commit>|<A>..<B>]
//   node tools/merge-lane/affected.mjs save <load-log folder> <results folder>...
// select prints: FULL <reason> | TASK <task> NONE|ALL|<n>/<total> <filter>... | STEP <regression|gametest> due|skip <why>
// | NOTE <text>. save adds the load sets of the passed test classes, recorded at HEAD, to the load map.
// --since replays a past batch on today's load sets: every load set counts as recorded at <commit>, the batch's
// base; <A>..<B> takes the changes between two commits instead of those up to the working tree.
// The load map is $LANE_MAP, or tools/merge-lane/logs/load-map.json.gz in the main checkout (git-ignored, shared).
import fs from 'fs';
import path from 'path';
import zlib from 'zlib';
import { execFileSync } from 'child_process';

const [mode = 'select', ...rest] = process.argv.slice(2);
const git = (...a) => execFileSync('git', a, { encoding: 'utf8', maxBuffer: 1 << 28 }).replace(/\r/g, '');
const gitOk = (...a) => { try { execFileSync('git', a, { stdio: 'ignore' }); return true; } catch { return false; } };
const root = git('rev-parse', '--show-toplevel').trim();
const mainCheckout = git('worktree', 'list', '--porcelain').split('\n')[0].replace(/^worktree /, '').trim();
const mapFile = process.env.LANE_MAP || path.join(mainCheckout, 'tools/merge-lane/logs/load-map.json.gz');
const SETS = ['main', 'test', 'fluidGameTest'];
const PREFIX = 'com/wormzjl/icheme/';
const KEEP = 3; // load sets kept per test class, newest first: one per recent commit line
const out = [];
let range = null; // --since A..B
const emit = line => out.push(line);

// ---- class files: source file of each class, string constants, test classes ------------------------------------------
function parseClass(buf) {
  let p = 8;
  const u1 = () => buf[p++], u2 = () => { const v = buf.readUInt16BE(p); p += 2; return v; };
  const u4 = () => { const v = buf.readUInt32BE(p); p += 4; return v; };
  const n = u2(), utf = [], strings = [], classRefs = [];
  for (let i = 1; i < n; i++) {
    const tag = u1();
    if (tag === 1) { const len = u2(); utf[i] = buf.toString('utf8', p, p + len); p += len; }
    else if (tag === 7) classRefs[i] = u2();
    else if (tag === 8) strings.push(u2());
    else if (tag === 16 || tag === 19 || tag === 20) p += 2;
    else if (tag === 15) p += 3;
    else if ([3, 4, 9, 10, 11, 12, 17, 18].includes(tag)) p += 4;
    else if (tag === 5 || tag === 6) { p += 8; i++; }
    else throw new Error(`constant tag ${tag}`);
  }
  const access = u2(), self = utf[classRefs[u2()]], superIndex = u2();
  const superName = superIndex ? utf[classRefs[superIndex]] : null;
  const interfaces = u2(); p += 2 * interfaces;
  const skipMembers = () => { for (let m = u2(); m > 0; m--) { p += 6; for (let a = u2(); a > 0; a--) { p += 2; const len = u4(); p += len; } } };
  skipMembers(); skipMembers();
  let sourceFile = null;
  for (let a = u2(); a > 0; a--) {
    const name = utf[u2()], len = u4();
    if (name === 'SourceFile') sourceFile = utf[buf.readUInt16BE(p)];
    p += len;
  }
  return { self, superName, access, sourceFile, utf: utf.filter(Boolean), strings: strings.map(i => utf[i]).filter(s => s != null) };
}

const classes = new Map(); // internal name -> class
for (const set of SETS) {
  const dir = path.join(root, 'build/classes/java', set);
  if (!fs.existsSync(dir)) { console.error(`no compiled classes in ${path.relative(root, dir)}: run testClasses compileFluidGameTestJava`); process.exit(2); }
  const walk = d => { for (const e of fs.readdirSync(d, { withFileTypes: true })) {
    const f = path.join(d, e.name);
    if (e.isDirectory()) walk(f);
    else if (e.name.endsWith('.class')) {
      const c = parseClass(fs.readFileSync(f));
      const pkg = c.self.slice(0, c.self.lastIndexOf('/') + 1), outer = c.self.slice(pkg.length).split('$')[0];
      let node = `src/${set}/java/${pkg}${c.sourceFile ?? outer + '.java'}`;
      if (!fs.existsSync(path.join(root, node))) node = `src/${set}/java/${pkg}${outer}.java`;
      classes.set(c.self, { ...c, node, set });
    } } };
  walk(dir);
}
const nodeOf = name => { // a loaded or referenced class name -> its source file
  let n = name.replace(/\./g, '/').replace(/\$\$Lambda.*$/, '').replace(/\/0x[0-9a-f]+$/, '');
  while (n.startsWith(PREFIX)) {
    if (classes.has(n)) return classes.get(n).node;
    const cut = n.lastIndexOf('$');
    if (cut < 0) return null;
    n = n.slice(0, cut);
  }
  return null;
};

// Test classes and the task that runs each (build.gradle: test, holdGates, columnGates, equipmentGates, fluidSolverRegression).
const gradle = fs.readFileSync(path.join(root, 'build.gradle'), 'utf8');
const slow = new Set([...((gradle.match(/def slowGateClasses = \[([\s\S]*?)\]\.collect/) || [, ''])[1]).matchAll(/'([\w.]+)'/g)]
  .map(m => 'com.wormzjl.icheme.' + m[1]));
if (!slow.size) { console.error('slowGateClasses not found in build.gradle'); process.exit(2); }
const JUNIT = /^Lorg\/junit\/jupiter\/api\/(Test|TestFactory|RepeatedTest|TestTemplate);$|^Lorg\/junit\/jupiter\/params\/ParameterizedTest;$/;
const hasTests = c => { for (let k = c; k; k = classes.get(k.superName)) if (k.utf.some(u => JUNIT.test(u))) return true; return false; };
const taskOf = fqn => {
  const r = fqn.slice('com.wormzjl.icheme.'.length);
  if (r.startsWith('fluid.benchmark.')) return r === 'fluid.benchmark.FluidSolverRegressionTest' ? 'fluidSolverRegression' : null;
  if (r.startsWith('science.column.v3.')) return 'columnGates';
  if (r.startsWith('science.equipment.')) return 'equipmentGates';
  return slow.has(fqn) ? 'holdGates' : 'test';
};
const tests = new Map(); // fqn -> {node, task, nested}
for (const c of classes.values()) {
  if (c.set !== 'test' || (c.access & 0x0600) || !hasTests(c)) continue;
  const outer = c.self.split('$')[0], fqn = outer.replace(/\//g, '.');
  const t = tests.get(fqn) ?? { node: classes.get(outer)?.node ?? c.node, task: taskOf(fqn), nested: false };
  if (c.self.includes('$')) t.nested = true;
  if (t.task) tests.set(fqn, t);
}

// ---- the load map --------------------------------------------------------------------------------------------------
const readMap = () => fs.existsSync(mapFile) ? JSON.parse(zlib.gunzipSync(fs.readFileSync(mapFile)).toString('utf8')) : { version: 1, files: [], tests: {} };
const map = readMap();

if (mode === 'save') {
  const [logs, ...resultDirs] = rest;
  const head = git('rev-parse', 'HEAD').trim();
  if (git('status', '--porcelain', '--untracked-files=no', '--', 'src', 'build.gradle', 'settings.gradle', 'gradle.properties').trim()) {
    console.log('NOTE load sets not saved: src or the build files have uncommitted changes, so HEAD is not what ran'); process.exit(0);
  }
  const passed = new Set();
  for (const d of resultDirs) for (const f of fs.existsSync(d) ? fs.readdirSync(d) : []) {
    const m = f.match(/^TEST-(.+)\.xml$/); if (!m) continue;
    const suite = (fs.readFileSync(path.join(d, f), 'utf8').match(/<testsuite [^>]*>/) || [''])[0];
    if (/ failures="0"/.test(suite) && / errors="0"/.test(suite)) passed.add(m[1].split('$')[0]);
  }
  const sets = new Map();
  for (const f of fs.readdirSync(logs).filter(f => /\.log(\.\d+)?$/.test(f))) {
    const loaded = [...fs.readFileSync(path.join(logs, f), 'utf8').matchAll(/\] (com\.wormzjl\.icheme\.\S+) source:/g)].map(m => m[1]);
    const first = loaded.map(n => n.split('$')[0]).find(n => tests.has(n)); // the class this JVM was given; supertypes load first
    if (!first) continue;
    if (!sets.has(first)) sets.set(first, new Set());
    for (const n of loaded) { const node = nodeOf(n); if (node) sets.get(first).add(node); }
  }
  const index = new Map(map.files.map((f, i) => [f, i]));
  const id = f => { if (!index.has(f)) { index.set(f, map.files.length); map.files.push(f); } return index.get(f); };
  let saved = 0;
  for (const [fqn, files] of sets) {
    if (!passed.has(fqn)) continue;
    const entry = { commit: head, task: tests.get(fqn).task, files: [...files].map(id).sort((a, b) => a - b) };
    map.tests[fqn] = [entry, ...(map.tests[fqn] ?? []).filter(e => e.commit !== head)].slice(0, KEEP);
    saved++;
  }
  fs.mkdirSync(path.dirname(mapFile), { recursive: true });
  fs.writeFileSync(mapFile + '.tmp', zlib.gzipSync(JSON.stringify(map)));
  fs.renameSync(mapFile + '.tmp', mapFile);
  console.log(`NOTE saved ${saved} load sets at ${head.slice(0, 10)} (${sets.size - saved} not passed) to ${mapFile}`);
  process.exit(0);
}
if (mode !== 'select') { console.error('usage: affected.mjs select | save <logs> <results>...'); process.exit(2); }

// ---- what changed since a commit: seeds (changed or touched source files), FULL reasons, GameTest reasons -------------
const readers = fs.readFileSync(new URL('./readers.txt', import.meta.url), 'utf8').replace(/\r/g, '')
  .split('\n').filter(l => l && !l.startsWith('#')).map(l => l.split(/\s+/)).map(([re, node]) => [new RegExp(re), node]);
const texts = new Map();
const sourceText = node => { if (!texts.has(node)) { const f = path.join(root, node); texts.set(node, fs.existsSync(f) ? fs.readFileSync(f, 'utf8') : ''); } return texts.get(node); };
const IGNORED = /^(documentation|research|tools|\.claude|\.github)\/|^[^/]+\.md$|^\.git(ignore|attributes)$|^LICENSE/;
// build.gradle is not a full-run file (owner, 2026-11-10: "changing build.gradle should not trigger a full test"): its
// compileOnly dependencies, test JVM settings and gate lists do not change what a test computes, and the game and loader
// versions live in gradle.properties, which still runs everything beyond mod_version.
const FULL = /^(settings\.gradle|gradlew(\.bat)?|gradle\/.*|(?!build\.gradle$)[^/]+\.gradle(\.kts)?)$|^tools\/development\.gradle$/;
// Compile-time constant candidates of a source file, name -> initializer: static final primitives and Strings, and any
// primitive or String field with an initializer in a file that declares an interface (its fields are static final).
const PRIMITIVE = '(?:int|long|double|float|short|byte|char|boolean|String)';
function constantsOf(text) {
  const t = text.replace(/\/\*[\s\S]*?\*\//g, ' ').replace(/\/\/[^\n]*/g, ' ');
  const found = new Map();
  for (const m of t.matchAll(new RegExp(`\\b(?:static\\s+final|final\\s+static)\\s+${PRIMITIVE}\\s+(\\w+)\\s*=\\s*([^;]+);`, 'g'))) found.set(m[1], m[2].replace(/\s+/g, ' ').trim());
  if (/\binterface\s+\w+/.test(t)) for (const m of t.matchAll(new RegExp(`^\\s*(?:public\\s+)?${PRIMITIVE}\\s+(\\w+)\\s*=\\s*([^;]+);`, 'gm'))) found.set(m[1], m[2].replace(/\s+/g, ' ').trim());
  return found;
}
const changedConstants = (before, after) => {
  const a = constantsOf(before), b = constantsOf(after);
  return [...new Set([...a.keys(), ...b.keys()])].filter(n => a.get(n) !== b.get(n));
};
const show = (commit, file) => { try { return execFileSync('git', ['show', `${commit}:${file}`], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'], maxBuffer: 1 << 26 }); } catch { return ''; } };
const constants = [...classes.values()].map(c => [c.node, c.strings]);

// A string constant names a path when it is a segment-aligned prefix of the path, or of a tail of it; a constant that
// names a file names its folder too (siblings are often read through it).
function readersOf(file) {
  const tails = new Set();
  for (const f of [file, file.replace(/^src\/[^/]+\/resources\//, '')]) { const s = f.split('/'); for (let i = 0; i < s.length; i++) tails.add(s.slice(i).join('/')); }
  const base = file.slice(file.lastIndexOf('/') + 1);
  const names = s => {
    const n = s.replace(/\\/g, '/').replace(/^\.?\//, '');
    if (n.length < 3 || !/[A-Za-z]/.test(n) || /\s/.test(n)) return [];
    if (!n.includes('/')) return n === base ? [n] : [];
    return /\.[A-Za-z0-9]+$/.test(n.slice(n.lastIndexOf('/') + 1)) ? [n, n.slice(0, n.lastIndexOf('/') + 1)] : [n];
  };
  const hits = new Set();
  for (const [node, strings] of constants) for (const s of strings) for (const n of names(s)) {
    if ([...tails].some(t => n.endsWith('/') ? t.startsWith(n) : t === n || t.startsWith(n + '/'))) { hits.add(node); break; }
  }
  const pkg = file.match(/^src\/[^/]+\/resources\/(com\/wormzjl\/icheme\/.*)\/[^/]+$/);
  if (pkg) for (const c of classes.values()) if (c.self.startsWith(pkg[1] + '/') && !c.self.slice(pkg[1].length + 1).includes('/')) hits.add(c.node);
  for (const [re, node] of readers) if (re.test(file)) hits.add(node);
  return hits;
}

const sinceCache = new Map();
function since(commit) {
  if (sinceCache.has(commit)) return sinceCache.get(commit);
  const r = { seeds: new Map(), full: [], notes: [], gametest: null, files: 0 };
  const seed = (node, why) => { if (!r.seeds.has(node)) r.seeds.set(node, why); };
  const pending = []; // [constant name, holder file] whose value changed
  // Against the working tree, as compiled: uncommitted edits and new files count too.
  const changed = range ? git('diff', '--name-only', '--no-renames', ...range).split('\n').filter(Boolean)
    : [...new Set([...git('diff', '--name-only', '--no-renames', commit).split('\n'),
      ...git('ls-files', '--others', '--exclude-standard', 'src').split('\n')].filter(Boolean))];
  r.files = changed.length;
  for (const f of changed) {
    if (IGNORED.test(f)) continue;
    if (FULL.test(f)) { r.full.push(`${f} changed`); continue; }
    if (f === 'build.gradle') { r.notes.push('build.gradle changed: no test selected for it (owner, 2026-11-10)'); continue; }
    if (f === 'gradle.properties') {
      const lines = git('diff', '-U0', ...(range ?? [commit]), '--', f).split('\n').filter(l => /^[+-][^+-]/.test(l));
      if (lines.some(l => !/^[+-]\s*mod_version\s*=/.test(l))) r.full.push('gradle.properties changed beyond mod_version');
      continue;
    }
    if (/^src\/(main\/resources\/(data|META-INF)|main\/templates|fluidGameTest)\//.test(f)) r.gametest ??= `${f} changed`;
    const java = f.match(/^src\/([^/]+)\/java\/.*\.java$/);
    if (java) {
      if (!SETS.includes(java[1])) { r.notes.push(`${f}: source set ${java[1]} has no tests`); continue; }
      seed(f, 'changed');
      if (java[1] === 'fluidGameTest' || (java[1] === 'main' && !f.includes('/client/'))) r.gametest ??= `${f} changed`;
      const before = show(commit, f), after = range ? show(range[1], f) : sourceText(f);
      for (const name of changedConstants(before, after)) pending.push([name, f]);
      continue;
    }
    const hits = readersOf(f);
    for (const h of hits) seed(h, `reads ${f}`);
    if (!hits.size) {
      if (/^src\/(test|fluidGameTest)\//.test(f)) r.full.push(`${f}: a test resource that no class names`);
      else r.notes.push(`${f}: no class names it`);
    }
  }
  // javac copies a compile-time constant into the classes that use it, which then never load its holder: a file that
  // names a constant whose value changed changed too, and so do its own constants that are built from it.
  const done = new Set();
  while (pending.length) {
    const [name, holder] = pending.pop();
    if (done.has(name)) continue;
    done.add(name);
    // A user names the holder (or a type declared in its file): Holder.NAME, a static import, or extends/implements it.
    const types = [...new Set([...sourceText(holder).matchAll(/\b(?:class|interface|enum|record)\s+(\w+)/g)].map(m => m[1]))];
    if (!types.length) types.push(holder.slice(holder.lastIndexOf('/') + 1, -5));
    const t = `(?:${types.join('|')})`;
    const word = new RegExp(`\\b${name}\\b`);
    const use = new RegExp(`\\b${t}\\s*\\.\\s*(?:${name}\\b|\\*)|\\b(?:extends|implements)\\s+[\\w.,\\s<>]*\\b${t}\\b`);
    for (const c of new Set([...classes.values()].map(c => c.node))) {
      if (c === holder || !use.test(sourceText(c)) || !word.test(sourceText(c))) continue;
      seed(c, `uses ${holder.slice(holder.lastIndexOf('/') + 1, -5)}.${name}, a changed constant`);
      for (const [n, init] of constantsOf(sourceText(c))) if (word.test(init)) pending.push([n, c]);
    }
  }
  sinceCache.set(commit, r);
  return r;
}

// ---- selection -------------------------------------------------------------------------------------------------------
const opt = name => { const i = rest.indexOf(name); return i >= 0 ? rest[i + 1] : null; };
const replay = opt('--since');
range = replay?.includes('..') ? replay.split('..') : null;
const base = replay ? git('rev-parse', range ? range[0] : replay).trim() : git('merge-base', 'HEAD', 'main').trim();
const fromBase = since(base);
for (const f of fromBase.full) emit(`FULL ${f}`);
for (const n of fromBase.notes) emit(`NOTE ${n}`);
const ancestor = new Map();
const isAncestor = c => { if (!ancestor.has(c)) ancestor.set(c, gitOk('merge-base', '--is-ancestor', c, 'HEAD')); return ancestor.get(c); };
const explainName = opt('--explain');
const chosen = new Map([...new Set([...tests.values()].map(t => t.task))].map(k => [k, []]));
const total = new Map([...chosen.keys()].map(k => [k, 0]));
const why = new Map();
let unrecorded = 0;
for (const [fqn, t] of tests) {
  total.set(t.task, total.get(t.task) + 1);
  const found = (map.tests[fqn] ?? []).find(e => isAncestor(e.commit));
  const entry = found && replay ? { ...found, commit: base } : found;
  let reason = null;
  if (!entry) { reason = 'no load set recorded on this line of commits'; unrecorded++; }
  else {
    const s = since(entry.commit);
    if (s.full.length) reason = `since ${entry.commit.slice(0, 10)}: ${s.full[0]}`;
    else if (s.seeds.has(t.node)) reason = `its source: ${s.seeds.get(t.node)}`;
    else for (const i of entry.files) { const f = map.files[i]; if (s.seeds.has(f)) { reason = `loads ${f}: ${s.seeds.get(f)}`; break; } }
  }
  if (reason) { chosen.get(t.task).push(fqn); why.set(fqn, reason); }
}
if (explainName) {
  const fqn = [...tests.keys()].find(k => k === explainName || k.endsWith('.' + explainName));
  console.error(fqn ? `${fqn}: ${why.get(fqn) ?? 'not affected'}` : `${explainName}: no such test class`);
}

for (const task of ['test', 'holdGates', 'columnGates', 'equipmentGates', 'fluidSolverRegression']) {
  const picked = chosen.get(task) ?? [], all = [...tests].filter(([, t]) => t.task === task).map(([k]) => k);
  if (!picked.length) { emit(`TASK ${task} NONE 0/${all.length}`); continue; }
  if (picked.length === all.length) { emit(`TASK ${task} ALL ${all.length}/${all.length}`); continue; }
  // A package whose tests in the task are all picked collapses to pkg.*; a test class with nested classes adds Name$*.
  const set = new Set(picked), filters = new Set();
  for (const fqn of [...set].sort()) {
    const seg = fqn.split('.');
    let best = null;
    for (let i = seg.length - 1; i >= 4; i--) {
      const pkg = seg.slice(0, i).join('.') + '.';
      if (all.filter(k => k.startsWith(pkg)).every(k => set.has(k))) best = pkg + '*'; else break;
    }
    if (best) filters.add(best); else { filters.add(fqn); if (tests.get(fqn).nested) filters.add(fqn + '$*'); }
  }
  const reduced = [...filters].filter(f => ![...filters].some(g => g !== f && g.endsWith('.*') && f.startsWith(g.slice(0, -1))));
  emit(`TASK ${task} ${picked.length}/${all.length} ${reduced.join(' ')}`);
}
const regression = (chosen.get('fluidSolverRegression') ?? []).length;
emit(`STEP regression ${regression ? 'due' : 'skip'} ${regression ? why.get('com.wormzjl.icheme.fluid.benchmark.FluidSolverRegressionTest') : 'FluidSolverRegressionTest loads nothing that changed'}`);
const gametest = fromBase.full[0] ?? fromBase.gametest;
emit(`STEP gametest ${gametest ? 'due' : 'skip'} ${gametest ?? 'only client code, tests or game assets changed since main'}`);
emit(`NOTE since main (${base.slice(0, 10)}): ${fromBase.files} files changed; ${unrecorded} test classes have no load set; map ${path.relative(root, mapFile).startsWith('..') ? mapFile : path.relative(root, mapFile)}`);
for (const [fqn, r] of why) if (r.startsWith('no load set') === false) emit(`WHY ${fqn.slice(19)} ${r}`);
console.log(out.join('\n'));
