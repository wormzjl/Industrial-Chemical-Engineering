// The tests that a branch's changes can reach, from the classes each test class loaded when it last passed
// (README.md of this folder). Usage, from the worktree root, after testClasses and compileFluidGameTestJava:
//   node tools/merge-lane/affected.mjs select [--explain <test class>] [--since <commit>|<A>..<B>]
//   node tools/merge-lane/affected.mjs save <load-log folder> <results folder>...
//   node tools/merge-lane/affected.mjs stamp <gametest|campaign> [<output folder>]
// select prints: FULL <reason> | TASK <task> NONE|<n>/<total> | PICK <task> [!]<test filter>
// | STEP <regression|gametest|campaign> due|skip <why> | NOTE <text> | WHY <class> <reason>.
//   PICK lines are the filters of each test task: one per class to run, and !<class>.<method> for each of its tests
//   that the lane leaves out (left-out.txt). lane.sh hands them to Gradle in a file (record.init.gradle, -PlaneTests).
// save adds the load sets of the passed test classes, recorded at HEAD, to the load map.
// stamp records that a step ran at HEAD: the next select asks what changed since then, not since main.
// --since replays a past batch on today's load sets: every load set counts as recorded at <commit>, the batch's
// base; <A>..<B> takes the changes between two commits instead of those up to the working tree.
// The load map is $LANE_MAP, or tools/merge-lane/logs/load-map.json.gz in the main checkout (git-ignored, shared);
// steps.json beside it holds the stamps.
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
const stepsFile = path.join(path.dirname(mapFile), 'steps.json');
const SETS = ['main', 'test', 'fluidGameTest'];
const PREFIX = 'com/wormzjl/icheme/';
const KEEP = 3; // load sets kept per test class, newest first: one per recent commit line
const STEPS_KEPT = 8; // stamps kept per step
const out = [];
let range = null; // --since A..B
const emit = line => out.push(line);

// ---- class files: source file of each class, string constants, static fields, test classes --------------------------
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
  // The static fields by name, and whether javac gave each a ConstantValue: a compile-time constant, copied into its users.
  const fields = [];
  for (let m = u2(); m > 0; m--) {
    const flags = u2(), name = utf[u2()]; p += 2;
    let constant = false;
    for (let a = u2(); a > 0; a--) { if (utf[u2()] === 'ConstantValue') constant = true; const len = u4(); p += len; }
    if (flags & 0x0008) fields.push({ name, constant });
  }
  for (let m = u2(); m > 0; m--) { p += 6; for (let a = u2(); a > 0; a--) { p += 2; const len = u4(); p += len; } }
  let sourceFile = null;
  for (let a = u2(); a > 0; a--) {
    const name = utf[u2()], len = u4();
    if (name === 'SourceFile') sourceFile = utf[buf.readUInt16BE(p)];
    p += len;
  }
  return { self, superName, access, sourceFile, fields, utf: utf.filter(Boolean), strings: strings.map(i => utf[i]).filter(s => s != null) };
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

// Test classes and the task that runs each (build.gradle: test, holdGates, columnGates, equipmentGates,
// fluidSolverRegression).
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

// ---- the load map and the steps' stamps ------------------------------------------------------------------------------
const readMap = () => fs.existsSync(mapFile) ? JSON.parse(zlib.gunzipSync(fs.readFileSync(mapFile)).toString('utf8')) : { version: 1, files: [], tests: {} };
const map = readMap();
const readSteps = () => fs.existsSync(stepsFile) ? JSON.parse(fs.readFileSync(stepsFile, 'utf8')) : {};
const dirty = (...more) => git('status', '--porcelain', '--untracked-files=no', '--', 'src', 'build.gradle', 'settings.gradle', 'gradle.properties', ...more).trim();

