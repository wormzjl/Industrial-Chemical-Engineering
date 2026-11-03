// node tools/localization/merge.js <locale> <work-dir> : joins <work-dir>/<xx>-1.json, <xx>-2.json, ... into
// src/main/resources/assets/icheme/lang/<locale>.json in en_us key order (xx = the locale's first two letters).
const fs=require('fs');const [,,l,w]=process.argv;const dir='src/main/resources/assets/icheme/lang/';
const en=JSON.parse(fs.readFileSync(dir+'en_us.json','utf8'));const parts={};
for(let i=1;fs.existsSync(`${w}/${l.slice(0,2)}-${i}.json`);i++)Object.assign(parts,JSON.parse(fs.readFileSync(`${w}/${l.slice(0,2)}-${i}.json`,'utf8')));
const o={};for(const k in en)if(k in parts)o[k]=parts[k];fs.writeFileSync(dir+l+'.json',JSON.stringify(o,null,2)+'\n');console.log(l,Object.keys(o).length,'of',Object.keys(en).length);
