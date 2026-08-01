# Phase 3 (design.md section 9.4) training-stage targets. Every target is a thin wrapper around
# `python3 -m scamshield_ml.<stage>` -- see each script's own module docstring for what it does
# and which judgment calls it had to make. `DATASET` must already be labeled and split (i.e. run
# through label.py then split.py first); there is no real one committed yet (see HANDOFF.md).

PYTHON ?= python3
DATASET ?= data/processed/dataset.jsonl
RUNS_DIR ?= runs
ARTIFACTS_DIR ?= artifacts

.PHONY: teacher distill prune export calibrate evaluate

teacher:
	$(PYTHON) -m scamshield_ml.train_teacher \
		--dataset $(DATASET) \
		--output-dir $(RUNS_DIR)/teacher

distill:
	$(PYTHON) -m scamshield_ml.distill \
		--dataset $(DATASET) \
		--teacher-dir $(RUNS_DIR)/teacher \
		--output-dir $(RUNS_DIR)/student

prune:
	$(PYTHON) -m scamshield_ml.prune_vocab \
		--dataset $(DATASET) \
		--student-dir $(RUNS_DIR)/student \
		--output-dir $(RUNS_DIR)/student_pruned

export:
	$(PYTHON) -m scamshield_ml.export_onnx \
		--student-dir $(RUNS_DIR)/student_pruned \
		--output $(ARTIFACTS_DIR)/model.onnx
	$(PYTHON) -m scamshield_ml.quantize \
		--model $(ARTIFACTS_DIR)/model.onnx \
		--output $(ARTIFACTS_DIR)/model.int8.onnx

calibrate:
	$(PYTHON) -m scamshield_ml.calibrate \
		--dataset $(DATASET) \
		--student-dir $(RUNS_DIR)/student_pruned \
		--state-dict $(RUNS_DIR)/student_pruned/student_pruned.pt \
		--output $(ARTIFACTS_DIR)/meta.json

evaluate:
	$(PYTHON) -m scamshield_ml.evaluate \
		--dataset $(DATASET) \
		--student-dir $(RUNS_DIR)/student_pruned \
		--state-dict $(RUNS_DIR)/student_pruned/student_pruned.pt \
		--onnx-model $(ARTIFACTS_DIR)/model.int8.onnx
