import torch

from scamshield_ml.model import NUM_CATEGORIES, ScamShieldClassifier
from scamshield_ml.schema import CATEGORIES

from .conftest import tiny_student_config


def test_num_categories_matches_schema():
    assert NUM_CATEGORIES == len(CATEGORIES) == 13


def test_forward_shapes(tiny_tokenizer):
    config = tiny_student_config(tiny_tokenizer.vocab_size)
    model = ScamShieldClassifier.from_config(config)

    batch = tiny_tokenizer(["your otp is do not share it", "team standup at nine am"], padding=True, return_tensors="pt")
    binary_logits, category_logits = model(batch["input_ids"], batch["attention_mask"])

    assert binary_logits.shape == (2, 2)
    assert category_logits.shape == (2, NUM_CATEGORIES)


def test_pooling_ignores_padding_tokens(tiny_tokenizer):
    config = tiny_student_config(tiny_tokenizer.vocab_size)
    model = ScamShieldClassifier.from_config(config)
    model.eval()

    short = tiny_tokenizer(["your otp is"], return_tensors="pt")
    padded = tiny_tokenizer(["your otp is"], padding="max_length", max_length=10, return_tensors="pt")

    with torch.no_grad():
        pooled_short = model.pooled_hidden(short["input_ids"], short["attention_mask"])
        pooled_padded = model.pooled_hidden(padded["input_ids"], padded["attention_mask"])

    assert torch.allclose(pooled_short, pooled_padded, atol=1e-5)


def test_hidden_states_includes_embeddings_plus_every_layer(tiny_tokenizer):
    config = tiny_student_config(tiny_tokenizer.vocab_size)
    model = ScamShieldClassifier.from_config(config)

    batch = tiny_tokenizer(["thanks for shopping"], return_tensors="pt")
    states = model.hidden_states(batch["input_ids"], batch["attention_mask"])

    assert len(states) == config.num_hidden_layers + 1
