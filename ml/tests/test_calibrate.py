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


def test_write_meta_json_roundtrips(tmp_path):
    path = tmp_path / "meta.json"
    write_meta_json(path, 1.8321, nll_before=0.9, nll_after=0.4, val_rows=200)

    data = json.loads(path.read_text())
    assert data["temperature"] == 1.8321
    assert data["val_rows"] == 200
