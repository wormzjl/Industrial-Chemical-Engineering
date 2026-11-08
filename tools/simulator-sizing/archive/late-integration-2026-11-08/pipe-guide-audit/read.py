from pathlib import Path
import json,sys
entries=json.loads(Path('tmp/pipe-guide-audit/entries.json').read_text(encoding='utf-8'))
name=sys.argv[1];offset=int(sys.argv[2]);budget=int(sys.argv[3]) if len(sys.argv)>3 else 12000
group=[e for e in entries if e['file']==name];text='';i=offset
while i<len(group):
 e=group[i];block=f"\n{e['id']} {e['file']}:{e['line']} [{e['heading']}]\n{e['text']}\n"
 if text and len(text)+len(block)>budget:break
 text+=block;i+=1
print(text);print(f'NEXT {name} {i} / {len(group)}')
