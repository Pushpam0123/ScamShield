#!/usr/bin/env python3
"""Validate rulepack/src and emit app/src/main/assets/rulepack/v1/.

architecture.md §11: "A rule pack is validated against a JSON schema at load. If validation
fails, the app falls back to the bundled pack and reports a non-fatal error — it never runs
with a partially-loaded pack." This script is the build-time half of that promise: the build
itself fails loudly on anything the runtime loader would have to reject, so a broken pack never
reaches app/src/main/assets in the first place.

Two kinds of check happen here:
  1. JSON Schema validation per file (rulepack/schema/*.schema.json) — structural.
  2. Cross-file invariant checks that JSON Schema cannot express — e.g. no two brands sharing
     an alias, every shortener's brand_operated entry naming a real brand id. These matter more
     than the schema: design.md §3.5's force-verdict rule is only as safe as banks.json's alias
     precision, and a schema cannot check "is this alias too generic" or "does brand X exist".

Usage:
    python3 rulepack/build_rulepack.py [--skip-reputation-fetch]
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
import struct
import subprocess
import sys
import zipfile
from datetime import datetime, timezone
from pathlib import Path

try:
    import jsonschema
except ImportError:  # pragma: no cover
    sys.exit("jsonschema is required: python3 -m pip install jsonschema")

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "rulepack" / "src"
SCHEMA_DIR = ROOT / "rulepack" / "schema"
OUT_DIR = ROOT / "app" / "src" / "main" / "assets" / "rulepack" / "v1"
CACHE_DIR = ROOT / "rulepack" / ".cache"

PACK_VERSION = "v1"
SCHEMA_VERSION = 1

# Bare common nouns that must never appear as a brand alias (banks.json comment block).
# A brand claim gates the URL analyzer's force-verdict rule (design.md §3.5); an alias this
# generic would force a SCAM verdict on unrelated genuine messages. This is a hard build-time
# gate, not just a documentation comment, because a copy-paste mistake here is expensive.
FORBIDDEN_ALIASES = {
    "bank", "pay", "card", "post", "service", "account", "office", "department",
    "app", "mobile", "online", "secure", "support", "helpline", "customer",
}

TRANCO_URL = "https://tranco-list.eu/top-1m.csv.zip"
BLOOM_TARGET_FPR = 0.01


class RulepackError(Exception):
    """Raised for a validation failure that must fail the build."""


def fail(message: str) -> None:
    raise RulepackError(message)


# ---------------------------------------------------------------------------
# Schema validation
# ---------------------------------------------------------------------------

def load_json(path: Path) -> dict:
    with path.open(encoding="utf-8") as f:
        return json.load(f)


def validate_schema(name: str, data: dict) -> None:
    schema = load_json(SCHEMA_DIR / f"{name}.schema.json")
    validator_cls = jsonschema.validators.validator_for(schema)
    validator_cls.check_schema(schema)
    validator = validator_cls(schema)
    errors = sorted(validator.iter_errors(data), key=lambda e: list(e.path))
    if errors:
        detail = "\n".join(
            f"  - at {'/'.join(str(p) for p in e.path) or '<root>'}: {e.message}"
            for e in errors
        )
        fail(f"{name}.json failed schema validation:\n{detail}")


# ---------------------------------------------------------------------------
# Cross-file invariant checks
# ---------------------------------------------------------------------------

def check_banks(banks: dict) -> None:
    brands = banks["brands"]

    ids = [b["id"] for b in brands]
    dup_ids = {i for i in ids if ids.count(i) > 1}
    if dup_ids:
        fail(f"banks.json: duplicate brand ids: {sorted(dup_ids)}")

    alias_owner: dict[str, str] = {}
    for b in brands:
        for alias in b["aliases"]:
            lower = alias.lower().strip()
            if lower in FORBIDDEN_ALIASES:
                fail(
                    f"banks.json: brand '{b['id']}' has alias '{alias}', which is a forbidden "
                    f"bare common noun (design.md §3.5 force-verdict safety — see "
                    f"FORBIDDEN_ALIASES in build_rulepack.py)"
                )
            if lower in alias_owner and alias_owner[lower] != b["id"]:
                fail(
                    f"banks.json: alias '{alias}' claimed by both "
                    f"'{alias_owner[lower]}' and '{b['id']}'"
                )
            alias_owner[lower] = b["id"]

    return {b["id"] for b in brands}


def check_shorteners(shorteners: dict, brand_ids: set[str]) -> None:
    domains = shorteners["shorteners"]
    dup = {d for d in domains if domains.count(d) > 1}
    if dup:
        fail(f"shorteners.json: duplicate shortener domains: {sorted(dup)}")

    for shortener, brand_id in shorteners.get("brand_operated", {}).items():
        if brand_id not in brand_ids:
            fail(
                f"shorteners.json: brand_operated['{shortener}'] = '{brand_id}', "
                f"which is not a brand id in banks.json"
            )


def check_typosquat(typosquat: dict) -> None:
    dist = typosquat["distance"]
    if dist["short_label_distance"] > dist["long_label_distance"]:
        fail(
            "typosquat.json: distance.short_label_distance must not exceed "
            "distance.long_label_distance"
        )
    for char, folded in typosquat["single_char_folds"].items():
        if len(char) != 1:
            fail(f"typosquat.json: single_char_folds key '{char}' is not a single character")


def check_patterns(patterns: dict) -> None:
    rules = patterns["patterns"]
    ids = [r["id"] for r in rules]
    dup = {i for i in ids if ids.count(i) > 1}
    if dup:
        fail(f"patterns.json: duplicate pattern ids: {sorted(dup)}")

    for rule in rules:
        for key in ("pattern", "suppress_if"):
            if key not in rule:
                continue
            try:
                re.compile(rule[key])
            except re.error as e:
                fail(f"patterns.json: rule '{rule['id']}'.{key} does not compile: {e}")


def check_psl(text: str) -> None:
    if "===BEGIN ICANN DOMAINS===" not in text:
        fail("public_suffix_list.dat: missing ICANN section marker — not a genuine PSL snapshot")
    rule_lines = [
        line.strip() for line in text.splitlines()
        if line.strip() and not line.strip().startswith("//")
    ]
    if len(rule_lines) < 1000:
        fail(f"public_suffix_list.dat: only {len(rule_lines)} rule lines, suspiciously small")
    # design.md §2.2 — the two cases approximating eTLD+1 as "last two labels" gets wrong.
    for required in ("co.in", "gov.in"):
        if required not in rule_lines:
            fail(f"public_suffix_list.dat: missing required rule '{required}'")


# ---------------------------------------------------------------------------
# reputation.bin — Bloom filter of established domains + explicit allowlist
#
# Format (little-endian):
#   7 bytes   magic "SSREPv1"
#   u32       m               bloom bit-array size, in bits
#   u32       k                number of hash rounds
#   u32       bloomByteLen     = ceil(m / 8)
#   bytes[]   bloom bit array
#   u32       allowlistCount
#   for each allowlist domain, sorted, deduped, lowercase ascii:
#     u16     domainByteLen
#     bytes[] domain UTF-8
#
# Membership test for domain d, matching design.md §3.1's "absence means unknown, never new":
#   digest = SHA-256(d)                      # 32 bytes
#   h1, h2 = first 8 bytes, next 8 bytes of digest, as signed 64-bit big-endian integers
#   for i in 0 until k:
#       bit = floorMod(h1 + i * h2, m)
#       if bloom[bit] == 0: NOT PRESENT (definitely unknown)
#   else: POSSIBLY PRESENT (established)
#
# SHA-256 is used, not a general-purpose bloom library, so that the exact same construction is
# trivial to reproduce in Kotlin with java.security.MessageDigest and no extra dependency —
# :analyzer:url must stay free of any hashing library beyond the JDK (architecture.md §10.1).
# ---------------------------------------------------------------------------

MAGIC = b"SSREPv1"


def bloom_params(n: int, target_fpr: float) -> tuple[int, int]:
    """Standard Bloom filter sizing (Bloom, 1970): m = -(n ln p) / (ln 2)^2, k = (m/n) ln 2."""
    if n == 0:
        return 8, 1
    m = max(8, math.ceil(-(n * math.log(target_fpr)) / (math.log(2) ** 2)))
    k = max(1, round((m / n) * math.log(2)))
    return m, k


def domain_hashes(domain: str) -> tuple[int, int]:
    digest = hashlib.sha256(domain.encode("utf-8")).digest()
    h1, h2 = struct.unpack(">qq", digest[:16])
    return h1, h2


def build_bloom(domains: list[str], target_fpr: float) -> tuple[bytes, int, int]:
    n = len(domains)
    m, k = bloom_params(n, target_fpr)
    bit_array = bytearray((m + 7) // 8)
    for domain in domains:
        h1, h2 = domain_hashes(domain)
        for i in range(k):
            bit = (h1 + i * h2) % m
            bit_array[bit // 8] |= 1 << (bit % 8)
    return bytes(bit_array), m, k


def fetch_tranco(skip_fetch: bool) -> tuple[list[str], str]:
    """Returns (registrable domains, source label). Never fabricates data: a failed or
    skipped fetch yields an empty list and an honest source label, rather than a fake one."""
    if skip_fetch:
        return [], "skipped"

    CACHE_DIR.mkdir(parents=True, exist_ok=True)
    cache_zip = CACHE_DIR / "tranco-top1m.csv.zip"

    if not cache_zip.exists():
        print(f"Fetching {TRANCO_URL} ...", file=sys.stderr)
        # curl, not urllib: this host's Python has no working local CA trust store
        # (urllib raises CERTIFICATE_VERIFY_FAILED), while curl uses the system store
        # successfully. Shelling out avoids depending on certifi as an extra pip package
        # for a one-shot build-time fetch.
        tmp = cache_zip.with_suffix(".part")
        result = subprocess.run(
            ["curl", "-sL", "--max-time", "60", "-o", str(tmp), TRANCO_URL],
            capture_output=True, text=True,
        )
        if result.returncode != 0 or not tmp.exists() or tmp.stat().st_size == 0:
            print(f"WARNING: Tranco fetch failed (curl exit {result.returncode}: "
                  f"{result.stderr.strip()}); reputation.bin will carry only the "
                  f"banks.json allowlist, with an empty general Bloom filter.",
                  file=sys.stderr)
            tmp.unlink(missing_ok=True)
            return [], "unavailable"
        tmp.rename(cache_zip)

    try:
        with zipfile.ZipFile(cache_zip) as zf:
            csv_name = next(n for n in zf.namelist() if n.endswith(".csv"))
            with zf.open(csv_name) as f:
                domains = []
                for line in f:
                    line = line.decode("utf-8", errors="ignore").strip()
                    if not line or "," not in line:
                        continue
                    _, domain = line.split(",", 1)
                    domain = domain.strip().lower()
                    if domain and "." in domain and domain.isascii():
                        domains.append(domain)
        return domains, "tranco-top-1m"
    except Exception as e:  # noqa: BLE001
        print(f"WARNING: Tranco archive unreadable ({e}); treating as unavailable.",
              file=sys.stderr)
        return [], "unavailable"


def build_reputation_bin(banks: dict, skip_fetch: bool) -> tuple[bytes, dict]:
    established_domains, source = fetch_tranco(skip_fetch)
    bloom_bytes, m, k = build_bloom(established_domains, BLOOM_TARGET_FPR)

    allowlist = sorted({
        d.lower() for b in banks["brands"] for d in b["domains"]
    })

    out = bytearray()
    out += MAGIC
    out += struct.pack("<III", m, k, len(bloom_bytes))
    out += bloom_bytes
    out += struct.pack("<I", len(allowlist))
    for domain in allowlist:
        encoded = domain.encode("utf-8")
        out += struct.pack("<H", len(encoded))
        out += encoded

    stats = {
        "bloom_source": source,
        "bloom_domain_count": len(established_domains),
        "bloom_bits": m,
        "bloom_hash_rounds": k,
        "bloom_target_fpr": BLOOM_TARGET_FPR,
        "allowlist_domain_count": len(allowlist),
    }
    return bytes(out), stats


# ---------------------------------------------------------------------------
# Emission — strip authoring commentary, minify, write to assets
# ---------------------------------------------------------------------------

def strip_comments(value):
    """Recursively drop any object key starting with '_'. Comments are for the humans
    reading rulepack/src; the runtime loader never sees or needs them, and every byte
    trimmed here is a byte the 25 MB APK budget (G5) doesn't have to find elsewhere."""
    if isinstance(value, dict):
        return {
            k: strip_comments(v) for k, v in value.items()
            if not k.startswith("_")
        }
    if isinstance(value, list):
        return [strip_comments(v) for v in value]
    return value


