"""Append-only run logging for `ml/EXPERIMENTS.md` (implementation.md Phase 3: "Every run appends
to `ml/EXPERIMENTS.md`: config hash, dataset version, all metrics from `design.md` section 9.5,
wall-clock, artifact size. Append-only -- never edit a past entry.").

One function, `append_run`, used identically by every stage script (`train_teacher.py`,
`distill.py`, ..., `evaluate.py`) so the log format can't drift stage to stage. `config_hash`
exists so two runs with different hyperparameters are distinguishable at a glance without diffing
full config dumps; it hashes the config dict's canonical JSON form (sorted keys), not the raw
dict repr, so key order never changes the hash.
"""

from __future__ import annotations

import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


def config_hash(config: dict[str, Any]) -> str:
    canonical = json.dumps(config, sort_keys=True, default=str)
    return hashlib.sha256(canonical.encode()).hexdigest()[:12]


def append_run(
    log_path: Path,
    *,
    stage: str,
    config: dict[str, Any],
    dataset_version: str,
    metrics: dict[str, Any],
    wall_clock_seconds: float,
    artifact_size_bytes: int | None = None,
    notes: str = "",
) -> None:
    """Appends one Markdown section to `log_path`, creating it with a header if it doesn't
    exist yet. Never truncates or rewrites existing content -- opened in append mode only.
    """
    is_new_file = not log_path.exists()
    log_path.parent.mkdir(parents=True, exist_ok=True)

    lines: list[str] = []
    if is_new_file:
        lines.append("# ScamShield ML — experiment log\n")
        lines.append(
            "\nAppend-only. Every training/eval run gets one section below, in the order it "
            "ran. Never edit or delete a past entry, including failed runs (design.md's own "
            "instruction: never fabricate benchmark numbers, and a truthful failure is still "
            "signal).\n"
        )

    timestamp = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    chash = config_hash(config)
    lines.append(f"\n## {timestamp} — {stage} ({chash})\n")
    lines.append(f"\n- dataset version: `{dataset_version}`")
    lines.append(f"\n- wall-clock: {wall_clock_seconds:.1f}s")
    if artifact_size_bytes is not None:
        lines.append(f"\n- artifact size: {artifact_size_bytes} bytes")
    lines.append("\n- config:\n```json\n" + json.dumps(config, indent=2, sort_keys=True, default=str) + "\n```")
    lines.append("\n- metrics:\n```json\n" + json.dumps(metrics, indent=2, sort_keys=True, default=str) + "\n```")
    if notes:
        lines.append(f"\n- notes: {notes}")
    lines.append("\n")

    with log_path.open("a", encoding="utf-8") as f:
        f.writelines(lines)
