"""The independent float64 side of the bundled model's fixtures (N6, batch 2026-11-05-neural-retrain): PyTorch float64
inference of a payload on the probe's `features` rows (model.py), the raw outputs (de-normalised) and the branch logits of
the named rows, for NeuralRetrainProbe.fixtures.

Run: python -I tools/neural-retrain/fixtures.py <payload> <features.jsonl> <id,id,...> <out.json>
"""
import json
import sys
from pathlib import Path

import numpy as np
import torch

sys.path.insert(0, str(Path(__file__).resolve().parent))
from model import joined_inputs, load_payload  # noqa: E402


def main(payload_path, features_path, ids, out_path):
    model, norm = load_payload(payload_path)
    wanted = ids.split(',')
    rows = {r['id']: r for r in (json.loads(line) for line in open(features_path, encoding='utf-8') if line.strip()) if r['id'] in wanted}
    out = {}
    for i in wanted:
        row = rows[i]
        x = torch.tensor(joined_inputs(row, norm, model.node_width, model.output_width))[None]
        g = torch.tensor((np.asarray(row['global']) - norm['gm']) / norm['gscale'])[None]
        with torch.no_grad():
            values, logits = model(x, g, torch.ones(x.shape[:2], dtype=torch.bool))
        raw = values[0].numpy() * norm['yscale'] + norm['ym']
        out[i] = {'raw': raw.tolist(), 'branchLogits': logits[0].numpy().tolist()}
        print(i, 'raw vs Java', float(np.max(np.abs(raw - np.asarray(row['javaRaw'])) / np.maximum(1.0, np.abs(row['javaRaw'])))))
    Path(out_path).write_text(json.dumps(out), encoding='utf-8')


if __name__ == '__main__':
    main(*sys.argv[1:5])
