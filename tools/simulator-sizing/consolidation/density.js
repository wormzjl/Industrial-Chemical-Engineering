// Line density of the Java files a branch adds under src/, at each given revision: lines, lines over 120 and 200
// characters, the longest line, and lines with more than one statement (for loops excepted).
// Usage, from the repository root: node tools/simulator-sizing/consolidation/density.js <base> <head> [<rev>...]
// The file list is `git diff --name-only --diff-filter=A <base> <head> -- 'src/*.java'`; each of <head> and the other
// revisions is measured on that list. Consolidation review 2026-11-08: f1db3a892 3107ecc90 658861333.
const { execSync } = require('child_process');

const [base, head, ...more] = process.argv.slice(2);
if (!base || !head) throw new Error('usage: density.js <base> <head> [<rev>...]');
const files = execSync(`git diff --name-only --diff-filter=A ${base} ${head} -- "src/*.java"`).toString()
    .split(/\r?\n/).filter(Boolean);
const strings = /"(?:[^"\\]|\\.)*"/g;

function stats(rev) {
    let lines = 0, over120 = 0, over200 = 0, max = 0, multi = 0;
    for (const f of files) {
        let s;
        try { s = execSync(`git show ${rev}:${f}`, { maxBuffer: 1 << 26 }).toString(); } catch (e) { continue; }
        for (const l of s.split(/\r?\n/)) {
            lines++;
            const n = l.length;
            if (n > 120) over120++;
            if (n > 200) over200++;
            if (n > max) max = n;
            const semis = (l.replace(strings, '').match(/;/g) || []).length;
            if (semis > 1 && !/^\s*for\s*\(/.test(l)) multi++;
        }
    }
    return { lines, over120, over200, max, multi };
}

console.log('files', files.length);
for (const rev of [head, ...more]) console.log(rev, JSON.stringify(stats(rev)));
