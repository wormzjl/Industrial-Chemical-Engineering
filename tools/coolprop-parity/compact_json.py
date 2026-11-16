"""The fixtures' JSON layout: one record per line.

A record is an object that holds no list of objects (a state with its numbers, nested plain objects included). It is
written on one line. Every other object is written one key per line, and a list of objects one item per line, with one
space of indent per level. Numbers are written by Python's own repr, as json.dumps writes them, so a value's text is
the same in either layout.

As a module: ``dumps(doc)`` for the generators. As a script it rewrites existing fixtures without CoolProp:

    python -I tools/coolprop-parity/compact_json.py [--out DIR] FILE...

With ``--out`` the files are written into DIR under their own names; without it, in place.
"""
import json
import pathlib
import sys


def _is_record(value):
    """An object with no list of objects anywhere inside: it goes on one line."""
    if isinstance(value, dict):
        return all(_is_record(v) for v in value.values())
    if isinstance(value, list):
        return not any(isinstance(v, (dict, list)) for v in value)
    return True


def _inline(value):
    return json.dumps(value, separators=(", ", ": "), ensure_ascii=False)


def _lines(value, depth, out, prefix, suffix):
    pad = " " * depth
    if isinstance(value, dict) and value and not (depth > 0 and _is_record(value)):
        out.append(pad + prefix + "{")
        items = list(value.items())
        for i, (key, item) in enumerate(items):
            _lines(item, depth + 1, out, json.dumps(key, ensure_ascii=False) + ": ", "," if i + 1 < len(items) else "")
        out.append(pad + "}" + suffix)
    elif isinstance(value, list) and value and any(isinstance(v, (dict, list)) for v in value):
        out.append(pad + prefix + "[")
        for i, item in enumerate(value):
            _lines(item, depth + 1, out, "", "," if i + 1 < len(value) else "")
        out.append(pad + "]" + suffix)
    else:
        out.append(pad + prefix + _inline(value) + suffix)


def dumps(doc):
    """The document in the fixtures' layout, with a final newline."""
    out = []
    _lines(doc, 0, out, "", "")
    return "\n".join(out) + "\n"


def write(path, doc):
    pathlib.Path(path).write_text(dumps(doc), encoding="utf-8", newline="\n")


def main(arguments):
    out = None
    if arguments[:1] == ["--out"]:
        out = pathlib.Path(arguments[1])
        out.mkdir(parents=True, exist_ok=True)
        arguments = arguments[2:]
    if not arguments:
        raise SystemExit(__doc__)
    for name in arguments:
        source = pathlib.Path(name)
        doc = json.loads(source.read_text(encoding="utf-8"))
        text = dumps(doc)
        if json.loads(text) != doc:
            raise SystemExit(f"{name}: the compact text does not parse back to the same document")
        target = out / source.name if out else source
        target.write_text(text, encoding="utf-8", newline="\n")
        print(f"{source.name}: {len(text)} bytes, {text.count(chr(10))} lines")


if __name__ == "__main__":
    main(sys.argv[1:])
