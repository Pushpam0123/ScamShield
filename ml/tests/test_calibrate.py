import json

import torch

from scamshield_ml.calibrate import fit_temperature, nll, write_meta_json


def _overconfident_logits(labels: torch.Tensor, correct_fraction: float, scale: float) -> torch.Tensor:
    """Builds logits that are right about `correct_fraction` of the time but extremely
    confident every time -- exactly the shape design.md says a distilled+quantized model
    produces, and exactly what temperature scaling is supposed to fix.
    """
    n = len(labels)
    logits = torch.zeros(n, 2)
    n_correct = int(n * correct_fraction)
    for i in range(n):
        predicted = labels[i].item() if i < n_correct else 1 - labels[i].item()
        logits[i, predicted] = scale
        logits[i, 1 - predicted] = -scale
    return logits


def test_fit_temperature_reduces_nll_on_overconfident_logits():
    labels = torch.tensor([0, 1, 0, 1, 0, 1, 0, 1, 0, 1])
    logits = _overconfident_logits(labels, correct_fraction=0.7, scale=10.0)

    t = fit_temperature(logits, labels)
    assert nll(logits, labels, t) <= nll(logits, labels, 1.0) + 1e-6
    assert t > 1.0  # overconfident predictions need softening, i.e. T > 1


def test_fit_temperature_stays_positive_and_improves_nll_when_all_predictions_correct():
    labels = torch.tensor([0, 1, 0, 1, 0, 1])
    # every prediction is already correct, just underconfident -- NLL-optimal T here is < 1
    # (sharpen, don't soften), the mirror image of the overconfident case above.
    logits = torch.tensor([[1.0, -1.0], [-1.0, 1.0], [1.0, -1.0], [-1.0, 1.0], [1.0, -1.0], [-1.0, 1.0]])
    t = fit_temperature(logits, labels)
    assert t > 0.0
    assert nll(logits, labels, t) <= nll(logits, labels, 1.0) + 1e-6


def test_fit_temperature_does_not_diverge_on_irregular_logits():
    """Regression test for a real bug found running `calibrate.py` against an actual trained
    model's validation logits: without `line_search_fn="strong_wolfe"`, LBFGS's default line
    search could take one bad step (observed: T~0.89 -> T~3e-8 in a single step) and never
    recover, driving `T` toward 0 and producing garbage (NLL in the hundreds of thousands)
    instead of the true optimum. Irregular, moderate-magnitude, imperfectly-accurate logits --
    not the clean synthetic cases above -- are what triggered it; a coarse grid search gives an
    independent ground truth to compare against.
    """
    torch.manual_seed(0)
    labels = torch.randint(0, 2, (30,))
    logits = torch.randn(30, 2) * 1.5
    # nudge most rows toward being "right" so accuracy isn't coin-flip, matching the messy,
    # mostly-but-not-perfectly-correct shape a real small validation set actually has
    for i in range(30):
        if torch.rand(1).item() < 0.8:
            logits[i, labels[i]] += 1.0

    t = fit_temperature(logits, labels)
    grid_best_nll = min(nll(logits, labels, candidate) for candidate in [0.1, 0.25, 0.5, 0.75, 1.0, 1.5, 2.0, 3.0])

    assert t > 1e-3  # not collapsed toward zero
    assert nll(logits, labels, t) <= grid_best_nll + 1e-3


def test_write_meta_json_roundtrips(tmp_path):
    path = tmp_path / "meta.json"
    write_meta_json(path, 1.8321, nll_before=0.9, nll_after=0.4, val_rows=200)

    data = json.loads(path.read_text())
    assert data["temperature"] == 1.8321
    assert data["val_rows"] == 200
