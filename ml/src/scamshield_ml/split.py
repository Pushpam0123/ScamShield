"""Phase 2.4 (design.md section 9.3): "Split by source cluster and by template family, never
randomly. Augmented variants must land in the same split as their parent. Random splitting
leaks near-duplicates across train/test and inflates F1 by 5-10 points. Ratio 70/15/15... Must
assert zero near-duplicate overlap across splits (MinHash, Jaccard > 0.8 -> same split). Fail
loudly."

**"Template family" here means MinHash-estimated near-duplicates specifically**, not an
explicit parent/child link -- `collect.py` does not yet produce augmented variants with a
tracked parent id (that generation step is later pipeline work, not one of Phase 2's four
build items), so there is nothing to link explicitly yet. Near-duplicate clustering by content
similarity is the practical mechanism available today and is what actually prevents the
leakage design.md warns about; if/when augmentation gains real parent tracking, add an explicit
union alongside the similarity-based one in `cluster_rows` rather than replacing it.

MinHash is used, not exact all-pairs Jaccard, to match design.md's own words and because a real
run needs to scale past what naive O(n^2) set-intersection comparison could handle -- comparing
compact integer signatures is cheap even though the clustering pass below is still O(n^2) in
the *number of comparisons*. At the row counts this tool has actually been tested at, that is
fine; if the corpus grows into the tens of thousands, replace the all-pairs pass with LSH
banding on these same signatures rather than changing the similarity metric itself.
"""

from __future__ import annotations

import argparse
import hashlib
import random
import sys
from collections import Counter
from dataclasses import replace
from pathlib import Path

from .jsonl_io import read_jsonl, write_jsonl
from .schema import Row

SHINGLE_SIZE = 5
NUM_HASHES = 64
JACCARD_THRESHOLD = 0.8
SPLIT_RATIOS = {"train": 0.70, "val": 0.15, "test": 0.15}


class LeakageError(Exception):
    """Raised when two rows in different splits are estimated near-duplicates. Deliberately a
    hard failure, not a warning -- design.md's own words are "fail loudly": split leakage
    silently inflates eval metrics (design.md's own figure: 5-10 F1 points) and is exactly the
    kind of bug that looks like a great model right up until production.
    """


def shingles(text: str, k: int = SHINGLE_SIZE) -> set[str]:
    normalized = text.lower()
    if len(normalized) < k:
        return {normalized} if normalized else set()
    return {normalized[i : i + k] for i in range(len(normalized) - k + 1)}


def minhash_signature(shingle_set: set[str], num_hashes: int = NUM_HASHES) -> tuple[int, ...]:
    """One deterministic hash "function" per signature slot, built by salting a stable hash
    (`hashlib.md5`, not Python's built-in `hash()`, which is randomly salted per process and
    would make signatures incomparable across runs) with the slot index.
    """
    if not shingle_set:
        return tuple(0 for _ in range(num_hashes))
    return tuple(
        min(int(hashlib.md5(f"{i}:{s}".encode()).hexdigest(), 16) for s in shingle_set)
        for i in range(num_hashes)
    )


def estimated_jaccard(sig_a: tuple[int, ...], sig_b: tuple[int, ...]) -> float:
    matches = sum(1 for a, b in zip(sig_a, sig_b) if a == b)
    return matches / len(sig_a)


class _UnionFind:
    def __init__(self, n: int):
        self.parent = list(range(n))

    def find(self, x: int) -> int:
        while self.parent[x] != x:
            self.parent[x] = self.parent[self.parent[x]]
            x = self.parent[x]
        return x

    def union(self, a: int, b: int) -> None:
        ra, rb = self.find(a), self.find(b)
        if ra != rb:
            self.parent[ra] = rb


def cluster_rows(rows: list[Row], threshold: float = JACCARD_THRESHOLD) -> list[list[int]]:
    """Groups row *indices* that must stay together: any two rows whose MinHash-estimated
    Jaccard similarity exceeds [threshold] are unioned into the same cluster, transitively (if
    A~B and B~C, all three land together even if A and C alone are below threshold) -- exactly
    what a naive per-pair split assignment would get wrong.
    """
    signatures = [minhash_signature(shingles(r.text)) for r in rows]
    uf = _UnionFind(len(rows))
    for i in range(len(rows)):
        for j in range(i + 1, len(rows)):
            if estimated_jaccard(signatures[i], signatures[j]) > threshold:
                uf.union(i, j)

    groups: dict[int, list[int]] = {}
    for i in range(len(rows)):
        groups.setdefault(uf.find(i), []).append(i)
    return list(groups.values())


