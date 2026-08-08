from pathlib import Path

import pytest

from scamshield_ml.collect import collect, read_csv_texts, read_plaintext_lines
from scamshield_ml.jsonl_io import read_jsonl, write_jsonl


def test_collect_assigns_sequential_ids_and_scrubs_pii():
    texts = iter(["call 9876543210 now", "hello there"])
    rows = collect(texts, source="testsrc", lang="EN", names_verified=True)
    assert len(rows) == 2
    assert rows[0].id == "sms_testsrc_000000"
    assert rows[1].id == "sms_testsrc_000001"
    assert "<PHONE>" in rows[0].text
    assert "9876543210" not in rows[0].text


def test_collected_rows_are_unlabeled():
    rows = collect(iter(["a message"]), source="s", lang="EN", names_verified=True)
    assert not rows[0].is_labeled
    assert rows[0].label is None
    assert rows[0].category is None


def test_collect_rejects_an_unknown_language():
    with pytest.raises(ValueError, match="language"):
        collect(iter(["x"]), source="s", lang="KLINGON")


def test_collect_gates_unknown_sources_on_the_name_scrub():
    # A source not on the allowlist, without --names-verified, must refuse rather than write
    # possibly-name-bearing rows to disk (pii_scrub.py's name gate).
    with pytest.raises(ValueError, match="names-verified"):
        collect(iter(["Dear Rahul, your account is active"]), source="some_new_portal", lang="EN")


def test_collect_allows_the_public_allowlisted_source_without_the_flag():
    rows = collect(iter(["hello"]), source="uci_sms_spam_collection", lang="EN")
    assert len(rows) == 1


def test_read_plaintext_lines_skips_blank_lines(tmp_path: Path):
    path = tmp_path / "messages.txt"
    path.write_text("first message\n\n  \nsecond message\n", encoding="utf-8")
    assert list(read_plaintext_lines(path)) == ["first message", "second message"]


def test_read_csv_texts_reads_the_named_column(tmp_path: Path):
    path = tmp_path / "messages.csv"
    path.write_text("id,text,other\n1,hello there,x\n2,,y\n3,goodbye,z\n", encoding="utf-8")
    # row 2's text is blank and must be skipped, not yielded as an empty message.
    assert list(read_csv_texts(path)) == ["hello there", "goodbye"]


def test_collect_then_write_then_read_round_trips(tmp_path: Path):
    rows = collect(iter(["one", "two", "three"]), source="s", lang="HI_LATN", names_verified=True)
    output = tmp_path / "out.jsonl"
    write_jsonl(rows, output)
    read_back = read_jsonl(output)
    assert read_back == rows
