// Output: pretty JSON with 2-space indentation and CRLF line ends, as the bundled working-tree files have
// (git stores them LF; `git ls-files --eol` gives i/lf w/crlf).
import fs from 'node:fs';
import path from 'node:path';

export function writeJson(file, value) {
  fs.mkdirSync(path.dirname(file), {recursive: true});
  fs.writeFileSync(file, JSON.stringify(value, null, 2).replace(/\n/g, '\r\n') + '\r\n');
}
export function writeText(file, text) {
  fs.mkdirSync(path.dirname(file), {recursive: true});
  fs.writeFileSync(file, text.replace(/\r?\n/g, '\n'));
}
export const readJsonFile = file => JSON.parse(fs.readFileSync(file, 'utf8'));
/** True when the module at `url` is the script node was started with. */
export const isMain = url => path.resolve(process.argv[1] ?? '') === path.resolve(new URL(url).pathname.replace(/^\/([A-Za-z]:)/, '$1'));
