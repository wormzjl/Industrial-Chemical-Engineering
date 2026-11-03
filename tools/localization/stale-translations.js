// node tools/localization/stale-translations.js <locale> [out.json] : compares a translation with en_us.
// missing: en_us keys the locale lacks; stale: locale keys en_us no longer has; english_changed: keys whose English changed
// after their translation was last written (found from the git history of both files). Run from the repository root.
const fs=require('fs'),{execSync}=require('child_process');const l=process.argv[2],out=process.argv[3];
const d='src/main/resources/assets/icheme/lang/',sh=c=>execSync(c,{encoding:'utf8',maxBuffer:1<<28});
const en=JSON.parse(fs.readFileSync(d+'en_us.json','utf8')),tr=JSON.parse(fs.readFileSync(d+l+'.json','utf8'));
const commits=sh(`git log --format=%H -- ${d}${l}.json`).trim().split('\n').filter(Boolean).reverse();
const at=(c,f)=>{try{return JSON.parse(sh(`git show ${c}:${d}${f}.json`))}catch{return {}}};const hist=commits.map(c=>({tr:at(c,l),en:at(c,'en_us')}));
const missing=Object.keys(en).filter(k=>!(k in tr)),stale=Object.keys(tr).filter(k=>!(k in en)),changed=[];
for(const k in tr){if(!(k in en)||!hist.length)continue;let i=hist.length-1;if(hist[i].tr[k]!==tr[k])continue;while(i>0&&hist[i-1].tr[k]===tr[k])i--;
 const was=hist[i].en[k];if(was!==undefined&&was!==en[k])changed.push({key:k,english_when_translated:was,english_now:en[k],translation:tr[k]});}
console.log(l,'missing',missing.length,'stale',stale.length,'english_changed',changed.length);
if(out)fs.writeFileSync(out,JSON.stringify({missing,stale,english_changed:changed},null,1));
