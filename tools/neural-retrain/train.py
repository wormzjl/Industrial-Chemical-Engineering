"""N3 trainer of batch 2026-11-05-neural-retrain: the anchor-augmented Transformer (model.ColumnModel) from scratch on the
features NeuralRetrainProbe.export writes (Java computes every feature, anchor and target; Python only normalises and fits).

Recipe of the training guide section 8.2: AdamW 8e-4, weight decay 1e-4, batch 32, gradient clip 1, 160 epochs, float32 CUDA,
deterministic; loss of tools/neural/train_transformer.loss (temperature, log1p(L/F), log1p(V/F), free water, wet flag with weights
3, 5, 5, 0.5, 2; per phase 2 x KL on present-phase fractions, 0.1 x squared error on centred log ratios, 0.2 x presence
cross-entropy) plus branch cross-entropy. Normalisation from TRAIN rows only, column-balanced (1 / nodes).

Run: python -I tools/neural-retrain/train.py <features.jsonl> <out-dir> [--seed N] [--epochs N] [--width 64] [--u2]
Writes <out-dir>/checkpoint-<epoch>.pt, norm.json, report.json.
"""
import argparse
import hashlib
import json
import sys
from pathlib import Path

import numpy as np
import torch
from torch.nn import functional as F

sys.path.insert(0, str(Path(__file__).resolve().parent))
from model import ColumnModel  # noqa: E402


def read(path):
    return [json.loads(line) for line in open(path, encoding='utf-8') if line.strip()]


def folds(rows):
    """The fold of each row: its design split, except that TRAIN rows split 85/15 by a hash of the id when the set has no
    validation rows (the pilot smoke). N2 has its own validation journals, so its TRAIN rows all train."""
    held = any(r['split'] != 'train' for r in rows)

    def fold(row):
        if row['split'] != 'train' or held:
            return row['split']
        return 'validation' if int(hashlib.sha256(row['id'].encode()).hexdigest(), 16) % 100 < 15 else 'train'
    return fold


def moments(values, weights=None):
    v = np.asarray(values, dtype=np.float64)
    w = np.ones(len(v)) if weights is None else np.asarray(weights, dtype=np.float64)
    mean = (w[:, None] * v).sum(0) / w.sum()
    std = np.sqrt((w[:, None] * (v - mean) ** 2).sum(0) / w.sum())
    return mean, np.maximum(std, 1e-6)


def global_of(row, u2):
    g = list(row['global'])
    return g + list(row['offAxisBands']) if u2 else g


def node_rows(row, node_width, c, u2):
    """A row's node features. Under U2 the three off-axis bands join the global block each node row repeats (the first
    54 + c columns, V3GeneralNeuralFeatures.nodes), as a Java U2 encoding would place them: global, bands, then the 22 local."""
    x = np.asarray(row['nodes'])[:, :node_width]
    if not u2:
        return x
    g = 54 + c
    return np.concatenate([x[:, :g], np.tile(np.asarray(row['offAxisBands']), (len(x), 1)), x[:, g:]], axis=1)


def normalization(rows, node_width, c, u2):
    xs, ys, bs, weights = [], [], [], []
    for r in rows:
        x = node_rows(r, node_width, c, u2)
        xs.append(x); ys.append(np.asarray(r['targets'])); bs.append(np.asarray(r['anchor']))
        weights.extend([1 / len(x)] * len(x))
    xm, xscale = moments(np.vstack(xs), weights)

    def target_moments(stack):
        m, s = moments(np.vstack(stack), weights)
        s[0] = max(s[0], 25); s[1:3] = np.maximum(s[1:3], .1)
        s[3:3 + 2 * c] = np.maximum(s[3:3 + 2 * c], 1)
        s[3 + 2 * c] = max(s[3 + 2 * c], 1); s[4 + 2 * c] = 1
        m[5 + 2 * c:] = 0; s[5 + 2 * c:] = 1
        return m, s
    ym, yscale = target_moments(ys)
    bm, bscale = target_moments(bs)
    gm, gscale = moments([global_of(r, u2) for r in rows])
    return {k: v.tolist() for k, v in dict(xm=xm, xscale=xscale, ym=ym, yscale=yscale, gm=gm, gscale=gscale, bm=bm, bscale=bscale).items()}


