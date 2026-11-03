// Usage: node check.js <english.json> <translated.json>. Checks key parity, format specifiers, newlines, emptiness.
const fs=require('fs');const [,,a,b]=process.argv;const en=JSON.parse(fs.readFileSync(a,'utf8'));let tr;
try{tr=JSON.parse(fs.readFileSync(b,'utf8'))}catch(e){console.log('INVALID JSON',e.message);process.exit(1)}
const spec=s=>(s.match(/%(\d+\$)?[sdf%]|%\.\d+f/g)||[]).sort().join(' ');let bad=0;const p=(...x)=>{bad++;console.log(...x)};
for(const k in en){if(!(k in tr)){p('MISSING',k);continue}if(typeof tr[k]!=='string'||!tr[k].trim()&&en[k].trim())p('EMPTY',k);
 else{if(spec(en[k])!==spec(tr[k]))p('FORMAT',k,JSON.stringify(spec(en[k])),'vs',JSON.stringify(spec(tr[k])));
 if((en[k].match(/\n/g)||[]).length!==(tr[k].match(/\n/g)||[]).length)p('NEWLINES',k);
 if((en[k].match(/§./g)||[]).join()!==(tr[k].match(/§./g)||[]).join())p('COLOUR',k);}}
for(const k in tr)if(!(k in en))p('EXTRA',k);
const raw=fs.readFileSync(b,'utf8');const n=(raw.match(/^\s*"[^"]+"\s*:/gm)||[]).length;if(n!==Object.keys(tr).length)p('DUPLICATE KEYS',n-Object.keys(tr).length);
console.log(bad?`${bad} problems`:`OK ${Object.keys(tr).length} keys`);process.exit(bad?1:0);
