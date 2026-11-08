from pathlib import Path
import json,re,sys
entries=json.loads(Path('tmp/pipe-guide-audit/entries.json').read_text(encoding='utf-8'))
name=sys.argv[1];offset=int(sys.argv[2]) if len(sys.argv)>2 else 0
group=[e for e in entries if e['file']==name]
for e in group[offset:int(sys.argv[3]) if len(sys.argv)>3 else len(group)]:
 text=re.sub(r'\s+',' ',e['text'])
 code=list(dict.fromkeys(re.findall(r'`([^`]+)`',text)))
 print(f"{e['id']} L{e['line']} {e['heading'].split(' / ')[-1]}: {text[:430]}"+(f" [code: {', '.join(code)[:350]}]" if code else ''))