def tensors(rows, norm, node_width, c, u2, device):
    n, length = len(rows), max(len(r['nodes']) for r in rows)
    inputs = node_width + (3 if u2 else 0) + 4 * c + 5 + 4
    x = np.zeros((n, length, inputs), np.float32)
    raw = np.zeros((n, length, 4 * c + 5), np.float32)
    fractions = np.zeros((n, length, 2, c), np.float32)
    valid = np.zeros((n, length), bool)
    xm, xs, bm, bs = (np.asarray(norm[k]) for k in ('xm', 'xscale', 'bm', 'bscale'))
    for i, r in enumerate(rows):
        nodes = node_rows(r, node_width, c, u2); count = len(nodes)
        extra = np.zeros((count, 4)); extra[:, r['branch']] = 1; extra[:, 3] = 1.0 if r['anchorAvailable'] else 0.0
        x[i, :count] = np.concatenate([(nodes - xm) / xs, (np.asarray(r['anchor']) - bm) / bs, extra], axis=1)
        raw[i, :count] = r['targets']; fractions[i, :count] = r['fractions']; valid[i, :count] = True
    g = (np.asarray([global_of(r, u2) for r in rows]) - norm['gm']) / np.asarray(norm['gscale'])
    feed = np.asarray([r['feedFractions'] for r in rows])
    values = dict(x=x, g=g, raw=raw, fractions=fractions, valid=valid, feed=feed,
                  labels=np.asarray([r['branch'] for r in rows], dtype=np.int64))
    return {k: torch.as_tensor(v, device=device, dtype=torch.bool if k == 'valid' else torch.long if k == 'labels' else torch.float32)
            for k, v in values.items()}


def loss(pred, branch, batch, ym, ys):
    """tools/neural/train_transformer.loss, unchanged."""
    raw = pred * ys + ym
    target = batch['raw']; wanted = (target - ym) / ys
    c = batch['feed'].shape[-1]
    indices = [0, 1, 2, 3 + 2 * c, 4 + 2 * c]
    scalar_weights = pred.new_tensor([3, 5, 5, .5, 2])
    node = ((pred[..., indices] - wanted[..., indices]).square() * scalar_weights).sum(-1)
    active = batch['feed'][:, None, :] > 0
    for phase in range(2):
        offset = 3 + phase * c; support = 5 + 2 * c + phase * c
        logits = raw[..., offset:offset + c] + batch['feed'][:, None, :].clamp_min(1e-30).log()
        logprob = logits.masked_fill(~active, -1e30).log_softmax(-1)
        actual = batch['fractions'][:, :, phase]
        kl = (actual * (actual.clamp_min(1e-30).log() - logprob)).sum(-1)
        present = target[..., 1 + phase] > 0
        node = node + 2 * kl * present
        mask = active & present[..., None]
        node = node + .1 * ((pred[..., offset:offset + c] - wanted[..., offset:offset + c]).square() * mask).sum(-1) / mask.sum(-1).clamp_min(1)
        bce = F.binary_cross_entropy_with_logits(raw[..., support:support + c], (target[..., support:support + c] > 0).float(), reduction='none')
        node = node + .2 * (bce * active).sum(-1) / active.sum(-1).clamp_min(1)
    valid = batch['valid']
    per_column = (node * valid).sum(1) / valid.sum(1)
    return per_column.mean() + F.cross_entropy(branch, batch['labels'])


def evaluate(model, batch, ym, ys, c, seen):
    """Validation numbers N3 compares forms on: tray-1 and all-node temperature error, top vapour and condenser water, branch."""
    model.eval()
    with torch.no_grad():
        pred, logits = model(batch['x'], batch['g'], batch['valid'])
        value = loss(pred, logits, batch, ym, ys).item()
        raw = pred * ys + ym; target = batch['raw']; valid = batch['valid']
        logits[:, ~seen] = -1e30
        dt = (raw[..., 0] - target[..., 0]).abs()
        return {'loss': value,
                'temperatureMaeK': float((dt * valid).sum() / valid.sum()),
                'tray1TemperatureMaeK': float(dt[:, 1].mean()),
                'topVaporLog1pMae': float((raw[:, 0, 2] - target[:, 0, 2]).abs().mean()),
                'condenserWaterLog1pMae': float((raw[:, 0, 3 + 2 * c] - target[:, 0, 3 + 2 * c]).abs().mean()),
                'branchAccuracy': float((logits.argmax(-1) == batch['labels']).float().mean())}


