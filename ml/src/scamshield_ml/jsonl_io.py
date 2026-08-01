"""Reading and writing the shared `Row` schema as JSONL -- one small module so `collect.py`,
`label.py`, and (later) `split.py` don't each reimplement it slightly differently.
"""

from __future__ import annotations

import json
from pathlib import Path

from .schema import Row


def read_jsonl(path: Path) -> list[Row]:
    rows = []
    with path.open(encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                rows.append(Row.from_json_dict(json.loads(line)))
    return rows


def write_jsonl(rows: list[Row], path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as f:
        for row in rows:
            f.write(json.dumps(row.to_json_dict(), ensure_ascii=False) + "\n")
