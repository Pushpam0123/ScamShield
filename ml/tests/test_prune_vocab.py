from scamshield_ml.model import ScamShieldClassifier
from scamshield_ml.prune_vocab import (
    build_id_remap,
    build_pruned_tokenizer,
    count_token_frequencies,
    prune_model_and_tokenizer,
    recover_after_prune,
    select_kept_token_ids,
    slice_embedding,
    special_token_ids,
)

from .conftest import tiny_teacher_config
from .test_train_teacher import synthetic_dataset

import pytest
import torch
from tokenizers import Tokenizer
from tokenizers.models import WordPiece
from tokenizers.pre_tokenizers import Whitespace
from transformers import PreTrainedTokenizerFast


def _wordpiece_tokenizer() -> PreTrainedTokenizerFast:
    vocab = {tok: i for i, tok in enumerate(["[PAD]", "[UNK]", "[CLS]", "[SEP]", "your", "otp", "##s"])}
    backend = Tokenizer(WordPiece(vocab=vocab, unk_token="[UNK]"))
    backend.pre_tokenizer = Whitespace()
    return PreTrainedTokenizerFast(
        tokenizer_object=backend, unk_token="[UNK]", pad_token="[PAD]", cls_token="[CLS]", sep_token="[SEP]"
    )


def test_build_pruned_tokenizer_refuses_wordpiece_input():
    # A WordPiece tokenizer (like real MuRIL) must fail loudly, not be silently downgraded to
    # WordLevel — see prune_vocab._assert_wordlevel and the module docstring.
    with pytest.raises(NotImplementedError, match="WordPiece"):
        build_pruned_tokenizer(_wordpiece_tokenizer(), kept_ids=[0, 1, 2, 3, 4, 5])


def test_special_token_ids_includes_pad_unk_cls_sep(tiny_tokenizer):
    ids = special_token_ids(tiny_tokenizer)
    for tok in ("[PAD]", "[UNK]", "[CLS]", "[SEP]"):
        assert tiny_tokenizer.convert_tokens_to_ids(tok) in ids


def test_count_token_frequencies_counts_repeated_words(tiny_tokenizer):
    freq = count_token_frequencies(tiny_tokenizer, ["your otp is your otp", "your otp"])
    your_id = tiny_tokenizer.convert_tokens_to_ids("your")
    assert freq[your_id] == 3


def test_select_kept_token_ids_always_keeps_specials(tiny_tokenizer):
    freq = count_token_frequencies(tiny_tokenizer, ["your otp is"])
    specials = special_token_ids(tiny_tokenizer)
    kept = select_kept_token_ids(freq, specials, min_freq=1000, target_size=1000)
    assert specials.issubset(set(kept))


def test_select_kept_token_ids_drops_rare_tokens(tiny_tokenizer):
    freq = count_token_frequencies(tiny_tokenizer, ["your otp is", "your otp is", "your otp is", "your otp is", "your otp is"])
    freq.update(count_token_frequencies(tiny_tokenizer, ["meeting"]))  # a known token, seen once
    specials = special_token_ids(tiny_tokenizer)

    kept = select_kept_token_ids(freq, specials, min_freq=5, target_size=1000)
    your_id = tiny_tokenizer.convert_tokens_to_ids("your")
    meeting_id = tiny_tokenizer.convert_tokens_to_ids("meeting")

    assert your_id in kept
    assert meeting_id not in kept


def test_select_kept_token_ids_caps_at_target_size_by_frequency(tiny_tokenizer):
    texts = ["your"] * 10 + ["otp"] * 5 + ["is"] * 1
    freq = count_token_frequencies(tiny_tokenizer, texts)
    specials = special_token_ids(tiny_tokenizer)

    kept = select_kept_token_ids(freq, specials, min_freq=1, target_size=len(specials) + 1)
    your_id = tiny_tokenizer.convert_tokens_to_ids("your")

    assert len(kept) == len(specials) + 1
    assert your_id in kept  # highest frequency survivor


def test_slice_embedding_and_remap_agree_on_row_order():
    weight = torch.arange(20).reshape(5, 4).float()
    kept_ids = [3, 0, 4]
    sliced = slice_embedding(weight, kept_ids)
    remap = build_id_remap(kept_ids)

    for old_id, new_id in remap.items():
        assert torch.equal(sliced[new_id], weight[old_id])


def test_build_pruned_tokenizer_only_knows_kept_tokens(tiny_tokenizer):
    kept_ids = sorted(special_token_ids(tiny_tokenizer) | {tiny_tokenizer.convert_tokens_to_ids("your")})
    pruned = build_pruned_tokenizer(tiny_tokenizer, kept_ids)

    assert pruned.vocab_size == len(kept_ids)
    encoded = pruned("your otp", add_special_tokens=False)["input_ids"]
    # "otp" was dropped -> becomes [UNK]; "your" survives with its remapped id
    assert encoded[0] == pruned.convert_tokens_to_ids("your")
    assert encoded[1] == pruned.unk_token_id


def test_prune_model_and_tokenizer_shrinks_embedding(tiny_tokenizer):
    config = tiny_teacher_config(tiny_tokenizer.vocab_size)
    model = ScamShieldClassifier.from_config(config)
    original_vocab_size = tiny_tokenizer.vocab_size

    corpus = ["your otp is do not share it"] * 6  # each word freq >= 5
    model, new_tokenizer, stats = prune_model_and_tokenizer(model, tiny_tokenizer, corpus, min_freq=5, target_size=1000)

    assert stats["pruned_vocab_size"] < original_vocab_size
    assert model.encoder.embeddings.word_embeddings.weight.shape[0] == stats["pruned_vocab_size"]
    assert new_tokenizer.vocab_size == stats["pruned_vocab_size"]

    # model still runs end to end with its new tokenizer + smaller embedding
    batch = new_tokenizer(["your otp is"], return_tensors="pt")
    binary_logits, category_logits = model(batch["input_ids"], batch["attention_mask"])
    assert binary_logits.shape == (1, 2)


def test_recover_after_prune_runs_the_requested_epoch_count(tiny_tokenizer):
    config = tiny_teacher_config(tiny_tokenizer.vocab_size)
    model = ScamShieldClassifier.from_config(config)
    corpus = ["your otp is do not share it"] * 6
    model, new_tokenizer, _ = prune_model_and_tokenizer(model, tiny_tokenizer, corpus, min_freq=5, target_size=1000)

    rows = synthetic_dataset()
    train_rows = [r for r in rows if r.split == "train"]
    val_rows = [r for r in rows if r.split == "val"]

    result = recover_after_prune(model, new_tokenizer, train_rows, val_rows, epochs=2)
    assert len(result["history"]) == 2
