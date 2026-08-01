import pytest

from scamshield_ml.schema import Row
from scamshield_ml.split import (
    LeakageError,
    assert_no_leakage,
    assign_splits,
    cluster_rows,
    estimated_jaccard,
    minhash_signature,
    shingles,
)


def labeled_row(row_id: str, text: str, label: int = 1, category: str = "KYC_PHISHING") -> Row:
    return Row(id=row_id, text=text, lang="EN", source="s", collected_at="d", label=label, category=category)


def unlabeled_row(row_id: str, text: str) -> Row:
    return Row(id=row_id, text=text, lang="EN", source="s", collected_at="d")


# --- shingles / minhash primitives ---

def test_shingles_basic():
    assert shingles("hello", k=3) == {"hel", "ell", "llo"}


def test_shingles_shorter_than_k_returns_whole_text():
    assert shingles("hi", k=5) == {"hi"}


def test_shingles_empty_text():
    assert shingles("", k=5) == set()


def test_identical_text_gives_identical_signature():
    a = minhash_signature(shingles("your otp is 4821 do not share it"))
    b = minhash_signature(shingles("your otp is 4821 do not share it"))
    assert a == b
    assert estimated_jaccard(a, b) == 1.0


def test_very_different_texts_give_low_estimated_jaccard():
    a = minhash_signature(shingles("your otp is 4821 do not share it with anyone"))
    b = minhash_signature(shingles("flat forty percent off on your next order today"))
    assert estimated_jaccard(a, b) < 0.3


def test_near_duplicate_texts_give_high_estimated_jaccard():
    a = minhash_signature(shingles("congratulations you have won rs 25000 in the lucky draw"))
    b = minhash_signature(shingles("congratulations you have won rs 26000 in the lucky draw"))
    assert estimated_jaccard(a, b) > 0.8


# --- clustering ---

def test_cluster_rows_groups_near_duplicates():
    rows = [
        labeled_row("r1", "congratulations you have won rs 25000 in the lucky draw"),
        labeled_row("r2", "congratulations you have won rs 26000 in the lucky draw"),
        labeled_row("r3", "your parcel is stuck at customs pay a clearance fee"),
    ]
    clusters = cluster_rows(rows)
    assert len(clusters) == 2
    sizes = sorted(len(c) for c in clusters)
    assert sizes == [1, 2]


def test_cluster_rows_transitively_groups_a_chain():
    # r1~r2 and r2~r3 both exceed the threshold, but r1~r3 alone does not (0.78, verified
    # directly against estimated_jaccard) -- transitivity through the union-find must still
    # land all three in one cluster.
    r1 = "congratulations you have won a lottery prize claim it now by replying yes today"
    r2 = "congratulations you have won a lottery prize claim it soon by replying yes today"
    r3 = "congratulations you have won a lottery prize claim it soon by replying yes now"
    assert estimated_jaccard(minhash_signature(shingles(r1)), minhash_signature(shingles(r3))) < 0.8

    rows = [labeled_row("r1", r1), labeled_row("r2", r2), labeled_row("r3", r3)]
    clusters = cluster_rows(rows)
    assert len(clusters) == 1
    assert len(clusters[0]) == 3


def test_distinct_rows_are_not_clustered():
    rows = [
        labeled_row("r1", "your otp is 482913, do not share it with anyone"),
        labeled_row("r2", "flat forty percent off on your next order, shop now"),
        labeled_row("r3", "your parcel is stuck at customs, pay a clearance fee today"),
    ]
    clusters = cluster_rows(rows)
    assert len(clusters) == 3


# --- split assignment ---

def test_assign_splits_keeps_a_cluster_together():
    rows = [
        labeled_row("r1", "congratulations you have won rs 25000 in the lucky draw"),
        labeled_row("r2", "congratulations you have won rs 26000 in the lucky draw"),
    ] + [labeled_row(f"filler{i}", f"unrelated genuine message number {i} about something else") for i in range(8)]
    result = assign_splits(rows, seed=0)
    by_id = {r.id: r for r in result}
    assert by_id["r1"].split == by_id["r2"].split


