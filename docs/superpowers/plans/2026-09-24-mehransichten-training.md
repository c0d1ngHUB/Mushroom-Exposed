# Mehransichten-Training Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Einen nichtkommerziellen, reproduzierbaren FungiTastic-M-Prototypen trainieren, der Masken für Hut, Unterseite und Stiel/Ring nachweist und nur bei bestandenen Modell- und Lizenz-Gates nach TFLite exportiert wird.

**Architecture:** Ein Datenadapter wandelt die offiziellen RLE-Teilmasken in die festen lokalen Teilklassen `background,cap,gills,pores,stipe,ring` um und splittet nach Beobachtung. Der Segmentierer, seine Schwellenkalibrierung und der Export sind getrennte Artefakte; kein Kandidat kann ohne manifestierte Herkunft und Gateentscheidung in die Android-App gelangen.

**Tech Stack:** Python 3, PyTorch, torchvision, pandas/pyarrow, NumPy, TensorFlow Lite export path already used by the repository, unittest/pytest.

**Spec:** `../Mushroom-Exposed/docs/superpowers/specs/2026-09-24-mehransichten-prototyp-design.md`

## Global Constraints

- FungiTastic-M nur für den nichtkommerziellen lokalen Prototyp; keine Veröffentlichung eines abgeleiteten Modells ohne erneute Rechteprüfung.
- Datendownload erst nach expliziter Speicher-/Kaggle-Freigabe; der Vollbestand beträgt laut Datenbeschreibung etwa 50 GB.
- Split ist observation-disjoint; mehrere Fotos derselben Beobachtung dürfen nie unterschiedliche Splits erreichen.
- Export schreibt niemals `out/best_model_v2.pt`, `model.tflite` oder App-Assets ohne separaten, akzeptierten Viewpoint-Gatebericht.
- Die GPU-Belegung wird nicht verändert; Training startet erst auf ausdrücklich freigegebener GPU mit ausreichend freiem VRAM.

## Review Focus

- RLE mit mehreren Teilmasken desselben Bildes behält alle Klassen statt die letzte Maske zu überschreiben.
- Gills und pores bleiben getrennte Modellkanäle, mappen aber beide auf die UI-Ansicht Unterseite.
- Eine Beobachtungs-ID erscheint nur in einem Split, auch wenn mehrere Bilder vorhanden sind.
- CC BY-NC-SA 4.0 steht im Run-Manifest; ein fehlendes Manifest blockiert Export und Staging.
- Ein guter Pixel-IoU-Wert ohne niedrige Fehlgrünrate pro Ansicht besteht das Gate nicht.

---

### Task 1: Isolierter Training-Worktree und Provenance-Gate

**Files:**
- Create: `docs/superpowers/notes/fungitastic-m-provenance-2026-09-24.md`
- Create: `src/viewpoint_provenance.py`
- Create: `tests/test_viewpoint_provenance.py`

**Interfaces:**
- `validate_provenance(manifest: dict) -> None` requires `dataset`, `license`, `purpose`, `source_url`, `downloaded_at`.
- Produces `out/viewpoint/provenance.json`, never a model artifact.

- [ ] **Step 1: Write failing license-manifest tests**

```python
def test_noncommercial_fungitastic_manifest_is_accepted(tmp_path):
    manifest = {"dataset": "FungiTastic-M", "license": "CC BY-NC-SA 4.0", "purpose": "noncommercial-prototype", "source_url": "https://www.kaggle.com/datasets/picekl/fungitastic", "downloaded_at": "2026-09-24T00:00:00Z"}
    validate_provenance(manifest)

def test_commercial_or_missing_license_is_rejected():
    with pytest.raises(ValueError): validate_provenance({"purpose": "commercial"})
```

- [ ] **Step 2: Run red**

Run: `pytest tests/test_viewpoint_provenance.py -q`
Expected: import failure because `viewpoint_provenance` is absent.

- [ ] **Step 3: Implement strict manifest validation**

`validate_provenance()` rejects every purpose other than exactly `noncommercial-prototype` and every license other than exactly `CC BY-NC-SA 4.0`; `write_provenance()` writes sorted JSON and a SHA-256 digest of the manifest.

- [ ] **Step 4: Verify and commit**

Run:
```bash
pytest tests/test_viewpoint_provenance.py -q
git add src/viewpoint_provenance.py tests/test_viewpoint_provenance.py docs/superpowers/notes/fungitastic-m-provenance-2026-09-24.md
git commit -m "feat(train): gate viewpoint data provenance"
```
Expected: PASS.