if (mode === 'save') {
  const [logs, ...resultDirs] = rest;
  const head = git('rev-parse', 'HEAD').trim();
  if (dirty()) { console.log('NOTE load sets not saved: src or the build files have uncommitted changes, so HEAD is not what ran'); process.exit(0); }
  // A class passed when every result file of it passed (its nested classes have their own).
  const seen = new Set(), failed = new Set();
  for (const d of resultDirs) for (const f of fs.existsSync(d) ? fs.readdirSync(d) : []) {
    const m = f.match(/^TEST-(.+)\.xml$/); if (!m) continue;
    const suite = (fs.readFileSync(path.join(d, f), 'utf8').match(/<testsuite [^>]*>/) || [''])[0];
    const fqn = m[1].split('$')[0];
    seen.add(fqn);
    if (!/ failures="0"/.test(suite) || !/ errors="0"/.test(suite)) failed.add(fqn);
  }
  // Each JVM's loads go to every test class of this run that it loaded: the test classes share JVMs
  // (record.init.gradle, -PlaneGroup), and a larger load set is a safe one.
  const sets = new Map(), inits = new Map(), initLogged = new Set();
  for (const f of fs.readdirSync(logs).filter(f => /\.log(\.\d+)?$/.test(f))) {
    const text = fs.readFileSync(path.join(logs, f), 'utf8');
    const loaded = [...text.matchAll(/\] (com\.wormzjl\.icheme\.\S+) source:/g)].map(m => m[1]);
    const started = [...text.matchAll(/Initializing '?(com\/wormzjl\/icheme\/[^'\s(]+)/g)].map(m => m[1]);
    const ran = new Set(loaded.map(n => n.split('$')[0]).filter(n => tests.has(n) && seen.has(n)));
    for (const fqn of ran) {
      if (!sets.has(fqn)) { sets.set(fqn, new Set()); inits.set(fqn, new Set()); }
      for (const n of loaded) { const node = nodeOf(n); if (node) sets.get(fqn).add(node); }
      for (const n of started) { const node = nodeOf(n); if (node) inits.get(fqn).add(node); }
      if (started.length) initLogged.add(fqn);
    }
  }
  const index = new Map(map.files.map((f, i) => [f, i]));
  const id = f => { if (!index.has(f)) { index.set(f, map.files.length); map.files.push(f); } return index.get(f); };
  let saved = 0;
  for (const [fqn, files] of sets) {
    if (failed.has(fqn)) continue;
    const entry = { commit: head, task: tests.get(fqn).task, files: [...files].map(id).sort((a, b) => a - b) };
    // The files with a class this JVM initialized (-Xlog:class+init): a file only loaded ran no code of its own.
    if (initLogged.has(fqn)) entry.init = [...inits.get(fqn)].map(id).sort((a, b) => a - b);
    map.tests[fqn] = [entry, ...(map.tests[fqn] ?? []).filter(e => e.commit !== head)].slice(0, KEEP);
    saved++;
  }
  fs.mkdirSync(path.dirname(mapFile), { recursive: true });
  fs.writeFileSync(mapFile + '.tmp', zlib.gzipSync(JSON.stringify(map)));
  fs.renameSync(mapFile + '.tmp', mapFile);
  console.log(`NOTE saved ${saved} load sets at ${head.slice(0, 10)} (${sets.size - saved} not passed) to ${mapFile}`);
  process.exit(0);
}
if (mode === 'stamp') {
  const [step, folder] = rest;
  if (!['gametest', 'campaign'].includes(step)) { console.error('usage: affected.mjs stamp <gametest|campaign> [<output folder>]'); process.exit(2); }
  if (dirty('tools/merge-lane/campaign-seeds.txt')) { console.log(`NOTE ${step} not stamped: uncommitted changes, so HEAD is not what ran`); process.exit(0); }
  const head = git('rev-parse', 'HEAD').trim(), steps = readSteps();
  steps[step] = [{ commit: head, at: new Date().toISOString().slice(0, 10), out: folder ? path.resolve(folder) : null },
    ...(steps[step] ?? []).filter(e => e.commit !== head)].slice(0, STEPS_KEPT);
  fs.mkdirSync(path.dirname(stepsFile), { recursive: true });
  fs.writeFileSync(stepsFile + '.tmp', JSON.stringify(steps, null, 1));
  fs.renameSync(stepsFile + '.tmp', stepsFile);
  console.log(`NOTE ${step} stamped at ${head.slice(0, 10)} in ${stepsFile}`);
  process.exit(0);
}
if (mode !== 'select') { console.error('usage: affected.mjs select | save <logs> <results>... | stamp <step> [<folder>]'); process.exit(2); }

