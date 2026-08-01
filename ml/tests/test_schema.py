import pytest

from scamshield_ml.schema import CATEGORIES, SCAM_CATEGORIES, Row


def test_unlabeled_row_is_valid():
    row = Row(id="t1", text="hello", lang="EN", source="test", collected_at="2026-08-01")
    assert not row.is_labeled


def test_scam_categories_excludes_not_scam():
    assert "NOT_SCAM" not in SCAM_CATEGORIES
    assert len(SCAM_CATEGORIES) == len(CATEGORIES) - 1


def test_genuine_row_must_be_categorized_not_scam():
    with pytest.raises(ValueError, match="NOT_SCAM"):
        Row(id="t1", text="x", lang="EN", source="s", collected_at="d", label=0, category="FAKE_JOB")


def test_scam_row_cannot_be_categorized_not_scam():
    with pytest.raises(ValueError, match="NOT_SCAM"):
        Row(id="t1", text="x", lang="EN", source="s", collected_at="d", label=1, category="NOT_SCAM")


def test_a_labeled_row_needs_a_category():
    with pytest.raises(ValueError, match="category"):
        Row(id="t1", text="x", lang="EN", source="s", collected_at="d", label=1)


def test_unknown_category_rejected():
    with pytest.raises(ValueError, match="category"):
        Row(id="t1", text="x", lang="EN", source="s", collected_at="d", label=1, category="NOT_A_REAL_CATEGORY")


def test_unknown_language_rejected():
    with pytest.raises(ValueError, match="language"):
        Row(id="t1", text="x", lang="KLINGON", source="s", collected_at="d")


def test_valid_scam_row():
    row = Row(id="t1", text="x", lang="HI_LATN", source="s", collected_at="d", label=1, category="KYC_PHISHING")
    assert row.is_labeled


def test_round_trip_json():
    row = Row(id="t1", text="x", lang="EN", source="s", collected_at="d", label=1, category="LOAN_APP")
    assert Row.from_json_dict(row.to_json_dict()) == row
