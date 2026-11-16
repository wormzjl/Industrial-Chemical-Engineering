import json,sys
from pathlib import Path
for f in ["Nitrogen","CarbonDioxide","Methane","Ethane","Hydrogen"]:
    a=json.loads(Path(sys.argv[1],f"parity-{f}.json").read_text()); b=json.loads(Path(sys.argv[2],f"parity-{f}.json").read_text())
    n=len(a["single_phase"]); same=all(b[k]==a[k] for k in a if k!="single_phase")
    print(f, n, len(b["single_phase"]), b["single_phase"][:n]==a["single_phase"], same, set(r["source"] for r in b["single_phase"][n:]), set(r["phase"] for r in b["single_phase"][n:]))
