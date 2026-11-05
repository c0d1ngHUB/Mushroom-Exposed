#!/usr/bin/env python3
"""Reproducible Python golden for the five Android runtime parity frames.

All frames come from FungiTastic-Mini train/300p (CC BY-NC-SA 4.0) and are
locally excluded from the MIT app repo. Run this script in the training
worktree to (re-)generate the numbers pinned in ViewpointRuntimeTest.
"""
import json, sys, hashlib, shutil
from pathlib import Path
import numpy as np
from PIL import Image
import torch

ROOT = Path('/home/m3kky/projects/Mushroom-Exposed-training/.worktrees/feat-viewpoint')
DATA = Path('/home/m3kky/projects/Mushroom-Exposed-training/data/fungitastic/FungiTastic-Mini')
SPLIT_MANIFEST = ROOT / 'out/viewpoint/splits/seed-42.json'
sys.path.insert(0, str(ROOT))

from src.viewpoint_model import PartSegmenter, PART_NAMES, to_app_nhwc
from src.measure_viewpoint_views import sharpness_for_rgb224
from src.viewpoint_operating_point import frame_flags

# v2 operating point from Task 3 report.
tau_by_channel = {"cap": 0.3, "gills": 0.5, "pores": 0.5, "stipe": 0.3, "ring": 0.3}
min_coverage_by_channel = {"cap": 0.002, "gills": 0.01, "pores": 0.003, "stipe": 0.001, "ring": 0.001}
dominance_by_view = {"cap": 1.0, "underside": 0.0, "stipe_ring": 0.0}
min_sharpness = 0.05

FRAMES = [
    {
        # Positive parity frame: all three views accepted.
        "source": DATA / 'train/300p' / '0-2237852161.JPG',
        "asset": 'viewpoint_real_frame.jpg',
        "observation_id": '2237852161',
    },
    {
        # Low sharpness: every view rejected by the global sharpness floor.
        "source": DATA / 'train/300p' / '3-3126950317.JPG',
        "asset": '3-3126950317_frame.jpg',
        "observation_id": '3126950317',
    },
    {
        # Dominance/floor rejection: cap rejected because gills dominates and stipe_ring floor not met.
        "source": DATA / 'train/300p' / '0-3313851318.JPG',
        "asset": '0-3313851318_frame.jpg',
        "observation_id": '3313851318',
    },
    {
        # Val boundary case just above the unchanged 0.05 sharpness floor.
        "source": DATA / 'train/300p' / '1-3329734337.JPG',
        "asset": '1-3329734337_frame.jpg',
        "observation_id": '3329734337',
    },
    {
        # Val boundary case just below the unchanged 0.05 sharpness floor.
        "source": DATA / 'train/300p' / '0-3008838331.JPG',
        "asset": '0-3008838331_frame.jpg',
        "observation_id": '3008838331',
    },
]


def source_split_for_observation(observation_id: str) -> str:
    """Resolve provenance from the persisted Seed-42 split manifest."""
    splits = json.loads(SPLIT_MANIFEST.read_text())["splits"]
    matches = [name for name, observation_ids in splits.items()
               if observation_id in observation_ids]
    if len(matches) != 1:
        raise ValueError(
            f"observation {observation_id} must occur in exactly one split; got {matches}"
        )
    return matches[0]


def sharpness_for_source_image(image: Image.Image) -> float:
    """Measure the original decoded frame through the app-equivalent 224 path."""
    return sharpness_for_rgb224(np.asarray(image.convert('RGB')))


def analyze(path: Path):
    img = Image.open(path).convert('RGB')
    orig_size = img.size
    resized = img.resize((300, 300), Image.BILINEAR)
    arr = np.asarray(resized, dtype=np.float32) / 255.0
    tensor = torch.from_numpy(arr).permute(2, 0, 1).unsqueeze(0)
    model = PartSegmenter()
    ckpt = torch.load(ROOT / 'out/viewpoint/runs/skip-300-seed42/checkpoint.pt', map_location='cpu', weights_only=False)
    model.load_state_dict(ckpt)
    model.eval()
    with torch.no_grad():
        logits = model(tensor)
    probs_nhwc = to_app_nhwc(logits).numpy()
    probs = probs_nhwc[0]

    distinct_taus = sorted(set(tau_by_channel.values()))
    coverages = {}
    total = probs.shape[0] * probs.shape[1]
    for tau in distinct_taus:
        c = np.zeros(len(PART_NAMES))
        for i, name in enumerate(PART_NAMES):
            c[i] = np.count_nonzero(probs[:, :, i] > tau) / total
        coverages[tau] = c.reshape(1, -1)

    sharp = sharpness_for_source_image(img)
    flags = frame_flags(
        coverages=coverages,
        tau_by_channel=tau_by_channel,
        min_coverage_by_channel=min_coverage_by_channel,
        dominance_by_view=dominance_by_view,
        sharpness=np.array([sharp]),
        min_sharpness=min_sharpness,
    )

    channel_coverages = {}
    for name in PART_NAMES:
        if name == 'background':
            continue
        tau = tau_by_channel.get(name, 0.5)
        idx = PART_NAMES.index(name)
        channel_coverages[name] = float(coverages[tau][0, idx])
    return {
        "orig_size": orig_size,
        "probs_shape": probs.shape,
        "sharpness": float(sharp),
        "channel_coverages": channel_coverages,
        "flags": {k: bool(v[0]) for k, v in flags.items()},
    }


def main():
    out_dir = Path('/home/m3kky/projects/Mushroom-Exposed/.worktrees/feat-viewpoint-operating-point/app/src/androidTest/assets')
    out_dir.mkdir(parents=True, exist_ok=True)
    report = {
        "checkpoint": str(ROOT / 'out/viewpoint/runs/skip-300-seed42/checkpoint.pt'),
        "model_run_id": "skip-300-seed42",
        "tau_by_channel": tau_by_channel,
        "min_coverage_by_channel": min_coverage_by_channel,
        "dominance_by_view": dominance_by_view,
        "min_sharpness": min_sharpness,
        "frames": {},
    }
    for entry in FRAMES:
        src = entry["source"]
        asset_name = entry["asset"]
        print(f'\n--- {asset_name} ---')
        result = analyze(src)
        print(json.dumps(result, indent=2))
        report["frames"][asset_name] = {
            "source": str(src),
            "source_split": source_split_for_observation(entry["observation_id"]),
            "observation_id": entry["observation_id"],
            **result,
            "sha256": hashlib.sha256(src.read_bytes()).hexdigest(),
        }
        dst = out_dir / asset_name
        shutil.copy(src, dst)
        print(f'copied to {dst}')
        print(f'asset sha256 {hashlib.sha256(dst.read_bytes()).hexdigest()}')

    print('\n=== full report ===')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