// ---- what changed since a commit: seeds (changed or touched source files), FULL reasons, step reasons -----------------
const lines = name => fs.readFileSync(new URL(name, import.meta.url), 'utf8').replace(/\r/g, '').split('\n').filter(l => l && !l.startsWith('#'));
const readers = lines('./readers.txt').map(l => l.split(/\s+/)).map(([re, node]) => [new RegExp(re), node]);
const campaignInputs = lines('./campaign-inputs.txt').map(l => new RegExp(l));
const texts = new Map();
const sourceText = node => { if (!texts.has(node)) { const f = path.join(root, node); texts.set(node, fs.existsSync(f) ? fs.readFileSync(f, 'utf8') : ''); } return texts.get(node); };
const IGNORED = /^(documentation|research|tools|\.claude|\.github)\/|^[^/]+\.md$|^\.git(ignore|attributes)$|^LICENSE/;
// build.gradle is not a full-run file (owner, 2026-11-10: "changing build.gradle should not trigger a full test"): its
// compileOnly dependencies, test JVM settings and gate lists do not change what a test computes, and the game and loader
// versions live in gradle.properties. gradle.properties is not a full-run file either (owner, 2026-11-16:
// "gradle.properties change should not trigger full test"); a game or loader version change is run with --all.
const FULL = /^(settings\.gradle|gradlew(\.bat)?|gradle\/.*|(?!build\.gradle$)[^/]+\.gradle(\.kts)?)$|^tools\/development\.gradle$/;
// Compile-time constants. javac marks each with a ConstantValue in its class file, so the constants of a source file as
// compiled now are exact: constantTypes gives, per file, each constant's name and the simple names of the types that
// declare it. A file's earlier text has no class file: there a constant is a static final primitive or String with an
// initializer, or, in a file that declares an interface, a field that is still a static field now. (Until 2026-11-16
// the second rule took any initialized local of such a file for a constant: 125 test classes of one lane ran for it.)
const PRIMITIVE = '(?:int|long|double|float|short|byte|char|boolean|String)';
const constantTypes = new Map(), staticFields = new Map(); // source file -> Map(constant -> Set(type)) | Set(field)
for (const c of classes.values()) for (const f of c.fields) {
  if (!staticFields.has(c.node)) { staticFields.set(c.node, new Set()); constantTypes.set(c.node, new Map()); }
  staticFields.get(c.node).add(f.name);
  if (!f.constant) continue;
  const types = constantTypes.get(c.node);
  if (!types.has(f.name)) types.set(f.name, new Set());
  types.get(f.name).add(c.self.slice(Math.max(c.self.lastIndexOf('/'), c.self.lastIndexOf('$')) + 1));
}
const code = text => text.replace(/\/\*[\s\S]*?\*\//g, ' ').replace(/\/\/[^\n]*/g, ' ');
// Every initializer or assignment of a name in a text, through its semicolon: what the constant's value is written as.
const written = (text, name) => [...code(text).matchAll(new RegExp(`\\b${name}\\s*=(?!=)([^;]*);`, 'g'))].map(m => m[1].replace(/\s+/g, ' ').trim()).join(' | ');
function earlierConstants(text, node) {
  const t = code(text), found = new Set();
  for (const m of t.matchAll(new RegExp(`\\b(?:static\\s+final|final\\s+static)\\s+${PRIMITIVE}\\s+(\\w+)\\s*=`, 'g'))) found.add(m[1]);
  if (/\binterface\s+\w+/.test(t)) for (const m of t.matchAll(new RegExp(`^\\s*(?:public\\s+)?${PRIMITIVE}\\s+(\\w+)\\s*=`, 'gm'))) if (staticFields.get(node)?.has(m[1])) found.add(m[1]);
  return found;
}
// The constants of a file whose value is written differently than before, each with the types a user names it by: its
// declaring types as compiled now, or every type of the file for a constant that is no constant any more.
function changedConstants(node, before, after) {
  const now = constantTypes.get(node) ?? new Map(), found = [];
  for (const name of new Set([...now.keys(), ...earlierConstants(before, node)])) {
    if (written(before, name) === written(after, name)) continue;
    const types = now.get(name) ?? new Set([...code(after + '\n' + before).matchAll(/\b(?:class|interface|enum|record)\s+(\w+)/g)].map(m => m[1]));
    found.push([name, [...types]]);
  }
  return found;
}
// The texts of a commit's files, in one git process: a process for each file was most of the selection's work on
// Windows (about a thousand of them on a lane whose load sets were recorded at several commits). A file the commit
// does not have is the empty text.
function showAll(commit, files) {
  const texts = new Map();
  if (!files.length) return texts;
  const buf = execFileSync('git', ['cat-file', '--batch'], { input: files.map(f => `${commit}:${f}\n`).join(''), stdio: ['pipe', 'pipe', 'ignore'], maxBuffer: 1 << 29 });
  let at = 0;
  for (const f of files) {
    const end = buf.indexOf(10, at), head = buf.toString('utf8', at, end).match(/ (\w+) (\d+)$/);
    at = end + 1;
    if (!head) { texts.set(f, ''); continue; } // "<name> missing"
    texts.set(f, head[1] === 'blob' ? buf.toString('utf8', at, at + +head[2]) : '');
    at += +head[2] + 1;
  }
  return texts;
}
const constants = [...classes.values()].map(c => [c.node, c.strings]);
const nodes = [...new Set([...classes.values()].map(c => c.node))];

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
  const r = { seeds: new Map(), full: [], notes: [], gametest: null, campaign: null, files: 0 };
  const seed = (node, why) => { if (!r.seeds.has(node)) r.seeds.set(node, why); };
  const pending = []; // [constant name, holder file, the types that declare it] whose value changed
  // Against the working tree, as compiled: uncommitted edits and new files count too.
  const changed = range ? git('diff', '--name-only', '--no-renames', ...range).split('\n').filter(Boolean)
    : [...new Set([...git('diff', '--name-only', '--no-renames', commit).split('\n'),
      ...git('ls-files', '--others', '--exclude-standard', 'src').split('\n')].filter(Boolean))];
  r.files = changed.length;
  const sources = changed.filter(f => { const m = f.match(/^src\/([^/]+)\/java\/.*\.java$/); return m && SETS.includes(m[1]); });
  const earlier = showAll(commit, sources), later = range ? showAll(range[1], sources) : null;
  for (const f of changed) {
    if (campaignInputs.some(re => re.test(f))) r.campaign ??= `${f} changed`;
    if (IGNORED.test(f)) continue;
    if (FULL.test(f)) { r.full.push(`${f} changed`); continue; }
    if (f === 'build.gradle') { r.notes.push('build.gradle changed: no test selected for it (owner, 2026-11-10)'); continue; }
    if (f === 'gradle.properties') {
      const diff = git('diff', '-U0', ...(range ?? [commit]), '--', f).split('\n').filter(l => /^[+-][^+-]/.test(l));
      if (diff.some(l => !/^[+-]\s*mod_version\s*=/.test(l))) r.notes.push('gradle.properties changed beyond mod_version: no test selected for it (owner, 2026-11-16)');
      continue;
    }
    if (/^src\/(main\/resources\/(data|META-INF)|main\/templates|fluidGameTest)\//.test(f)) r.gametest ??= `${f} changed`;
    const java = f.match(/^src\/([^/]+)\/java\/.*\.java$/);
    if (java) {
      if (!SETS.includes(java[1])) { r.notes.push(`${f}: source set ${java[1]} has no tests`); continue; }
      seed(f, 'changed');
      // This gate walks Java sources on disk, so its loaded classes omit what it checks.
      if (/^src\/(?:main\/java\/com\/wormzjl\/icheme\/(?:science|runtime)|test\/java\/com\/wormzjl\/icheme\/fluid\/benchmark)\//.test(f)) {
        seed('src/test/java/com/wormzjl/icheme/science/fluid/DeterministicMathSourceTest.java', `scans ${f}`);
      }
      if (java[1] === 'fluidGameTest' || (java[1] === 'main' && !f.includes('/client/'))) r.gametest ??= `${f} changed`;
      const before = earlier.get(f), after = later ? later.get(f) : sourceText(f);
      for (const [name, types] of changedConstants(f, before, after)) pending.push([name, f, types]);
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
    const [name, holder, types] = pending.pop();
    if (done.has(holder + '#' + name)) continue;
    done.add(holder + '#' + name);
    // A user names the constant's declaring type: Type.NAME, a static import, or extends/implements it.
    if (!types.length) types.push(holder.slice(holder.lastIndexOf('/') + 1, -5));
    const t = `(?:${types.join('|')})`;
    const word = new RegExp(`\\b${name}\\b`);
    const use = new RegExp(`\\b${t}\\s*\\.\\s*(?:${name}\\b|\\*)|\\b(?:extends|implements)\\s+[\\w.,\\s<>]*\\b${t}\\b`);
    for (const c of nodes) {
      if (c === holder || !word.test(sourceText(c)) || !use.test(sourceText(c))) continue;
      seed(c, `uses ${holder.slice(holder.lastIndexOf('/') + 1, -5)}.${name}, a changed constant`);
      for (const [n, own] of constantTypes.get(c) ?? []) if (word.test(written(sourceText(c), n))) pending.push([n, c, [...own]]);
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
const why = new Map();
let unrecorded = 0, onlyLoaded = 0;
for (const [fqn, t] of tests) {
  const found = (map.tests[fqn] ?? []).find(e => isAncestor(e.commit));
  const entry = found && replay ? { ...found, commit: base } : found;
  let reason = null;
  if (!entry) { reason = 'no load set recorded on this line of commits'; unrecorded++; }
  else {
    const s = since(entry.commit);
    if (s.full.length) reason = `since ${entry.commit.slice(0, 10)}: ${s.full[0]}`;
    else if (s.seeds.has(t.node)) reason = `its source: ${s.seeds.get(t.node)}`;
    else {
      const hits = entry.files.map(i => map.files[i]).filter(f => s.seeds.has(f));
      if (hits.length) {
        reason = `loads ${hits[0]}: ${s.seeds.get(hits[0])}`;
        // Counted, not used: the class runs. A file that was loaded and never initialized ran no code in this test.
        if (entry.init) { const ran = new Set(entry.init.map(i => map.files[i])); if (!hits.some(f => ran.has(f))) { onlyLoaded++; reason += ' (loaded, never initialized)'; } }
      }
    }
  }
  if (reason) { chosen.get(t.task).push(fqn); why.set(fqn, reason); }
}
if (explainName) {
  const fqn = [...tests.keys()].find(k => k === explainName || k.endsWith('.' + explainName));
  console.error(fqn ? `${fqn}: ${why.get(fqn) ?? 'not affected'}` : `${explainName}: no such test class`);
}

// The tests the lane leaves out (left-out.txt, owner 2026-11-16): by test class, as Gradle filters.
const leftOut = new Map();
for (const filter of lines('./left-out.txt').map(l => l.split(/\s+/)[0])) {
  const fqn = filter.slice(0, filter.lastIndexOf('.')).split('$')[0];
  if (!leftOut.has(fqn)) leftOut.set(fqn, []);
  leftOut.get(fqn).push(filter);
}
let left = 0;
for (const task of ['test', 'holdGates', 'columnGates', 'equipmentGates', 'fluidSolverRegression']) {
  const picked = (chosen.get(task) ?? []).sort(), all = [...tests].filter(([, t]) => t.task === task).length;
  if (!picked.length) { emit(`TASK ${task} NONE 0/${all}`); continue; }
  emit(`TASK ${task} ${picked.length}/${all}`);
  if (task === 'fluidSolverRegression') continue;
  // One filter per class, Name$* for a class with nested test classes, and !<filter> for each of its tests left out.
  for (const fqn of picked) {
    emit(`PICK ${task} ${fqn}`);
    if (tests.get(fqn).nested) emit(`PICK ${task} ${fqn}$*`);
    for (const filter of leftOut.get(fqn) ?? []) { emit(`PICK ${task} !${filter}`); left++; }
  }
}
const regression = (chosen.get('fluidSolverRegression') ?? []).length;
emit(`STEP regression ${regression ? 'due' : 'skip'} ${regression ? why.get('com.wormzjl.icheme.fluid.benchmark.FluidSolverRegressionTest') : 'FluidSolverRegressionTest loads nothing that changed'}`);
// The GameTests and the campaign run when something they load changed since their last run on this line of commits
// (stamp), or since main when they have none: a second lane of a batch does not repeat a step its changes cannot reach.
const steps = replay ? {} : readSteps();
function stepDue(step, reach) {
  const marks = [...(steps[step] ?? []).filter(e => isAncestor(e.commit)).map(e => ({ commit: e.commit, label: `its last run (${e.commit.slice(0, 10)}, ${e.at})`, out: e.out })),
    { commit: base, label: `main (${base.slice(0, 10)})` }];
  let first = null;
  for (const m of marks) {
    const reason = reach(since(m.commit));
    if (!reason) return { due: false, text: `nothing it loads changed since ${m.label}`, out: m.out };
    first ??= `${reason} since ${m.label}`;
  }
  return { due: true, text: first, out: marks[0].out };
}
const gametest = stepDue('gametest', s => s.full[0] ?? s.gametest);
emit(`STEP gametest ${gametest.due ? 'due' : 'skip'} ${gametest.text}`);
const campaign = stepDue('campaign', s => s.campaign);
emit(`STEP campaign ${campaign.due ? 'due' : 'skip'} ${campaign.text}`);
if (campaign.out) emit(`NOTE the campaign's last reports on this line of commits: ${campaign.out}`);
if (onlyLoaded) emit(`NOTE ${onlyLoaded} selected test classes only loaded what changed and initialized none of it (they run; README, "Loaded and never initialized")`);
emit(`NOTE since main (${base.slice(0, 10)}): ${fromBase.files} files changed; ${unrecorded} test classes have no load set; ${left} tests left out (left-out.txt); map ${path.relative(root, mapFile).startsWith('..') ? mapFile : path.relative(root, mapFile)}`);
for (const [fqn, r] of why) if (r.startsWith('no load set') === false) emit(`WHY ${fqn.slice(19)} ${r}`);
console.log(out.join('\n'));
