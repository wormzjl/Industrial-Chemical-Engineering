// Splits en_us.json into N chunk files tmp/lang-review/en-<i>.json (key order kept).
const fs=require('fs');const [,,lang,n,out]=process.argv;const en=JSON.parse(fs.readFileSync(lang,'utf8'));const ks=Object.keys(en);const size=Math.ceil(ks.length/n);
for(let i=0;i<n;i++){const o={};for(const k of ks.slice(i*size,(i+1)*size))o[k]=en[k];fs.writeFileSync(`${out}/en-${i+1}.json`,JSON.stringify(o,null,2)+'\n');console.log(i+1,Object.keys(o).length,Buffer.byteLength(JSON.stringify(o)))}