### Task 2: Download- und Schema-Probe

**Files:**
- Create: `src/probe_fungitastic_m.py`
- Create: `tests/test_fungitastic_m_schema.py`
- Create: `out/viewpoint/schema-report.json` (ignored run artifact)

**Interfaces:**
- `load_mask_rows(root: Path) -> pandas.DataFrame` requires `filename`, `label`, `rle`, `height`, `width`.
- `report_raw_labels(rows) -> set[str]` writes the observed, unnormalized label strings; no canonical mapping is inferred.

- [ ] **Step 1: Write failing schema test from a synthetic parquet row**

```python
def test_raw_label_report_is_deterministic():
    rows = pd.DataFrame({"filename": ["a.jpg", "b.jpg"], "label": [["raw-cap"], ["raw-ring", "raw-cap"]], "rle": [[[]], [[]]], "height": [10, 10], "width": [10, 10]})
    assert report_raw_labels(rows) == {"raw-cap", "raw-ring"}
```

- [ ] **Step 2: Run red**

Run: `pytest tests/test_fungitastic_m_schema.py -q`
Expected: import failure because `probe_fungitastic_m` is absent.

- [ ] **Step 3: Obtain explicit download approval, then download only metadata, masks and Mini 300px images**

Run only after user approval:
```bash
python dataset/download.py --metadata --masks --images --subset m --size 300 --save_path data/fungitastic
```
Expected: a local `data/fungitastic/FungiTastic/` tree; record actual bytes, SHA-256 manifests and exact raw labels in `out/viewpoint/schema-report.json`. Create `out/viewpoint/label-map-proposal.json`, but do not train until each raw label has a reviewed canonical mapping or an explicit ignored status.

- [ ] **Step 4: Verify schema and no silent fallback**

Run: `pytest tests/test_fungitastic_m_schema.py -q`
Expected: PASS; missing columns, bad RLE and missing parquet fail loudly. The report alone never guesses that a raw label means `cap`, `gills`, `pores`, `stipe` or `ring`.

- [ ] **Step 5: Commit code only**

```bash
git add src/probe_fungitastic_m.py tests/test_fungitastic_m_schema.py
git commit -m "feat(train): inspect FungiTastic morphology masks"
```

### Task 3: Observation-disjoint part dataset

**Files:**
- Create: `src/viewpoint_dataset.py`
- Create: `tests/test_viewpoint_dataset.py`

**Interfaces:**
- `decode_rle(rle: list[int], height: int, width: int) -> np.ndarray` returns `bool[height,width]`.
- `merge_parts(rows, label_map) -> np.ndarray` returns `uint8[height,width]` with channel IDs 0..5 and rejects raw labels not explicitly mapped or ignored.
- `split_observations(rows, seed: int) -> tuple[pd.DataFrame, pd.DataFrame, pd.DataFrame]` returns disjoint observation IDs.

- [ ] **Step 1: Write failing adapter tests**

```python
def test_gills_and_pores_get_distinct_channels():
    mask = merge_parts(two_part_rows("gills", "pores"))
    assert set(np.unique(mask)) == {0, 2, 3}

def test_observation_split_has_no_photo_leakage():
    train, val, test = split_observations(two_photos_per_observation(), seed=42)
    assert set(train.observation_id).isdisjoint(set(val.observation_id) | set(test.observation_id))
```

- [ ] **Step 2: Run red**

Run: `pytest tests/test_viewpoint_dataset.py -q`
Expected: import failure because the adapter is absent.

- [ ] **Step 3: Implement RLE decoder, explicit overlap rule and split**

Use FungiTastic RLE decoding semantics; reject overlapping non-background masks rather than selecting an arbitrary part. Consume the reviewed `label-map-proposal.json` as an explicit `label_map`; never infer a semantic label from spelling. Derive `observation_id` from official metadata, not from a filename prefix. Persist split IDs under `out/viewpoint/splits/seed-42.json`.

- [ ] **Step 4: Verify and commit**

Run:
```bash
pytest tests/test_viewpoint_dataset.py -q
git add src/viewpoint_dataset.py tests/test_viewpoint_dataset.py
git commit -m "feat(train): build observation-disjoint part dataset"
```
Expected: PASS for multiple masks, overlap, malformed RLE and leakage.

### Task 4: Segmentierer-Spike und trainierbarer Baseline-Lauf

