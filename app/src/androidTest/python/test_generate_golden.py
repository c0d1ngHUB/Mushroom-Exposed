"""Regression tests for the Python side of Android sharpness parity."""

import importlib.util
from pathlib import Path

import numpy as np
from PIL import Image
import pytest


SCRIPT = Path(__file__).with_name("generate_golden.py")
SPEC = importlib.util.spec_from_file_location("generate_golden", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
generate_golden = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(generate_golden)


def test_source_sharpness_skips_model_size_intermediate_resize():
    """A non-square source must go straight to the app's 224-square path."""
    height, width = 188, 300
    pixels = np.full((height, width, 3), 100, dtype=np.uint8)
    pixels[30:158, 50:250] = 180
    for y in range(35, 155, 8):
        pixels[y:y + 2, 60:240] = 220

    measured = generate_golden.sharpness_for_source_image(Image.fromarray(pixels))

    assert measured == pytest.approx(0.1435049049800002, abs=1e-12)

