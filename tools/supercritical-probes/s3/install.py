import json, sys
from pathlib import Path
D = Path("src/main/resources/assets/icheme/flowsheet_examples")
for n in ["crude_unit", "small_crude_unit", "ethanol_rectifier"]:
    old = (D / (n + ".json")).read_bytes().decode("utf-8")
    new = Path("tmp/supercritical-s3/probe/out/" + n + ".json").read_bytes().decode("utf-8").replace("\r\n", "\n")
    a, b = json.loads(old), json.loads(new)
    assert a["basis"]["revision"] != b["basis"]["revision"]
    a["basis"].pop("revision"); b["basis"].pop("revision")
    assert a == b, n  # every value but the basis revision equal
    if "\r\n" in old: new = new.replace("\n", "\r\n")
    (D / (n + ".json")).write_bytes(new.encode("utf-8"))
    print(n, "installed; all other values equal")