def write_json(path: Path, data: dict) -> None:
    path.write_text(json.dumps(strip_comments(data), separators=(",", ":"), ensure_ascii=False), encoding="utf-8")


def write_psl(path: Path, text: str) -> None:
    rule_lines = [
        line.strip() for line in text.splitlines()
        if line.strip() and not line.strip().startswith("//")
    ]
    path.write_text("\n".join(rule_lines) + "\n", encoding="utf-8")


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--skip-reputation-fetch", action="store_true",
        help="Do not attempt to download the Tranco top-1M list; emit an allowlist-only "
             "reputation.bin. Use for fast local iteration on the other files.",
    )
    args = parser.parse_args()

    try:
        banks = load_json(SRC / "banks.json")
        shorteners = load_json(SRC / "shorteners.json")
        typosquat = load_json(SRC / "typosquat.json")
        patterns = load_json(SRC / "patterns.json")
        psl_text = (SRC / "public_suffix_list.dat").read_text(encoding="utf-8")

        validate_schema("banks", banks)
        validate_schema("shorteners", shorteners)
        validate_schema("typosquat", typosquat)
        validate_schema("patterns", patterns)

        brand_ids = check_banks(banks)
        check_shorteners(shorteners, brand_ids)
        check_typosquat(typosquat)
        check_patterns(patterns)
        check_psl(psl_text)

    except RulepackError as e:
        print(f"\nRULEPACK BUILD FAILED\n{e}\n", file=sys.stderr)
        return 1

    reputation_bytes, reputation_stats = build_reputation_bin(banks, args.skip_reputation_fetch)

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    write_json(OUT_DIR / "banks.json", banks)
    write_json(OUT_DIR / "shorteners.json", shorteners)
    write_json(OUT_DIR / "typosquat.json", typosquat)
    write_json(OUT_DIR / "patterns.json", patterns)
    write_psl(OUT_DIR / "public_suffix_list.dat", psl_text)
    (OUT_DIR / "reputation.bin").write_bytes(reputation_bytes)

    meta = {
        "pack_version": PACK_VERSION,
        "schema_version": SCHEMA_VERSION,
        "generated_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "counts": {
            "brands": len(banks["brands"]),
            "shorteners": len(shorteners["shorteners"]),
            "patterns": len(patterns["patterns"]),
        },
        "reputation": reputation_stats,
    }
    (OUT_DIR / "meta.json").write_text(json.dumps(meta, indent=2), encoding="utf-8")

    print(f"Rule pack built: {OUT_DIR}")
    print(json.dumps(meta, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
