// Revision-token table of the material records changed on both sides of the merge of main 0.33.0.
// Scope of a token: the object that declares "revision", without its sub-objects that declare their own.
const { execSync } = require('child_process');
const fs = require('fs');
const BASE = 'b41f7f9c7', MAIN = 'main', OURS = 'cdc05e565';
const files = fs.readFileSync('tmp/supercritical-briefs/both3-changed.txt', 'utf8').split(/\r?\n/)
  .filter(f => f.includes('materials/properties/'));
const show = (rev, f) => { try { return JSON.parse(execSync(`git show ${rev}:${f}`, { maxBuffer: 1 << 26 }).toString()); } catch (e) { return null; } };
function scopes(obj, path, out) {
  if (obj === null || typeof obj !== 'object') return out;
  if (!Array.isArray(obj) && typeof obj.revision === 'string') out[path || '$'] = obj;
  for (const [k, v] of Object.entries(obj)) if (v && typeof v === 'object') scopes(v, path + '/' + k, out);
  return out;
}
function own(obj) { // values of the scope without sub-scopes
  if (obj === null || typeof obj !== 'object') return obj;
  if (Array.isArray(obj)) return obj.map(own);
  const r = {};
  for (const [k, v] of Object.entries(obj)) {
    if (k === 'revision') continue;
    if (v && typeof v === 'object' && !Array.isArray(v) && typeof v.revision === 'string') continue;
    r[k] = own(v);
  }
  return r;
}
const rows = []; let problems = 0;
for (const f of files) {
  const b = show(BASE, f), m = show(MAIN, f), o = show(OURS, f);
  const w = JSON.parse(fs.readFileSync(f, 'utf8'));
  const sb = scopes(b, '', {}), sm = scopes(m, '', {}), so = scopes(o, '', {}), sw = scopes(w, '', {});
  const paths = new Set([...Object.keys(sb), ...Object.keys(sm), ...Object.keys(so), ...Object.keys(sw)]);
  for (const p of paths) {
    const tok = s => s[p] ? s[p].revision : '-';
    const val = s => s[p] ? JSON.stringify(own(s[p])) : null;
    const mTok = tok(sm) !== tok(sb), oTok = tok(so) !== tok(sb);
    const mVal = val(sm) !== val(sb), oVal = val(so) !== val(sb);
    const wSide = val(sw) === val(sm) ? (val(sw) === val(so) ? 'both' : 'main') : (val(sw) === val(so) ? 'ours' : 'NEITHER');
    let why;
    if (mTok && oTok) why = 'BOTH CHANGED TOKEN';
    else if ((mTok && oVal) || (oTok && mVal)) why = 'TOKEN ONE SIDE, VALUES OTHER';
    else if ((mVal && !mTok) || (oVal && !oTok)) why = 'VALUES WITHOUT TOKEN (' + (mVal && !mTok ? 'main' : '') + (oVal && !oTok ? 'ours' : '') + ')';
    else why = mTok ? 'main only' : oTok ? 'ours only' : 'unchanged';
    if (/[A-Z]/.test(why)) problems++;
    rows.push([f.replace(/.*properties\//, ''), p, tok(sb), tok(sm), tok(so), tok(sw), wSide, why]);
  }
}
for (const r of rows) console.log(r.join(' | '));
console.log('problems', problems);