def main():
    p = argparse.ArgumentParser()
    p.add_argument('features'); p.add_argument('out')
    p.add_argument('--seed', type=int, default=17041); p.add_argument('--epochs', type=int, default=160)
    p.add_argument('--width', type=int, default=64); p.add_argument('--u2', action='store_true')
    p.add_argument('--checkpoints', default='80,120,160')
    a = p.parse_args()
    torch.manual_seed(a.seed); np.random.seed(a.seed)
    torch.use_deterministic_algorithms(True, warn_only=True)
    # The fused attention kernels (flash, memory-efficient) have non-deterministic backward passes; the math kernel does not.
    torch.backends.cuda.enable_flash_sdp(False); torch.backends.cuda.enable_mem_efficient_sdp(False)
    device = 'cuda' if torch.cuda.is_available() else 'cpu'
    rows = read(a.features)
    c = len(rows[0]['feedFractions']); node_width = 54 + c + 22
    fold = folds(rows)
    train = [r for r in rows if fold(r) == 'train']; val = [r for r in rows if fold(r) == 'validation']
    subsets = {'all': val, 'axisOnly': [r for r in val if not r.get('injected')], 'injected': [r for r in val if r.get('injected')],
               'cdu': [r for r in val if r['unit'] == 'cdu'], 'vdu': [r for r in val if r['unit'] == 'vdu']}
    norm = normalization(train, node_width, c, a.u2)
    model = ColumnModel(c, width=a.width, extra_global=3 if a.u2 else 0).to(device)
    tt = tensors(train, norm, node_width, c, a.u2, device)
    vts = {k: tensors(v, norm, node_width, c, a.u2, device) for k, v in subsets.items() if v}
    ym, ys = (torch.tensor(norm[k], device=device, dtype=torch.float32) for k in ('ym', 'yscale'))
    seen = torch.zeros(3, dtype=torch.bool, device=device); seen[tt['labels'].unique()] = True
    opt = torch.optim.AdamW(model.parameters(), lr=8e-4, weight_decay=1e-4)
    out = Path(a.out); out.mkdir(parents=True, exist_ok=True)
    (out / 'norm.json').write_text(json.dumps(norm))
    marks = {int(e) for e in a.checkpoints.split(',')}
    report = {'rows': len(rows), 'train': len(train), 'validation': {k: len(v) for k, v in subsets.items()}, 'components': c, 'width': a.width, 'u2': a.u2,
              'seed': a.seed, 'device': torch.cuda.get_device_name(0) if device == 'cuda' else 'cpu', 'parameters': sum(q.numel() for q in model.parameters()), 'branchesSeen': seen.tolist(), 'epochs': []}
    generator = torch.Generator(device='cpu').manual_seed(a.seed)
    for epoch in range(1, a.epochs + 1):
        model.train()
        order = torch.randperm(len(train), generator=generator)
        for start in range(0, len(train), 32):
            idx = order[start:start + 32].to(device)
            batch = {k: v[idx] for k, v in tt.items()}
            pred, logits = model(batch['x'], batch['g'], batch['valid'])
            value = loss(pred, logits, batch, ym, ys)
            opt.zero_grad(); value.backward(); torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0); opt.step()
        if epoch in marks or epoch == a.epochs:
            metrics = {'epoch': epoch} | {k: evaluate(model, v, ym, ys, c, seen) for k, v in vts.items()}
            report['epochs'].append(metrics)
            torch.save({'state': model.state_dict(), 'components': c, 'width': a.width, 'u2': a.u2, 'branchesSeen': seen.tolist()}, out / f'checkpoint-{epoch}.pt')
            print(json.dumps(metrics))
    (out / 'report.json').write_text(json.dumps(report, indent=1))


if __name__ == '__main__':
    main()
