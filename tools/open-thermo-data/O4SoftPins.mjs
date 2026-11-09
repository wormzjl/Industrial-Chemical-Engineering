// Throwaway helper of batch 2026-11-04-open-thermo-data (O4). `node O4SoftPins.mjs on <TestClass.java>...` (paths under
// src/test/java, from the worktree root) saves each file to tmp/open-thermo-o4/soft-originals/, turns its assertEquals into
// O4Soft.eq (non-throwing; prints PIN lines) and copies O4Soft.java into src/test/java/com/wormzjl/icheme/. Run the tests,
// then `node O4SoftPins.mjs off` restores every saved file and removes O4Soft.java. Never commit the soft copies.
import fs from 'node:fs';
import path from 'node:path';
const SAVE = 'tmp/open-thermo-o4/soft-originals', HELPER = 'src/test/java/com/wormzjl/icheme/O4Soft.java';
const [mode, ...files] = process.argv.slice(2);
if (mode === 'on') {
  fs.mkdirSync(SAVE, {recursive: true});
  fs.copyFileSync(path.join(path.dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1')), 'O4Soft.java'), HELPER);
  const manifest = [];
  for (const file of files) {
    const text = fs.readFileSync(file, 'utf8'), saved = path.join(SAVE, path.basename(file));
    fs.writeFileSync(saved, text); manifest.push([file, saved]);
    fs.writeFileSync(file, text.replace(/(?<![\w.])assertEquals\(/g, 'com.wormzjl.icheme.O4Soft.eq('));
  }
  fs.writeFileSync(path.join(SAVE, 'manifest.json'), JSON.stringify(manifest));
} else if (mode === 'off') {
  for (const [file, saved] of JSON.parse(fs.readFileSync(path.join(SAVE, 'manifest.json'), 'utf8'))) fs.copyFileSync(saved, file);
  fs.rmSync(HELPER, {force: true});
} else throw new Error('usage: on <files> | off');