def assign_splits(
    rows: list[Row],
    ratios: dict[str, float] = SPLIT_RATIOS,
    threshold: float = JACCARD_THRESHOLD,
    seed: int = 0,
) -> list[Row]:
    """Assigns `split` to every *labeled* row (unlabeled rows pass through unchanged -- there
    is nothing to split yet). Whole clusters (design.md's "source cluster"/"template family")
    move together via a greedy bin-packing pass: process clusters largest-first, each time
    dropping the whole cluster into whichever split is currently furthest below its target
    share. This keeps every near-duplicate group intact while still tracking the target ratios
    reasonably closely -- an exact 70/15/15 split is not achievable once whole clusters (which
    vary in size) are the unit of assignment, and design.md does not ask for exact.
    """
    labeled_indices = [i for i, r in enumerate(rows) if r.is_labeled]
    labeled_rows = [rows[i] for i in labeled_indices]

    clusters = cluster_rows(labeled_rows, threshold)

    rng = random.Random(seed)
    order = list(range(len(clusters)))
    rng.shuffle(order)  # breaks ties among same-size clusters without favoring input order
    order.sort(key=lambda idx: -len(clusters[idx]))

    total = len(labeled_rows)
    targets = {name: ratio * total for name, ratio in ratios.items()}
    counts = dict.fromkeys(ratios, 0)
    split_by_labeled_index: dict[int, str] = {}

    for idx in order:
        cluster = clusters[idx]
        # Fractional deficit (how far below its OWN target a split is, as a proportion),
        # not absolute deficit -- comparing absolute counts directly is wrong when targets
        # differ in size (70 vs. 15 here): train's absolute deficit starts at 70 and stays
        # the largest number for the first ~55 assignments regardless of val/test's own
        # state, so a plain "largest absolute deficit wins" greedy starves val and test
        # almost completely. Normalizing by each split's own target puts all three on the
        # same 0..1 scale, which is what makes the greedy choice actually track the ratios.
        deficits = {name: (targets[name] - counts[name]) / targets[name] for name in ratios}
        chosen = max(deficits, key=lambda name: deficits[name])
        for local_row_idx in cluster:
            split_by_labeled_index[local_row_idx] = chosen
        counts[chosen] += len(cluster)

    result = list(rows)
    for local_idx, original_idx in enumerate(labeled_indices):
        result[original_idx] = replace(rows[original_idx], split=split_by_labeled_index[local_idx])
    return result


def assert_no_leakage(rows: list[Row], threshold: float = JACCARD_THRESHOLD) -> None:
    """The hard, fail-loud check design.md's own words demand -- run this on the final split
    output before it is trusted for training, not only rely on [assign_splits] having done the
    right thing (a hand-edited split file, or a future change to the assignment algorithm,
    should not be able to introduce leakage silently).
    """
    split_rows = [r for r in rows if r.split is not None]
    signatures = [minhash_signature(shingles(r.text)) for r in split_rows]
    for i in range(len(split_rows)):
        for j in range(i + 1, len(split_rows)):
            if split_rows[i].split == split_rows[j].split:
                continue
            if estimated_jaccard(signatures[i], signatures[j]) > threshold:
                raise LeakageError(
                    f"{split_rows[i].id} (split={split_rows[i].split}) and {split_rows[j].id} "
                    f"(split={split_rows[j].split}) are estimated near-duplicates "
                    f"(Jaccard > {threshold}) but landed in different splits"
                )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--input", required=True, type=Path)
    parser.add_argument("--output", type=Path, help="Defaults to overwriting --input")
    parser.add_argument("--seed", type=int, default=0)
    parser.add_argument("--jaccard-threshold", type=float, default=JACCARD_THRESHOLD)
    args = parser.parse_args(argv)

    rows = read_jsonl(args.input)
    split_rows = assign_splits(rows, threshold=args.jaccard_threshold, seed=args.seed)
    assert_no_leakage(split_rows, threshold=args.jaccard_threshold)  # before writing anything

    counts = Counter(r.split for r in split_rows if r.split is not None)
    unlabeled = sum(1 for r in split_rows if r.split is None)
    print(
        f"train={counts['train']} val={counts['val']} test={counts['test']} unlabeled={unlabeled}",
        file=sys.stderr,
    )

    write_jsonl(split_rows, args.output or args.input)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
