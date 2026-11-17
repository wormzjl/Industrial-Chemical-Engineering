"""Reads one Newton dump of trace.patch and prints, per iteration: the weighted norm, the residual by row group, the
largest rows, the line search's trials, and the Jacobian's singular values (numpy). Usage: python -I analyse_trace.py <dump> [top]"""
import sys, re
import numpy as np

path = sys.argv[1]; top = int(sys.argv[2]) if len(sys.argv) > 2 else 6
rows = cols = w = None; it = {}; jac = {}; cur_j = None; trials = []; ends = []; points = {}
for line in open(path, encoding="utf-8"):
    tag, _, rest = line.rstrip("\n").partition(" ")
    if tag == "HEADER": print(line.strip()[:400])
    elif tag == "ROWS": rows = rest.split(" ")
    elif tag == "COLS": cols = rest.split(" ")
    elif tag == "W": w = np.array([float(v) for v in rest.split(" ")])
    elif tag in ("X", "F", "D"):
        parts = rest.split(" "); k = int(parts[0]); it.setdefault(k, {})[tag] = np.array([float(v) for v in parts[1:]])
    elif tag == "N": parts = rest.split(" "); it.setdefault(int(parts[0]), {})["N"] = rest
    elif tag == "P": k, _, txt = rest.partition(" "); points[int(k)] = txt
    elif tag == "J":
        k, n = map(int, rest.split(" ")); cur_j = np.zeros((n, n)); jac[k] = cur_j
    elif tag == "JC":
        parts = rest.split(" "); c = int(parts[0])
        for e in parts[1:]:
            r, v = e.split(":"); cur_j[int(r), c] = float(v)
    elif tag in ("T", "A", "FRESH", "REFUSED", "SOLVEFAIL"): trials.append(line.strip())
    elif tag == "END": ends.append(line.strip())

def group(name):
    if ".hyd" in name: return "line rows (hyd)"
    if ".ctl:" in name:
        m = re.search(r",(\w+)\)\.ctl:(\w+)", name); return f"{m.group(1)} {m.group(2)}"
    if ".bal" in name: return "junction mass balances"
    if ".energy" in name: return "energy rows"
    return "node row " + name.split(".")[-1]

for k in sorted(it):
    d = it[k]; f = d.get("F")
    if f is None: continue
    wf = np.abs(f) * w
    print(f"\n-- iteration {k}: {d.get('N')}")
    groups = {}
    for i, v in enumerate(wf): g = group(rows[i]); groups[g] = max(groups.get(g, 0), v)
    print("   by group:", "; ".join(f"{g} {v:.4g}" for g, v in sorted(groups.items(), key=lambda t: -t[1])))
    order = np.argsort(-wf)[:top]
    print("   largest:", "; ".join(f"{rows[i]} {f[i]:.5g}" for i in order))
    if k in points: print("   point:", points[k][:1600])
    if k in jac:
        J = jac[k]; s = np.linalg.svd(J, compute_uv=False)
        print(f"   Jacobian: sigma max {s[0]:.4g}, min {s[-1]:.4g}, cond {s[0]/s[-1]:.4g}; smallest five {', '.join(f'{v:.3g}' for v in s[-5:])}")
        U, S, Vt = np.linalg.svd(J)
        v = Vt[-1]; o = np.argsort(-np.abs(v))[:6]
        print("   null-ish column direction:", "; ".join(f"{cols[i]} {v[i]:+.3f}" for i in o))
        u = U[:, -1]; o = np.argsort(-np.abs(u))[:6]
        print("   null-ish row combination:", "; ".join(f"{rows[i]} {u[i]:+.3f}" for i in o))
    if "D" in d:
        D = d["D"]; o = np.argsort(-np.abs(D))[:8]
        print("   Newton direction:", "; ".join(f"{cols[i]} {D[i]:+.5g}" for i in o))
print()
for t in trials[:60]: print("  ", t[:200])
for e in ends: print(e[:300])
