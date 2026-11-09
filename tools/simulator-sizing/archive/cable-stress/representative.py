from pathlib import Path
p=Path('tmp/cable-stress')
(p/'stress-representative.args').write_text((p/'stress.args').read_text(encoding='utf-8')+'representative\n',encoding='utf-8')
s=(p/'run.ps1').read_text(encoding='utf-8').replace('stress.args','stress-representative.args').replace('stress.log','stress-representative.log').replace('stress-error.log','stress-error-representative.log').replace('process-memory.csv','process-memory-representative.csv').replace('process-summary.json','process-summary-representative.json')
(p/'run-representative.ps1').write_text(s,encoding='utf-8')
