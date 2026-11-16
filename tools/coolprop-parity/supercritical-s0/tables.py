import sys, re, html
from pathlib import Path
x = html.unescape(Path(sys.argv[1]).read_text(encoding="utf-8"))
out = {}
for line in x.splitlines():
    m = re.match(r"^G3 F11 (M1 range|M1 H2O|M1|M2|M3|M4|M5 bundled|M5|M6 today|M6 O9 HYDROGEN|M6 O9 NONPOLAR|M6|M7|M8 range|M8) (\|.*)$", line)
    if m:
        out.setdefault(m.group(1), []).append(m.group(2))
for k, v in out.items():
    Path(sys.argv[2], k.replace(" ", "_") + ".md").write_text("\n".join(v) + "\n", encoding="utf-8")
    print(k, len(v))
