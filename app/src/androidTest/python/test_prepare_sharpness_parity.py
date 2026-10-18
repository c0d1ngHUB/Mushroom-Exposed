"""Tests for the full val/test sharpness parity input manifest."""

import importlib.util
from pathlib import Path

import numpy as np
import pytest


SCRIPT = Path(__file__).with_name("prepare_sharpness_parity.py")
SPEC = importlib.util.spec_from_file_location("prepare_sharpness_parity", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
prepare = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(prepare)


def test_build_frame_entries_binds_order_bytes_and_python_sharpness(tmp_path):
    (tmp_path / "a.jpg").write_bytes(b"first-image")
    (tmp_path / "b.jpg").write_bytes(b"second-image")

    frames = prepare.build_frame_entries(
        split="val",
        filenames=["a.jpg", "b.jpg"],
        observation_ids=["obs-a", "obs-b"],
        archive_observation_ids=np.array(["obs-a", "obs-b"]),
        sharpness=np.array([0.125, 0.75]),
        images_dir=tmp_path,
    )

    assert [(frame["filename"], frame["observation_id"], frame["split"])
            for frame in frames] == [
        ("a.jpg", "obs-a", "val"),
        ("b.jpg", "obs-b", "val"),
    ]
    assert [frame["python_sharpness"] for frame in frames] == [0.125, 0.75]
    assert all(len(frame["image_sha256"]) == 64 for frame in frames)


def test_build_frame_entries_rejects_npz_row_mismatch(tmp_path):
    (tmp_path / "a.jpg").write_bytes(b"image")

    with pytest.raises(ValueError, match="observation order"):
        prepare.build_frame_entries(
            split="test",
            filenames=["a.jpg"],
            observation_ids=["obs-a"],
            archive_observation_ids=np.array(["other"]),
            sharpness=np.array([0.25]),
            images_dir=tmp_path,
        )


def test_split_order_indices_follow_manifest_and_preserve_frame_order():
    indices = prepare.split_order_indices(
        observation_ids=["obs-b", "obs-a", "obs-b", "obs-c"],
        split_observation_ids=["obs-a", "obs-b", "obs-c"],
    )

    assert indices == [1, 0, 2, 3]
