#!/usr/bin/env node
// Code map of src/main/java/com/wormzjl/icheme: files and lines per package, the import matrix between the
// top-level layers, and the count columns of the code guide's module table. No dependencies. Run from the
// repository root:
//
//   node tools/code-guide/map.mjs            prints the package table and the layer matrix
//   node tools/code-guide/map.mjs --readme   rewrites the file counts in documentation/reference/code-guide/README.md
//   node tools/code-guide/map.mjs --check    exits 1 if --readme would change the README
//
// README mapping (the module table of "Level 2 — Code map" has no count column; its counts are the
// parenthesised numbers after a package path in the first cell). The script rewrites a number only in these forms
// and leaves every other character as it is:
//
//   `p/**` (N)              N = Java files in p and every package below it
//   `p/**` (N, ...)          the same; the text after the comma is kept
//   `p/**` (N; `s/` M ...)   N as above; M = files in p/s and below
//   `p/**` (N + `s/` M ...)  N = files directly in p; M = files in p/s and below
//   `p/` (N)                 N = files directly in p
//   `p/**` (a N, b M ...)    each named count = files in p/a and below
//
// Paths are relative to com/wormzjl/icheme. A row whose counts follow none of these forms is left alone and
// printed as "unmapped": today the `client/**` row (89 "besides" two sub-trees; it is not clear which of the other
// client sub-trees the 89 left out). The README holds file counts only; lines per module are printed, not written.
// The Level 2 sentence with the totals and their commit ("861 Java files at ...") is not a table cell and is left
// alone.
//
// Layering: FORBIDDEN lists the top-level rules the layering of CODEBASE_REVIEW section 3.1 wants. The rules that
// hold today are pinned in src/test/java/com/wormzjl/icheme/LayeringTest.java; keep both lists in step. The
// matrix counts import statements; "qualified" counts code lines (comments and strings stripped) that name a type
// of another layer by its full name without an import.

import fs from 'node:fs';
import path from 'node:path';

const ROOT = 'src/main/java/com/wormzjl/icheme';
const README = 'documentation/reference/code-guide/README.md';
const ROOT_LAYER = '(root)';

// [from, to, reason]; a rule forbids every reference from `from` to `to`.
export const FORBIDDEN = [
  ['science', 'runtime', 'science is Minecraft-free and below the engine'],
  ['science', 'world', 'science is Minecraft-free'],
  ['science', 'network', 'science is Minecraft-free'],
  ['science', 'client', 'science is Minecraft-free'],
  ['science', 'registry', 'science is Minecraft-free'],
  ['science', 'compat', 'science is Minecraft-free'],
  ['science', 'mixin', 'science is Minecraft-free'],
  ['science', ROOT_LAYER, 'science is Minecraft-free'],
  ['material', 'runtime', 'the catalog loader sits below the engine'],
  ['material', 'world', 'the catalog loader sits below the world'],
  ['material', 'network', 'the catalog loader sits below the wire'],
  ['material', 'client', 'the catalog loader is common code'],
  ['runtime', 'client', 'the engine runs on the server'],
  ['runtime', 'network', 'the engine publishes; the wire reads it (P3-26)'],
  ['runtime', 'world', 'the engine sits below the world adapters'],
  ['network', 'client', 'packets are common code; client hooks go through an interface'],
  ['world', 'client', 'block entities are common code; client hooks go through an interface (E17)'],
  ['registry', 'client', 'registration is common code'],
  ['compat', 'client', 'integrations are common code unless client-only'],
];

function walk(dir, out = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p, out);
    else if (e.name.endsWith('.java')) out.push(p.split(path.sep).join('/'));
  }
  return out;
}

/** The code of a Java source with comments, strings, text blocks and char literals blanked (newlines kept). */
export function stripJava(src) {
  let out = '';
  let i = 0;
  const n = src.length;
  while (i < n) {
    const c = src[i], d = src[i + 1];
    if (c === '/' && d === '/') { while (i < n && src[i] !== '\n') i++; continue; }
    if (c === '/' && d === '*') {
      i += 2;
      while (i < n && !(src[i] === '*' && src[i + 1] === '/')) { if (src[i] === '\n') out += '\n'; i++; }
      i += 2; continue;
    }
    if (c === '"' && d === '"' && src[i + 2] === '"') {
      i += 3;
      while (i < n && !(src[i] === '"' && src[i + 1] === '"' && src[i + 2] === '"')) {
        if (src[i] === '\\') i++;
        else if (src[i] === '\n') out += '\n';
        i++;
      }
      i += 3; out += '""'; continue;
    }
    if (c === '"' || c === "'") {
      i++;
      while (i < n && src[i] !== c && src[i] !== '\n') { if (src[i] === '\\') i++; i++; }
      i++; out += c + c; continue;
    }
    out += c; i++;
  }
  return out;
}

