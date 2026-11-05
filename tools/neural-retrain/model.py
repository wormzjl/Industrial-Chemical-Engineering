"""Python twin of the shipped anchor-augmented Transformer (V3AnchorTransformerInitializer, feature revision
v3-anchor-augmented-regrouped-2, anchor layout "full"), batch 2026-11-05-neural-retrain, N0.

The Java probe (NeuralRetrainProbe.features) exports the raw global features, node features and native anchor, so the
Python side computes no feature itself: it only normalises, embeds and runs the same layers. The state-dict names equal the
payload's weight names, so a trained module exports with no renaming and a payload loads with no renaming.

Parity check: python -I tools/neural-retrain/model.py <payload.json> <features.jsonl>
"""
import json
import sys

import numpy as np
import torch
from torch import nn

WIDTH, HEADS, FEED_FORWARD = 64, 4, 128


def widths(components):
    glob = 54 + components                    # V3GeneralNeuralFeatures.globalWidth
    node = glob + 22                          # V3GeneralNeuralFeatures.nodeWidth (LOCAL_WIDTH 22 + 3 branch columns) minus the 3 branch columns
    output = 4 * components + 5               # V3FactorizedNeuralFeatures.outputWidth
    return glob, node, output, node + output + 4   # inputs: node, full anchor, branch one-hot (3), anchor availability


class ColumnModel(nn.Module):
    def __init__(self, components, width=WIDTH, feed_forward=FEED_FORWARD):
        super().__init__()
        glob, node, output, inputs = widths(components)
        self.node_width, self.output_width = node, output
        self.embed = nn.Linear(inputs, width)
        self.blocks = nn.ModuleList([nn.TransformerEncoderLayer(width, HEADS, feed_forward, dropout=0.0, activation='gelu',
                                                                batch_first=True, norm_first=True) for _ in range(2)])
        self.output = nn.Sequential(nn.LayerNorm(width), nn.Linear(width, output))
        self.branch = nn.Sequential(nn.Linear(glob, width), nn.GELU(), nn.Linear(width, 3))

    def forward(self, x, g, valid):
        """x: normalised joined node inputs [B, N, inputs]; g: normalised global [B, G]; valid: [B, N] bool."""
        h = self.embed(x)
        for block in self.blocks:
            h = block(h, src_key_padding_mask=~valid)
            h = h.masked_fill(~valid[..., None], 0.0)
        return self.output(h), self.branch(g)


def joined_inputs(row, norm, node_width, output_width):
    """The Java `raw` input for one column: normalised node features, normalised anchor, branch one-hot, availability."""
    nodes = np.asarray(row['nodes'], dtype=np.float64)[:, :node_width]
    anchor = np.asarray(row['anchor'], dtype=np.float64)
    x = (nodes - norm['xm']) / norm['xscale']
    b = (anchor - norm['bm']) / norm['bscale']
    extra = np.zeros((len(nodes), 4))
    extra[:, row['branch']] = 1.0
    extra[:, 3] = 1.0 if row['anchorAvailable'] else 0.0
    return np.concatenate([x, b, extra], axis=1)


def load_payload(path):
    with open(path, encoding='utf-8') as f:
        payload = json.load(f)
    components = len(payload['components'])
    model = ColumnModel(components).double()
    state = {name: torch.tensor(t['values'], dtype=torch.float64).reshape(t['shape']) for name, t in payload['weights'].items()}
    model.load_state_dict(state, strict=True)
    norm = {k: np.asarray(v, dtype=np.float64) for k, v in payload['normalization'].items()}
    return model.eval(), norm


def parity(payload_path, features_path):
    model, norm = load_payload(payload_path)
    worst_raw = worst_logit = 0.0
    rows = [json.loads(line) for line in open(features_path, encoding='utf-8') if line.strip()]
    for row in rows:
        x = torch.tensor(joined_inputs(row, norm, model.node_width, model.output_width))[None]
        g = torch.tensor((np.asarray(row['global']) - norm['gm']) / norm['gscale'])[None]
        valid = torch.ones(x.shape[:2], dtype=torch.bool)
        with torch.no_grad():
            values, logits = model(x, g, valid)
        raw = values[0].numpy() * norm['yscale'] + norm['ym']
        java_raw, java_logits = np.asarray(row['javaRaw']), np.asarray(row['javaBranchLogits'])
        d_raw = float(np.max(np.abs(raw - java_raw) / np.maximum(1.0, np.abs(java_raw))))
        d_logit = float(np.max(np.abs(logits[0].numpy() - java_logits)))
        worst_raw, worst_logit = max(worst_raw, d_raw), max(worst_logit, d_logit)
        print(f"{row['id']}: raw {d_raw:.2e}, logits {d_logit:.2e}")
    parameters = sum(p.numel() for p in model.parameters())
    print(json.dumps({'rows': len(rows), 'parameters': parameters, 'worstRawRelative': worst_raw, 'worstLogitAbsolute': worst_logit,
                      'pass': worst_raw <= 1e-5 and worst_logit <= 1e-5}))
    return worst_raw <= 1e-5 and worst_logit <= 1e-5


if __name__ == '__main__':
    sys.exit(0 if parity(sys.argv[1], sys.argv[2]) else 1)
