"""Exports a train.py checkpoint as the payload and sidecar the Java reader takes (V3NeuralRegistry.TransformerArtifact),
batch 2026-11-05-neural-retrain. The sidecar is schema 5 (N5): it takes the decoder, rules, presence threshold, trace floor,
pumparound splits and the steam-at-sump rule from a template sidecar, and writes the trained physics the probe's `physics`
wrote (physics.json beside features.jsonl) with its ceiling at the TRAIN feed temperature maximum, the node-pressure bounds of
the TRAIN rows (top pressure to bottom pressure), its own model id, payload hash, formulation revisions, branches seen, the global bounds over the
TRAIN features (the composition box widened by 10 % of its span, NEURAL_RETRAIN_DATA.md section 4), the E7 families (one per unit:
mean, 3-PC basis, the TRAIN compositions' coordinates, in-plane and out-of-plane limits at the 99th percentile of the trained spread),
the off-axis limit (the owner's 5 %) and the U1 lump map the probe's export wrote (lumpMap.json beside features.jsonl).

Run: python -I tools/neural-retrain/export.py <checkpoint.pt> <norm.json> <features.jsonl> <template-sidecar.json> <model-id> <out-dir>
"""
import hashlib
import json
import sys
from pathlib import Path

import numpy as np
import torch

sys.path.insert(0, str(Path(__file__).resolve().parent))
from train import folds, read  # noqa: E402

FEATURE_REVISION = 'v3-anchor-augmented-regrouped-2'     # the Java encoding is unchanged under U1
ANCHOR_REVISION = 'native-material-closed-anchor-v2-basis-width'
OFF_AXIS_LIMIT = 0.05
BOX = slice(17, 17 + 19)        # global features x17..x35: the feed's mole fractions
MAX_POINTS = 2048               # V3AnchorTransformerInitializer.Family.MAXIMUM_POINTS
COMPONENTS = ['Methane', 'Ethane', 'Propane', 'Isobutane', 'N-butane', 'Isopentane', 'N-pentane'] + [f'crude_pc{i:02d}' for i in range(1, 13)]


def family(name, fractions):
    """E7 family of one unit's TRAIN compositions (non-injected rows): mean, 3-PC basis, coordinates, limits."""
    x = np.asarray(fractions, dtype=np.float64)
    mean = x.mean(0)
    _, _, vt = np.linalg.svd(x - mean, full_matrices=False)
    basis = vt[:min(3, len(vt))]
    coords = (x - mean) @ basis.T
    residual = np.linalg.norm((x - mean) - coords @ basis, axis=1)
    if len(coords) > 1:
        d = np.linalg.norm(coords[:, None, :] - coords[None, :, :], axis=-1)
        np.fill_diagonal(d, np.inf)
        in_plane = float(np.percentile(d.min(1), 99))
    else:
        in_plane = 0.0
    points = coords[::max(1, -(-len(coords) // MAX_POINTS))]
    return {'name': name, 'mean': mean.tolist(), 'basis': basis.tolist(), 'points': points.tolist(),
            'inPlaneLimit': in_plane, 'outOfPlaneLimit': float(np.percentile(residual, 99))}


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
    every = read(features); fold = folds(every)
    rows = [r for r in every if fold(r) == 'train']
    g = np.asarray([r['global'] for r in rows])
    side = json.loads(Path(template).read_text(encoding='utf-8'))
    side['payloadSha256'] = hashlib.sha256(payload_bytes).hexdigest()
    side['modelId'] = model_id
    side['formulationRevisions'] = sorted({r['formulationRevision'] for r in rows if r.get('formulationRevision')})
    side['branchesSeen'] = ck['branchesSeen']
    lo, hi = g.min(0), g.max(0)
    span = hi[BOX] - lo[BOX]
    lo[BOX] = np.maximum(lo[BOX] - 0.1 * span, 0); hi[BOX] = hi[BOX] + 0.1 * span
    side['globalMin'] = lo.tolist(); side['globalMax'] = hi.tolist()
    physics = json.loads((Path(features).parent / 'physics.json').read_text(encoding='utf-8'))
    physics['temperatureMaximumKelvin'] = float(hi[1])
    side['physics'] = physics
    top = g[:, 2] * 1e5; bottom = top + (np.rint(g[:, 4] * 64) - 1) * g[:, 3] * 1e3   # V3GeneralNeuralFeatures.global x2, x3, x4
    side['designConstraints'] = side['designConstraints'] | {'minimumNodePressurePascal': float(top.min()),
                                                             'maximumNodePressurePascal': float(bottom.max())}
    side['schemaVersion'] = 5
    side.pop('compositionEdges', None)
    known = [r for r in rows if not r.get('injected')]
    side['families'] = [family(unit, [r['feedFractions'] for r in known if r['unit'] == unit])
                        for unit in ('cdu', 'vdu') if any(r['unit'] == unit for r in known)]
    side['offAxisLimit'] = OFF_AXIS_LIMIT
    side['lumpMap'] = json.loads((Path(features).parent / 'lumpMap.json').read_text(encoding='utf-8'))
    (out / 'sidecar.json').write_text(json.dumps(side), encoding='utf-8')
    print(json.dumps({'payloadBytes': len(payload_bytes), 'payloadSha256': side['payloadSha256'], 'formulationRevisions': side['formulationRevisions'],
                      'trainRows': len(rows), 'nodePressurePascal': [side['designConstraints']['minimumNodePressurePascal'],
                      side['designConstraints']['maximumNodePressurePascal']], 'trainedMaximumKelvin': physics['temperatureMaximumKelvin'],
                      'families': [{k: f[k] for k in ('name', 'inPlaneLimit', 'outOfPlaneLimit')} | {'points': len(f['points'])} for f in side['families']]}))


if __name__ == '__main__':
    main(*sys.argv[1:7])
