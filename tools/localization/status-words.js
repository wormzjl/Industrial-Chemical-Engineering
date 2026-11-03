// node tools/localization/status-words.js <locale>... : lists all-caps words quoted in en_us (server-built English status
// words such as LIMITED, LOW LIMIT, DUTY INSUFFICIENT) that a translation does not keep verbatim. Acronyms that a language
// writes its own way (CFB, VDU) show up too; judge those by hand.
const fs=require('fs');const d='src/main/resources/assets/icheme/lang/';const en=JSON.parse(fs.readFileSync(d+'en_us.json','utf8'));
const skip=/^(API|NBP|LED|LCD|RRGGBB|TEMA|ASME|JSON|RAL|HUD|GUI|IChemE|HC|ISO|DIN|NPS|ANSI|TBP|ASTM)$/;
const tok=s=>[...new Set((s.match(/\b[A-Z]{3,}(?: [A-Z]{2,})*\b/g)||[]).filter(t=>!skip.test(t)))];
for(const l of process.argv.slice(2)){const tr=JSON.parse(fs.readFileSync(d+l+'.json','utf8'));let n=0;
 for(const k in en)for(const t of tok(en[k]))if(k in tr&&!tr[k].includes(t)){n++;console.log(l,k,t)}console.log(l,'not kept:',n)}
