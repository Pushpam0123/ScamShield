"""The shared two-head classifier architecture (design.md section 6.1), used by every training
stage so the teacher, the student, and the exported ONNX graph all agree on the same interface:

    input:  input_ids [batch, seq_len] int64, attention_mask [batch, seq_len] int64
    output: binary_logits [batch, 2], category_logits [batch, 13]

`seq_len` is a training-time choice (design.md's on-device max is 128; tests use much shorter
sequences to stay fast, since nothing about the architecture depends on the specific length).
`NUM_CATEGORIES` is `len(schema.CATEGORIES)` so the head width can never silently drift from the
label encoding `schema.py` already owns.
"""

from __future__ import annotations

import torch
from torch import nn
from transformers import AutoModel, PretrainedConfig

from .schema import CATEGORIES

NUM_CATEGORIES = len(CATEGORIES)
MAX_SEQ_LEN = 128


class ScamShieldClassifier(nn.Module):
    """Wraps a HuggingFace encoder (teacher: `google/muril-base-cased`; student: a smaller
    from-scratch config, see `distill.py`) with the two linear heads design.md specifies.
    Pooling is mean-pooling over `attention_mask`, not the encoder's `[CLS]` pooler output --
    MuRIL's pooler is trained for a different objective (next-sentence-style pretraining), and
    mean-pooling over real tokens is the more standard choice for a from-scratch classification
    head on top of a frozen-ish encoder.
    """

    def __init__(self, encoder: nn.Module, hidden_size: int):
        super().__init__()
        self.encoder = encoder
        self.hidden_size = hidden_size
        self.binary_head = nn.Linear(hidden_size, 2)
        self.category_head = nn.Linear(hidden_size, NUM_CATEGORIES)

    @classmethod
    def from_pretrained(cls, model_name: str) -> "ScamShieldClassifier":
        encoder = AutoModel.from_pretrained(model_name)
        return cls(encoder, encoder.config.hidden_size)

    @classmethod
    def from_config(cls, config: PretrainedConfig) -> "ScamShieldClassifier":
        """Builds an encoder from a config with randomly-initialized weights, no download --
        what tests use (a tiny `BertConfig`) and what `distill.py` uses for the student before
        applying its own teacher-derived initialization.
        """
        encoder = AutoModel.from_config(config)
        return cls(encoder, config.hidden_size)

    def pooled_hidden(self, input_ids: torch.Tensor, attention_mask: torch.Tensor) -> torch.Tensor:
        outputs = self.encoder(input_ids=input_ids, attention_mask=attention_mask, output_hidden_states=True)
        mask = attention_mask.unsqueeze(-1).to(outputs.last_hidden_state.dtype)
        summed = (outputs.last_hidden_state * mask).sum(dim=1)
        counts = mask.sum(dim=1).clamp(min=1e-9)
        return summed / counts

    def forward(
        self, input_ids: torch.Tensor, attention_mask: torch.Tensor
    ) -> tuple[torch.Tensor, torch.Tensor]:
        pooled = self.pooled_hidden(input_ids, attention_mask)
        return self.binary_head(pooled), self.category_head(pooled)

    def hidden_states(self, input_ids: torch.Tensor, attention_mask: torch.Tensor) -> tuple[torch.Tensor, ...]:
        """Full per-layer hidden state stack (embeddings + one per transformer layer), for the
        distillation MSE term (design.md section 9.4 Stage B), not needed by ordinary forward.
        """
        outputs = self.encoder(input_ids=input_ids, attention_mask=attention_mask, output_hidden_states=True)
        return outputs.hidden_states
