"""Shared fixtures for the Phase 3 (model training) test suite.

Every training-stage script is tested against tiny, from-scratch configs and a tiny hand-built
tokenizer -- never the real `google/muril-base-cased` checkpoint. That keeps the suite fast and
network-independent; the scripts' *default* CLI arguments still point at the real model name, per
design.md section 9.4, so a real run is a config change, not a code change.
"""

from __future__ import annotations

import pytest
from tokenizers import Tokenizer
from tokenizers.models import WordLevel
from tokenizers.pre_tokenizers import Whitespace
from transformers import BertConfig, PreTrainedTokenizerFast

# A small fixed vocabulary covering the words used across the Phase 3 test suite's synthetic
# messages, plus the four special tokens every BERT-style tokenizer needs.
_SPECIAL_TOKENS = ("[PAD]", "[UNK]", "[CLS]", "[SEP]")
_CORPUS_WORDS = (
    "your otp is do not share it with anyone dear customer kyc expire today update account "
    "click here to verify claim your prize now urgent bank transfer confirm delivery scheduled "
    "for tomorrow congratulations you have won a lottery pay ten thousand rupees processing fee "
    "meeting reminder team standup at nine am thanks for shopping order has shipped"
).split()


def build_tiny_tokenizer() -> PreTrainedTokenizerFast:
    vocab = {tok: i for i, tok in enumerate(_SPECIAL_TOKENS)}
    for word in dict.fromkeys(_CORPUS_WORDS):  # dedupe, preserve order
        vocab.setdefault(word, len(vocab))

    backend = Tokenizer(WordLevel(vocab=vocab, unk_token="[UNK]"))
    backend.pre_tokenizer = Whitespace()
    return PreTrainedTokenizerFast(
        tokenizer_object=backend,
        unk_token="[UNK]",
        pad_token="[PAD]",
        cls_token="[CLS]",
        sep_token="[SEP]",
    )


@pytest.fixture
def tiny_tokenizer() -> PreTrainedTokenizerFast:
    return build_tiny_tokenizer()


def tiny_teacher_config(vocab_size: int) -> BertConfig:
    """12 layers, matching design.md's hardcoded teacher hidden-state indices [3, 6, 9, 12] in
    the distillation MSE term -- only `hidden_size` shrinks for test speed, layer count doesn't,
    since the distillation code path depends on there being exactly 12 to index into.
    """
    return BertConfig(
        vocab_size=vocab_size,
        hidden_size=8,
        num_hidden_layers=12,
        num_attention_heads=2,
        intermediate_size=16,
        max_position_embeddings=64,
        type_vocab_size=2,
    )


def tiny_student_config(vocab_size: int) -> BertConfig:
    """4 layers, matching design.md section 9.4 Stage B's student shape (proportions shrunk for
    test speed: real spec is hidden 384 / heads 4 / intermediate 1536, a 4x hidden->intermediate
    ratio this keeps at 4x too).
    """
    return BertConfig(
        vocab_size=vocab_size,
        hidden_size=4,
        num_hidden_layers=4,
        num_attention_heads=4,
        intermediate_size=16,
        max_position_embeddings=64,
        type_vocab_size=2,
    )