**Files:**
- Create: `src/train_viewpoint.py`
- Create: `src/viewpoint_model.py`
- Create: `tests/test_viewpoint_model.py`
- Create: `out/viewpoint/runs/<run-id>/model_spec.json` (ignored run artifact)

**Interfaces:**
- `PartSegmenter(num_classes: int = 6)` consumes `float32[N,3,300,300]`, produces logits `float32[N,6,300,300]`.
- `evaluate_parts(model, loader) -> dict[str, dict[str, float]]` reports IoU, precision and false-green rate for each part.

- [ ] **Step 1: Write failing shape and metric tests**

```python
def test_part_segmenter_preserves_spatial_shape():
    assert PartSegmenter()(torch.zeros(2, 3, 300, 300)).shape == (2, 6, 300, 300)

def test_false_green_rate_counts_background_as_an_error():
    assert evaluate_masks(predicted_cap_on_background, all_background)["cap"]["false_green_rate"] > 0
```

- [ ] **Step 2: Run red**

Run: `pytest tests/test_viewpoint_model.py -q`
Expected: import failure because model and metrics are absent.

- [ ] **Step 3: Implement a depthwise-separable encoder/decoder**

Create `DepthwiseBlock(in_channels, out_channels, stride)` and `PartSegmenter` with three downsampling blocks, bilinear decoder upsampling and a final `Conv2d(..., 6, 1)`. Use weighted cross-entropy with class weights written to `model_spec.json`; do not collapse gills and pores during training.

- [ ] **Step 4: Run a bounded, explicitly named baseline only after GPU allocation approval**

Run after a GPU with at least 12 GB free is explicitly allocated:
```bash
CUDA_VISIBLE_DEVICES=<allocated-gpu> python src/train_viewpoint.py --data data/fungitastic/FungiTastic --epochs 5 --batch-size 8 --seed 42 --run-id baseline-300-seed42
```
Expected: `out/viewpoint/runs/baseline-300-seed42/` with checkpoint, split hash, class weights, metrics and no mutation of `out/best_model_v2.pt`.

- [ ] **Step 5: Verify and commit code**

Run:
```bash
pytest tests/test_viewpoint_model.py -q
git add src/viewpoint_model.py src/train_viewpoint.py tests/test_viewpoint_model.py
git commit -m "feat(train): train morphology part segmenter"
```

### Task 5: Calibration, TFLite parity and hard gate

**Files:**
- Create: `src/calibrate_viewpoint.py`
- Create: `src/export_viewpoint_tflite.py`
- Create: `src/viewpoint_release_gate.py`
- Create: `tests/test_viewpoint_release_gate.py`
- Create: `out/viewpoint/gate_decision.json` (ignored run artifact)

**Interfaces:**
- `calibrate_thresholds(metrics) -> dict[str, float]` returns a threshold per UI step.
- `decide_viewpoint_release(metrics, parity_error, provenance) -> Decision` returns `accept: bool` and a reason.

- [ ] **Step 1: Write failing gate tests**

```python
def test_missing_provenance_rejects_even_good_iou():
    assert not decide_viewpoint_release(good_metrics, 1e-6, None).accept

def test_high_false_green_rate_rejects_the_matching_step():
    assert not decide_viewpoint_release(metrics_with_false_cap, 1e-6, provenance).accept
```

- [ ] **Step 2: Run red**

Run: `pytest tests/test_viewpoint_release_gate.py -q`
Expected: import failure because gate functions are absent.

- [ ] **Step 3: Implement gate and export to isolated assets**

Export only to `out/viewpoint/runs/<run-id>/viewpoint.tflite`; compare fixed held-out tensor outputs between PyTorch and TFLite; write per-step thresholds and SHA-256 values to `viewpoint_manifest.json`. `decide_viewpoint_release()` rejects missing provenance, any missing part metric, parity error above the pinned test bound, or a false-green rate above the calibrated safety bound.

- [ ] **Step 4: Verify full training suite and gate**

Run:
```bash
pytest -q
python src/viewpoint_release_gate.py out/viewpoint/runs/baseline-300-seed42/viewpoint_manifest.json
```
Expected: all existing and new tests pass; gate emits JSON and does not stage an app asset unless `accept` is true.

- [ ] **Step 5: Commit**

```bash
git add src/calibrate_viewpoint.py src/export_viewpoint_tflite.py src/viewpoint_release_gate.py tests/test_viewpoint_release_gate.py
git commit -m "feat(train): gate noncommercial viewpoint model export"
```
