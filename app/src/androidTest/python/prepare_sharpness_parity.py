#!/usr/bin/env python3
"""Prepare ignored val/test images and Python sharpness values for Android.

The output is local test input only. It binds every frame to the persisted
Seed-42 operating NPZ metadata without copying licensed images into Git.
"""

from __future__ import annotations

import hashlib
import json
import os
import shutil
import sys
from pathlib import Path

import numpy as np


TRAIN_ROOT = Path("/home/m3kky/projects/Mushroom-Exposed-training/.worktrees/feat-viewpoint")
TRAIN_DATA = Path("/home/m3kky/projects/Mushroom-Exposed-training/data/fungitastic")
SPLITS_PATH = TRAIN_ROOT / "out/viewpoint/splits/seed-42.json"
APP_ROOT = Path(__file__).resolve().parents[4]
ASSET_ROOT = APP_ROOT / "app/src/androidTest/assets/sharpness_parity"
MODEL_PATH = APP_ROOT / "app/src/main/assets/viewpoint.tflite"

sys.path.insert(0, str(TRAIN_ROOT))
from src.viewpoint_data import images_dir_for, load_split_rows  # noqa: E402


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with Path(path).open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def build_frame_entries(
    *,
    split: str,
    filenames,
    observation_ids,
    archive_observation_ids: np.ndarray,
    sharpness: np.ndarray,
    images_dir: Path,
) -> list[dict]:
    """Bind ordered source rows to the exact ordered NPZ sharpness vector."""
    names = [str(value) for value in filenames]
    observations = [str(value).strip() for value in observation_ids]
    archived = [str(value).strip() for value in archive_observation_ids.tolist()]
    values = np.asarray(sharpness, dtype=np.float64)
    if split not in {"val", "test"}:
        raise ValueError(f"unsupported split: {split}")
    if observations != archived:
        raise ValueError("NPZ observation order does not match persisted split rows")
    if len(names) != len(observations) or values.shape != (len(names),):
        raise ValueError("filename, observation and sharpness lengths differ")
    if not np.isfinite(values).all() or np.any((values < 0) | (values > 1)):
        raise ValueError("sharpness must be finite and in [0,1]")

    frames = []
    for filename, observation_id, value in zip(names, observations, values):
        source = Path(images_dir) / filename
        if not source.is_file():
            raise FileNotFoundError(source)
        frames.append({
            "filename": filename,
            "observation_id": observation_id,
            "split": split,
            "python_sharpness": float(value),
            "image_sha256": sha256_file(source),
        })
    return frames


def split_order_indices(observation_ids, split_observation_ids) -> list[int]:
    """Return stable row indices ordered by the persisted split ID list."""
    order = {str(value): index for index, value in enumerate(split_observation_ids)}
    observations = [str(value).strip() for value in observation_ids]
    if len(order) != len(split_observation_ids):
        raise ValueError("persisted split contains duplicate observation IDs")
    missing = sorted(set(observations) - set(order))
    if missing:
        raise ValueError(f"rows contain observations outside persisted split: {missing[:5]}")
    return sorted(range(len(observations)), key=lambda index: order[observations[index]])


def images_manifest_sha256(images_dir: Path, filenames) -> str:
    """Match src.measure_viewpoint_views._sha256_of_read_images exactly."""
    digest = hashlib.sha256()
    for raw in filenames:
        filename = str(raw)
        digest.update(filename.encode("utf-8"))
        digest.update(sha256_file(Path(images_dir) / filename).encode("utf-8"))
    return digest.hexdigest()


def main() -> None:
    images_dir = images_dir_for(TRAIN_DATA, "300p")
    model_sha256 = sha256_file(MODEL_PATH)
    split_manifest_sha256 = sha256_file(SPLITS_PATH)
    persisted_splits = json.loads(SPLITS_PATH.read_text(encoding="utf-8"))["splits"]
    frames: list[dict] = []
    split_bindings = {}

    for split in ("val", "test"):
        rows = load_split_rows(TRAIN_DATA, split, SPLITS_PATH)
        indices = split_order_indices(rows["observationID"], persisted_splits[split])
        rows = rows.iloc[indices].reset_index(drop=True)
        npz_path = TRAIN_ROOT / f"out/viewpoint/operating-skip-300-seed42-{split}.npz"
        meta_path = npz_path.with_suffix(".json")
        metadata = json.loads(meta_path.read_text(encoding="utf-8"))
        with np.load(npz_path, allow_pickle=False) as archive:
            split_frames = build_frame_entries(
                split=split,
                filenames=rows["filename"],
                observation_ids=rows["observationID"],
                archive_observation_ids=archive["observation_ids"],
                sharpness=archive["sharpness"],
                images_dir=images_dir,
            )

        measured_images_sha = images_manifest_sha256(images_dir, rows["filename"])
        required = {
            "npz_sha256": sha256_file(npz_path),
            "images_manifest_sha256": measured_images_sha,
            "model_sha256": model_sha256,
            "splits_sha256": split_manifest_sha256,
        }
        for key, value in required.items():
            if metadata.get(key) != value:
                raise ValueError(
                    f"{split} metadata {key} mismatch: {metadata.get(key)!r} != {value!r}"
                )
        if metadata.get("frames") != len(split_frames):
            raise ValueError(f"{split} metadata frame count mismatch")
        split_bindings[split] = {"frames": len(split_frames), **required}
        frames.extend(split_frames)

    filenames = [frame["filename"] for frame in frames]
    if len(filenames) != len(set(filenames)):
        raise ValueError("val/test filenames must be unique")

    images_target = ASSET_ROOT / "images"
    images_target.mkdir(parents=True, exist_ok=True)
    expected_names = set(filenames)
    for stale in images_target.iterdir():
        if stale.is_file() and stale.name not in expected_names:
            stale.unlink()
    for index, frame in enumerate(frames, 1):
        source = images_dir / frame["filename"]
        target = images_target / frame["filename"]
        if not target.is_file() or sha256_file(target) != frame["image_sha256"]:
            shutil.copyfile(source, target)
        if index % 500 == 0:
            print(f"prepared {index}/{len(frames)} frames")

    manifest = {
        "schema_version": 1,
        "report_kind": "sharpness_parity_input",
        "release_eligible": False,
        "run_id": "skip-300-seed42",
        "min_sharpness": 0.05,
        "model_sha256": model_sha256,
        "splits": split_bindings,
        "frames": frames,
    }
    ASSET_ROOT.mkdir(parents=True, exist_ok=True)
    temp = ASSET_ROOT / "manifest.json.tmp"
    temp.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    os.replace(temp, ASSET_ROOT / "manifest.json")
    print(
        f"wrote {len(frames)} frames to {ASSET_ROOT}; "
        f"manifest sha256={sha256_file(ASSET_ROOT / 'manifest.json')}"
    )


if __name__ == "__main__":
    main()
