"""Exports a train.py checkpoint as the payload and sidecar the Java reader takes (V3NeuralRegistry.TransformerArtifact),
batch 2026-11-05-neural-retrain. The sidecar is schema 4 for now (N5 replaces compositionEdges with the E7 family check, schema 5):
it takes the physics block, decoder and rules from a template sidecar, and its own model id, payload hash, formulation revisions,
branches seen, global bounds over the TRAIN features, node-pressure bounds and composition edges between the training grades.

Run: python -I tools/neural-retrain/export.py <checkpoint.pt> <norm.json> <features.jsonl> <template-sidecar.json> <model-id> <out-dir>
"""
import hashlib
import json
import sys
from pathlib import Path

import numpy as np
import torch

sys.path.insert(0, str(Path(__file__).resolve().parent))
from train import fold, read  # noqa: E402

FEATURE_REVISION = 'v3-anchor-augmented-regrouped-2'     # the Java encoding is unchanged under U1
ANCHOR_REVISION = 'native-material-closed-anchor-v2-basis-width'
COMPONENTS = ['Methane', 'Ethane', 'Propane', 'Isobutane', 'N-butane', 'Isopentane', 'N-pentane'] + [f'crude_pc{i:02d}' for i in range(1, 13)]


def main(checkpoint, norm_path, features, template, model_id, out):
    ck = torch.load(checkpoint, map_location='cpu')
    if ck['width'] != 64 or ck['u2']:
        raise SystemExit('The Java reader of today takes width 64 without U2 channels; N5 widens it')
    norm = json.loads(Path(norm_path).read_text())
    payload = {'schemaVersion': 1, 'featureRevision': FEATURE_REVISION, 'modelType': 'anchor-augmented', 'anchorLayout': 'full',
               'baselineRevision': ANCHOR_REVISION, 'components': COMPONENTS, 'normalization': norm,
               'weights': {name: {'shape': list(t.shape), 'values': t.double().flatten().tolist()} for name, t in ck['state'].items()}}
    out = Path(out); out.mkdir(parents=True, exist_ok=True)
    payload_bytes = json.dumps(payload, separators=(',', ':')).encode('utf-8')
    (out / 'payload.json').write_bytes(payload_bytes)
    rows = [r for r in read(features) if fold(r) == 'train']
    g = np.asarray([r['global'] for r in rows])
    side = json.loads(Path(template).read_text(encoding='utf-8'))
    side['payloadSha256'] = hashlib.sha256(payload_bytes).hexdigest()
    side['modelId'] = model_id
    side['formulationRevisions'] = sorted({r['formulationRevision'] for r in rows if r.get('formulationRevision')})
    side['branchesSeen'] = ck['branchesSeen']
    side['globalMin'] = g.min(0).tolist(); side['globalMax'] = g.max(0).tolist()
    pure = {}
    for r in rows:
        f = np.asarray(r['feedFractions']); pure.setdefault(tuple(np.round(f, 12)), f)
    vertices = list(pure.values())[:4]
    side['compositionEdges'] = [[a.tolist(), b.tolist()] for i, a in enumerate(vertices) for b in vertices[i + 1:]] or [[vertices[0].tolist(), vertices[0].tolist()]]
    (out / 'sidecar.json').write_text(json.dumps(side), encoding='utf-8')
    print(json.dumps({'payloadBytes': len(payload_bytes), 'payloadSha256': side['payloadSha256'], 'formulationRevisions': side['formulationRevisions'],
                      'trainRows': len(rows)}))


if __name__ == '__main__':
    main(*sys.argv[1:7])
