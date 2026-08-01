import torch

from scamshield_ml.distill import (
    HiddenProjector,
    distillation_loss,
    distill,
    hidden_mse_loss,
    init_student_from_teacher,
    project_tensor,
    student_config,
)
from scamshield_ml.model import ScamShieldClassifier

from .conftest import tiny_teacher_config
from .test_train_teacher import synthetic_dataset


def test_project_tensor_noop_when_shape_already_matches():
    t = torch.randn(5, 8)
    assert torch.equal(project_tensor(t, (5, 8)), t)


def test_project_tensor_resizes_last_dim():
    t = torch.randn(5, 8)
    projected = project_tensor(t, (5, 4))
    assert projected.shape == (5, 4)


def test_project_tensor_resizes_1d():
    t = torch.randn(768)
    projected = project_tensor(t, (384,))
    assert projected.shape == (384,)


def test_project_tensor_rejects_rank_mismatch():
    t = torch.randn(5, 8)
    try:
        project_tensor(t, (5,))
        assert False, "expected ValueError"
    except ValueError:
        pass


def test_init_student_from_teacher_copies_projected_embeddings(tiny_tokenizer):
    teacher_cfg = tiny_teacher_config(tiny_tokenizer.vocab_size)
    student_cfg = student_config(
        tiny_tokenizer.vocab_size, hidden_size=4, num_hidden_layers=4, num_attention_heads=4, intermediate_size=16
    )
    teacher = ScamShieldClassifier.from_config(teacher_cfg)
    student = ScamShieldClassifier.from_config(student_cfg)

    before = student.encoder.embeddings.word_embeddings.weight.clone()
    init_student_from_teacher(student, teacher)
    after = student.encoder.embeddings.word_embeddings.weight

    assert after.shape == before.shape
    assert not torch.equal(before, after)


def test_init_student_from_teacher_rejects_layer_count_mismatch(tiny_tokenizer):
    teacher_cfg = tiny_teacher_config(tiny_tokenizer.vocab_size)
    student_cfg = student_config(
        tiny_tokenizer.vocab_size, hidden_size=4, num_hidden_layers=4, num_attention_heads=4, intermediate_size=16
    )
    teacher = ScamShieldClassifier.from_config(teacher_cfg)
    student = ScamShieldClassifier.from_config(student_cfg)

    try:
        init_student_from_teacher(student, teacher, teacher_layer_indices=(3, 6, 9))
        assert False, "expected ValueError"
    except ValueError:
        pass


def test_distillation_loss_is_finite_and_positive():
    student_logits = torch.randn(4, 2, requires_grad=True)
    teacher_logits = torch.randn(4, 2)
    labels = torch.tensor([0, 1, 1, 0])
    loss = distillation_loss(student_logits, teacher_logits, labels)
    assert torch.isfinite(loss)
    assert loss.item() > 0


def test_hidden_mse_loss_zero_when_projected_states_match_exactly():
    projector = HiddenProjector(student_hidden=4, teacher_hidden=4)
    with torch.no_grad():
        projector.proj.weight.copy_(torch.eye(4))
        projector.proj.bias.zero_()

    shared = [torch.randn(2, 3, 4) for _ in range(5)]  # embeddings + 4 layers
    loss = hidden_mse_loss(tuple(shared), tuple(shared), projector, teacher_layer_indices=(1, 2, 3, 4))
    assert loss.item() < 1e-10


def test_distill_runs_end_to_end_and_returns_history(tiny_tokenizer):
    rows = synthetic_dataset()
    train_rows = [r for r in rows if r.split == "train"]
    val_rows = [r for r in rows if r.split == "val"]

    teacher_cfg = tiny_teacher_config(tiny_tokenizer.vocab_size)
    teacher = ScamShieldClassifier.from_config(teacher_cfg)
    student_cfg = student_config(
        tiny_tokenizer.vocab_size, hidden_size=4, num_hidden_layers=4, num_attention_heads=4, intermediate_size=16
    )
    student = ScamShieldClassifier.from_config(student_cfg)

    result = distill(train_rows, val_rows, tiny_tokenizer, teacher, student, epochs=2, batch_size=4, max_seq_len=16)

    assert len(result["history"]) == 2
    for entry in result["history"]:
        assert entry["train_loss"] >= 0
        assert entry["val_loss"] >= 0