function layerOf(rel) {
  const parts = rel.split('/');
  return parts.length === 1 ? ROOT_LAYER : parts[0];
}

/** Every reference of a file to another icheme package: {line, layer, text, kind}. */
export function references(src) {
  const code = stripJava(src);
  const lines = code.split('\n');
  const refs = [];
  const target = /com\.wormzjl\.icheme\.([A-Za-z_]\w*)/g;
  lines.forEach((line, k) => {
    const imp = /^\s*import\s+(?:static\s+)?com\.wormzjl\.icheme\.([A-Za-z_]\w*)[\w.*]*\s*;/.exec(line);
    if (imp) { refs.push({ line: k + 1, layer: layerName(imp[1]), text: line.trim(), kind: 'import' }); return; }
    if (/^\s*package\s/.test(line)) return;
    let m;
    target.lastIndex = 0;
    const seen = new Set();
    while ((m = target.exec(line))) {
      const layer = layerName(m[1]);
      if (seen.has(layer)) continue;
      seen.add(layer);
      refs.push({ line: k + 1, layer, text: line.trim(), kind: 'qualified' });
    }
  });
  return refs;
}

function layerName(segment) { return /^[A-Z]/.test(segment) ? ROOT_LAYER : segment; }

function scan() {
  const files = walk(ROOT).sort();
  const data = files.map(f => {
    const rel = f.slice(ROOT.length + 1);
    const src = fs.readFileSync(f, 'utf8');
    const lineCount = src.length === 0 ? 0 : src.split('\n').length - (src.endsWith('\n') ? 1 : 0);
    return { file: f, rel, dir: path.posix.dirname(rel) === '.' ? '' : path.posix.dirname(rel), layer: layerOf(rel), lines: lineCount, refs: references(src) };
  });
  return data;
}

function countIn(data, dir, recursive) {
  return data.filter(d => recursive ? (d.dir === dir || d.dir.startsWith(dir + '/')) : d.dir === dir).length;
}

function pad(s, n) { s = String(s); return s + ' '.repeat(Math.max(0, n - s.length)); }
function lpad(s, n) { s = String(s); return ' '.repeat(Math.max(0, n - s.length)) + s; }

function printMap(data) {
  const pkgs = new Map();
  for (const d of data) {
    const key = d.dir === '' ? ROOT_LAYER : d.dir.split('/').slice(0, 3).join('.');
    const p = pkgs.get(key) ?? { files: 0, lines: 0 };
    p.files++; p.lines += d.lines; pkgs.set(key, p);
  }
  const keys = [...pkgs.keys()].sort();
  const w = Math.max(...keys.map(k => k.length), 7);
  console.log(`Files and lines per package (depth 3 under com.wormzjl.icheme), ${ROOT}`);
  console.log(`${pad('package', w)}  ${lpad('files', 6)}  ${lpad('lines', 8)}`);
  for (const k of keys) console.log(`${pad(k, w)}  ${lpad(pkgs.get(k).files, 6)}  ${lpad(pkgs.get(k).lines, 8)}`);
  const tf = data.length, tl = data.reduce((a, d) => a + d.lines, 0);
  console.log(`${pad('total', w)}  ${lpad(tf, 6)}  ${lpad(tl, 8)}`);

  const layers = [...new Set(data.map(d => d.layer))].sort();
  for (const kind of ['import', 'qualified']) {
    console.log('');
    console.log(kind === 'import'
      ? 'Import statements from layer (row) to layer (column); own layer shown as "."'
      : 'Qualified references (code lines naming another layer\'s type by full name, no import) from row to column');
    const cw = Math.max(...layers.map(l => l.length), 6);
    console.log(pad('', cw) + layers.map(l => lpad(l, cw + 1)).join(''));
    for (const a of layers) {
      const row = layers.map(b => {
        if (a === b) return lpad('.', cw + 1);
        const c = data.filter(d => d.layer === a).reduce((s, d) => s + d.refs.filter(r => r.kind === kind && r.layer === b).length, 0);
        return lpad(c, cw + 1);
      });
      console.log(pad(a, cw) + row.join(''));
    }
  }

  console.log('');
  console.log('Forbidden pairs (CODEBASE_REVIEW 3.1 layering); pinned in LayeringTest when the count is 0');
  for (const [a, b, why] of FORBIDDEN) {
    const hits = data.filter(d => d.layer === a).flatMap(d => d.refs.filter(r => r.layer === b).map(r => ({ d, r })));
    const files = [...new Set(hits.map(h => h.d.rel))];
    console.log(`  ${pad(a + ' -> ' + b, 22)} ${lpad(hits.length, 5)} references in ${lpad(files.length, 3)} files  (${why})`);
    for (const f of files) {
      const n = hits.filter(h => h.d.rel === f).length;
      console.log(`      ${f} (${n})`);
    }
  }
}

