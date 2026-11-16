import json, sys
from pathlib import Path
for f in sys.argv[1:]:
    d = json.loads(Path(f).read_text(encoding="utf-8"))
    e = d["EOS"][0]
    a0 = [t["type"] for t in e["alpha0"]]; ar = [t["type"] for t in e["alphar"]]
    c = d["STATES"]["critical"]
    print(Path(f).name, "| a0:", sorted(set(a0)), "| ar:", sorted(set(ar)), "| Tc", c["T"], "pc", c["p"], "rhoc", c["rhomolar"],
          "| acentric", e.get("acentric"), "| Tmax", e["T_max"], "pmax", e["p_max"], "| M", e["molar_mass"], "| anc", sorted(d["ANCILLARIES"].keys()),
          "| pS type", d["ANCILLARIES"]["pS"].get("type"), d["ANCILLARIES"]["rhoL"].get("type"), d["ANCILLARIES"]["rhoV"].get("type"))
