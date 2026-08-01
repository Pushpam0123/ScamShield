import json

from scamshield_ml.experiment_log import append_run, config_hash


def test_config_hash_is_stable_regardless_of_key_order():
    a = config_hash({"lr": 2e-5, "epochs": 4})
    b = config_hash({"epochs": 4, "lr": 2e-5})
    assert a == b


def test_config_hash_differs_for_different_configs():
    assert config_hash({"lr": 2e-5}) != config_hash({"lr": 5e-5})


def test_append_run_creates_file_with_header(tmp_path):
    log_path = tmp_path / "EXPERIMENTS.md"
    append_run(
        log_path,
        stage="teacher",
        config={"lr": 2e-5, "epochs": 4},
        dataset_version="v0-synthetic",
        metrics={"macro_f1": 0.5},
        wall_clock_seconds=12.3,
    )
    content = log_path.read_text()
    assert content.startswith("# ScamShield ML")
    assert "teacher" in content
    assert "macro_f1" in content


def test_append_run_never_truncates_prior_entries(tmp_path):
    log_path = tmp_path / "EXPERIMENTS.md"
    append_run(log_path, stage="teacher", config={"run": 1}, dataset_version="v0", metrics={}, wall_clock_seconds=1.0)
    first_content = log_path.read_text()

    append_run(log_path, stage="distill", config={"run": 2}, dataset_version="v0", metrics={}, wall_clock_seconds=2.0)
    second_content = log_path.read_text()

    assert second_content.startswith(first_content)
    assert "distill" in second_content


def test_append_run_records_a_failed_run_too(tmp_path):
    log_path = tmp_path / "EXPERIMENTS.md"
    append_run(
        log_path,
        stage="teacher",
        config={"lr": 2e-5},
        dataset_version="v0",
        metrics={"status": "failed", "reason": "loss diverged"},
        wall_clock_seconds=4.0,
        notes="early stop never triggered, aborted manually",
    )
    content = log_path.read_text()
    assert "failed" in content
    assert "loss diverged" in content


def test_metrics_json_block_is_valid_json(tmp_path):
    log_path = tmp_path / "EXPERIMENTS.md"
    metrics = {"macro_f1": 0.876, "per_language": {"EN": 0.9, "HI": 0.81}}
    append_run(log_path, stage="evaluate", config={}, dataset_version="v0", metrics=metrics, wall_clock_seconds=0.5)

    content = log_path.read_text()
    block = content.split("- metrics:\n```json\n")[1].split("\n```")[0]
    assert json.loads(block) == metrics