/** The README with its module-table counts recomputed; also returns the rows it could not map. */
function rewriteReadme(text, data) {
  const unmapped = [];
  const lines = text.split('\n');
  const start = lines.findIndex(l => l.startsWith('| Package / area |'));
  if (start < 0) throw new Error(`${README}: module table not found`);
  for (let i = start + 2; i < lines.length && lines[i].startsWith('|'); i++) {
    const line = lines[i];
    const end = line.indexOf('|', 1);
    const cell = line.slice(0, end);
    // A path in backticks followed by a parenthesised group: `p/**` (...) or `p/` (...).
    const re = /`([a-z][\w/]*?)(\/\*\*|\/)`\s\(([^()]*(?:\([^()]*\)[^()]*)*)\)/g;
    let out = '', last = 0, m, rowMapped = true;
    while ((m = re.exec(cell))) {
      const dir = m[1], recursive = m[2] === '/**', body = m[3];
      const next = mapCounts(dir, recursive, body, data);
      if (next === null) { rowMapped = false; continue; }
      const bodyStart = m.index + m[0].length - 1 - body.length;
      out += cell.slice(last, bodyStart) + next;
      last = bodyStart + body.length;
    }
    if (!rowMapped) unmapped.push(cell.trim());
    lines[i] = out + cell.slice(last) + line.slice(end);
  }
  return { text: lines.join('\n'), unmapped };
}

function mapCounts(dir, recursive, body, data) {
  if (!/\d/.test(body)) return body; // a description, not a count
  if (!/^\d/.test(body)) {
    // Named counts: (scene 11, films 11, ...)
    if (!/^[a-z]\w* \d+(, [a-z]\w* \d+)*$/.test(body)) return null;
    return body.replace(/([a-z]\w*) (\d+)/g, (_, name) => `${name} ${countIn(data, `${dir}/${name}`, true)}`);
  }
  const lead = /^(\d+)/.exec(body)[1];
  let rest = body.slice(lead.length);
  const plus = rest.startsWith(' + ');
  if (/^\s*(besides|but|without|except)\b/.test(rest) || /besides/.test(rest)) return null;
  const n = plus || !recursive ? countIn(data, dir, false) : countIn(data, dir, true);
  rest = rest.replace(/`([a-z][\w/]*?)\/` (\d+)/g, (_, sub) => `\`${sub}/\` ${countIn(data, `${dir}/${sub}`, true)}`);
  rest = rest.replace(/\+ ([a-z]\w*) (\d+)/g, (_, sub) => `+ ${sub} ${countIn(data, `${dir}/${sub}`, true)}`);
  return `${n}${rest}`;
}

const args = process.argv.slice(2);
const data = scan();
if (args.includes('--readme') || args.includes('--check')) {
  const before = fs.readFileSync(README, 'utf8');
  const { text, unmapped } = rewriteReadme(before, data);
  for (const u of unmapped) console.log(`unmapped (left alone): ${u.slice(0, 120)}`);
  if (args.includes('--check')) {
    if (text !== before) { console.log(`${README}: counts are stale; run node tools/code-guide/map.mjs --readme`); process.exit(1); }
    console.log(`${README}: counts current`);
  } else {
    if (text !== before) { fs.writeFileSync(README, text); console.log(`${README}: counts rewritten`); }
    else console.log(`${README}: counts current, unchanged`);
  }
} else {
  printMap(data);
}
