#!/usr/bin/env python3
import json, sys, hashlib, shutil
from pathlib import Path
import numpy as np
from PIL import Image
import torch

ROOT = Path('/home/m3kky/projects/Mushroom-Exposed-training/.worktrees/feat-viewpoint')
DATA = Path('/home/m3kky/projects/Mushroom-Exposed-training/data/fungitastic/FungiTastic-Mini')
sys.path.insert(0, str(ROOT))

from src.viewpoint_model import PartSegmenter, PART_NAMES, to_app_nhwc
from src.measure_viewpoint_views import sharpness_for_rgb224
from src.viewpoint_operating_point import frame_flags

# v2 operating point from Task 3 report
tau_by_channel = {"cap":0.3, "gills":0.5, "pores":0.5, "stipe":0.3, "ring":0.3}
min_coverage_by_channel = {"cap":0.002, "gills":0.01, "pores":0.003, "stipe":0.001, "ring":0.001}
dominance_by_view = {"cap":1.0, "underside":0.0, "stipe_ring":0.0}
min_sharpness = 0.05

filename = '0-2237852161.JPG'
img_path = DATA / 'train/300p' / filename
img = Image.open(img_path).convert('RGB')
orig_size = img.size
resized = img.resize((300,300), Image.BILINEAR)
arr = np.asarray(resized, dtype=np.float32) / 255.0
tensor = torch.from_numpy(arr).permute(2,0,1).unsqueeze(0)

model = PartSegmenter()
ckpt = torch.load(ROOT / 'out/viewpoint/runs/skip-300-seed42/checkpoint.pt', map_location='cpu', weights_only=False)
model.load_state_dict(ckpt)
model.eval()
with torch.no_grad():
    logits = model(tensor)
probs_nhwc = to_app_nhwc(logits).numpy()
probs = probs_nhwc[0]

taus_needed = set(tau_by_channel.values())
coverages = {}
for tau in taus_needed:
    c = np.zeros(len(PART_NAMES))
    total = probs.shape[0] * probs.shape[1]
    for i, name in enumerate(PART_NAMES):
        c[i] = np.count_nonzero(probs[:,:,i] > tau) / total
    coverages[tau] = c.reshape(1, -1)

sharp = sharpness_for_rgb224(np.asarray(resized))

flags = frame_flags(
    coverages=coverages,
    tau_by_channel=tau_by_channel,
    min_coverage_by_channel=min_coverage_by_channel,
    dominance_by_view=dominance_by_view,
    sharpness=np.array([sharp]),
    min_sharpness=min_sharpness,
)

print('filename', filename)
print('orig_size', orig_size)
print('probs shape', probs.shape)
print('sharpness', sharp)
print('channel coverages (tau-specific):')
for name in PART_NAMES:
    tau = tau_by_channel.get(name, 0.5)
    idx = PART_NAMES.index(name)
    print(f'  {name} tau={tau} coverage={coverages[tau][0,idx]:.6f}')
print('flags', {k: bool(v[0]) for k,v in flags.items()})

out_dir = Path('/home/m3kky/projects/Mushroom-Exposed/.worktrees/feat-viewpoint-operating-point/app/src/androidTest/assets')
out_dir.mkdir(parents=True, exist_ok=True)
shutil.copy(img_path, out_dir / 'viewpoint_real_frame.png')
print('copied to', out_dir / 'viewpoint_real_frame.png')
print('asset sha256', hashlib.sha256((out_dir/'viewpoint_real_frame.png').read_bytes()).hexdigest())
