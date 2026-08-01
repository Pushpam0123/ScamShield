import random

from scamshield_ml.label import label_session, prompt_label, run_audit
from scamshield_ml.schema import Row


def scripted_io(answers: list[str]):
    """A fake input_fn that returns each of `answers` in turn, plus a print_fn that just
    collects everything shown so a test can assert on it without a real terminal.
    """
    it = iter(answers)
    shown: list[str] = []

    def input_fn(prompt: str) -> str:
        shown.append(f"PROMPT: {prompt}")
        return next(it)

    def print_fn(text: str) -> None:
        shown.append(text)

    return input_fn, print_fn, shown


def unlabeled_row(row_id="r1", text="hello") -> Row:
    return Row(id=row_id, text=text, lang="EN", source="test", collected_at="2026-08-01")


def test_prompt_label_genuine_needs_no_category_prompt():
    input_fn, print_fn, shown = scripted_io(["0"])
    label, category = prompt_label(unlabeled_row(), input_fn, print_fn)
    assert (label, category) == (0, "NOT_SCAM")
    assert not any("Category" in line for line in shown)


def test_prompt_label_scam_prompts_for_category():
    input_fn, print_fn, _ = scripted_io(["1", "1"])  # scam, then category #1
    label, category = prompt_label(unlabeled_row(), input_fn, print_fn)
    assert label == 1
    assert category  # the first listed scam category


def test_prompt_label_reprompts_on_invalid_answer():
    input_fn, print_fn, shown = scripted_io(["maybe", "1", "not a number", "1"])
    label, category = prompt_label(unlabeled_row(), input_fn, print_fn)
    assert label == 1
    assert any("Please enter 1, 0, or s" in line for line in shown)
    assert any("Please enter a number" in line for line in shown)


def test_label_session_skips_already_labeled_rows():
    already = Row(id="r1", text="x", lang="EN", source="s", collected_at="d", label=1, category="LOAN_APP")
    input_fn, print_fn, shown = scripted_io([])  # should never be called
    result = label_session([already], labeler="alice", input_fn=input_fn, print_fn=print_fn)
    assert result == [already]
    assert shown == []


def test_label_session_labels_unlabeled_rows_with_provenance():
    input_fn, print_fn, _ = scripted_io(["1", "2"])
    result = label_session([unlabeled_row()], labeler="alice", input_fn=input_fn, print_fn=print_fn, now_fn=lambda: "2026-08-01T00:00:00")
    assert result[0].is_labeled
    assert result[0].labeled_by == "alice"
    assert result[0].labeled_at == "2026-08-01T00:00:00"


def test_label_session_skip_leaves_the_row_unlabeled():
    input_fn, print_fn, _ = scripted_io(["s"])
    result = label_session([unlabeled_row()], labeler="alice", input_fn=input_fn, print_fn=print_fn)
    assert result[0].is_labeled is False


def test_label_session_resumes_a_mixed_batch():
    labeled = Row(id="r1", text="x", lang="EN", source="s", collected_at="d", label=0, category="NOT_SCAM")
    unlabeled = unlabeled_row(row_id="r2")
    input_fn, print_fn, _ = scripted_io(["0"])  # only for r2
    result = label_session([labeled, unlabeled], labeler="alice", input_fn=input_fn, print_fn=print_fn)
    assert result[0] == labeled  # untouched
    assert result[1].is_labeled


def test_run_audit_full_agreement():
    rows = [
        Row(id=f"r{i}", text=f"msg {i}", lang="EN", source="s", collected_at="d", label=0, category="NOT_SCAM")
        for i in range(10)
    ]
    input_fn, print_fn, _ = scripted_io(["0"] * 10)  # always agrees: genuine again
    result = run_audit(rows, fraction=1.0, input_fn=input_fn, print_fn=print_fn, rng=random.Random(0))
    assert result.sampled == 10
    assert result.agreed == 10
    assert result.agreement_rate == 1.0
    assert result.disagreements == []


def test_run_audit_detects_a_disagreement():
    rows = [Row(id="r1", text="x", lang="EN", source="s", collected_at="d", label=0, category="NOT_SCAM")]
    input_fn, print_fn, _ = scripted_io(["1", "1"])  # re-labeled as scam this time
    result = run_audit(rows, fraction=1.0, input_fn=input_fn, print_fn=print_fn, rng=random.Random(0))
    assert result.agreed == 0
    assert result.disagreements[0][0] == "r1"


def test_run_audit_does_not_show_the_original_label():
    rows = [Row(id="r1", text="x", lang="EN", source="s", collected_at="d", label=1, category="LOTTERY_PRIZE")]
    input_fn, print_fn, shown = scripted_io(["1", "1"])
    run_audit(rows, fraction=1.0, input_fn=input_fn, print_fn=print_fn, rng=random.Random(0))
    # The category menu unavoidably lists every category name, LOTTERY_PRIZE included -- that
    # is not a leak. What must never happen is the row's own header (printed first, before any
    # menu) revealing the prior answer.
    row_header = shown[0]
    assert "LOTTERY_PRIZE" not in row_header


def test_run_audit_never_mutates_the_original_rows():
    row = Row(id="r1", text="x", lang="EN", source="s", collected_at="d", label=0, category="NOT_SCAM")
    rows = [row]
    input_fn, print_fn, _ = scripted_io(["1", "1"])
    run_audit(rows, fraction=1.0, input_fn=input_fn, print_fn=print_fn, rng=random.Random(0))
    assert rows[0] == row  # unchanged despite the "disagreement"


def test_run_audit_samples_the_requested_fraction():
    rows = [
        Row(id=f"r{i}", text=f"msg {i}", lang="EN", source="s", collected_at="d", label=0, category="NOT_SCAM")
        for i in range(20)
    ]
    input_fn, print_fn, _ = scripted_io(["0"] * 20)
    result = run_audit(rows, fraction=0.05, input_fn=input_fn, print_fn=print_fn, rng=random.Random(0))
    assert result.sampled == 1  # round(20 * 0.05) == 1