def test_assign_splits_only_touches_labeled_rows():
    rows = [labeled_row("r1", "your otp is 482913"), unlabeled_row("r2", "some unlabeled message")]
    result = assign_splits(rows, seed=0)
    by_id = {r.id: r for r in result}
    assert by_id["r1"].split is not None
    assert by_id["r2"].split is None


def test_assign_splits_every_labeled_row_gets_a_split():
    rows = [labeled_row(f"r{i}", f"distinct genuine looking message number {i} today") for i in range(20)]
    result = assign_splits(rows, seed=0)
    assert all(r.split in ("train", "val", "test") for r in result)


def test_assign_splits_roughly_matches_target_ratios_with_many_distinct_rows():
    # With no near-duplicates at all, every "cluster" is a single row, so the greedy
    # bin-packing should track the 70/15/15 target closely. Genuinely varied vocabulary per
    # row matters here -- a template with only a number substituted (e.g. "message number
    # {i}") barely changes the 5-gram shingle set and collapses almost everything into one
    # giant MinHash cluster, which would make this test about bin-packing one big blob, not
    # about the ratio-tracking behavior it's meant to exercise.
    words = (
        "otp verify account bank customs parcel lottery prize loan job investment crypto "
        "electricity disconnect urgent password login refund cashback delivery courier "
        "reward winner claim fee payment transfer suspend block expire renew update apply"
    ).split()
    rows = [
        labeled_row(f"r{i}", " ".join(words[j % len(words)] for j in range(i, i + 7)) + f" case{i}")
        for i in range(100)
    ]
    result = assign_splits(rows, seed=0)
    counts = {"train": 0, "val": 0, "test": 0}
    for r in result:
        counts[r.split] += 1
    assert 60 <= counts["train"] <= 80
    assert 5 <= counts["val"] <= 25
    assert 5 <= counts["test"] <= 25


def test_assign_splits_is_deterministic_for_a_fixed_seed():
    rows = [labeled_row(f"r{i}", f"a distinct message number {i}") for i in range(30)]
    result_a = assign_splits(rows, seed=42)
    result_b = assign_splits(rows, seed=42)
    assert [r.split for r in result_a] == [r.split for r in result_b]


# --- leakage assertion ---

def test_assert_no_leakage_passes_on_a_correct_split():
    rows = [
        labeled_row("r1", "congratulations you have won rs 25000 in the lucky draw"),
        labeled_row("r2", "congratulations you have won rs 26000 in the lucky draw"),
    ] + [labeled_row(f"filler{i}", f"unrelated genuine message number {i} about something else") for i in range(8)]
    split_rows = assign_splits(rows, seed=0)
    assert_no_leakage(split_rows)  # must not raise


def test_assert_no_leakage_raises_when_near_duplicates_span_splits():
    rows = [
        labeled_row("r1", "congratulations you have won rs 25000 in the lucky draw", category="LOTTERY_PRIZE"),
        labeled_row("r2", "congratulations you have won rs 26000 in the lucky draw", category="LOTTERY_PRIZE"),
    ]
    # Force them apart by hand -- this is exactly the mistake the assertion exists to catch,
    # e.g. a hand-edited split file or a bug in a future change to assign_splits.
    from dataclasses import replace as _replace

    bad = [_replace(rows[0], split="train"), _replace(rows[1], split="test")]
    with pytest.raises(LeakageError, match="near-duplicates"):
        assert_no_leakage(bad)


def test_assert_no_leakage_ignores_unlabeled_rows():
    rows = [
        Row(id="r1", text="your otp is 482913", lang="EN", source="s", collected_at="d", split="train"),
        Row(id="r2", text="your otp is 482913", lang="EN", source="s", collected_at="d"),  # no split at all
    ]
    assert_no_leakage(rows)  # must not raise -- r2 has no split to leak into
