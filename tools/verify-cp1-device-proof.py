#!/usr/bin/env python3
"""Fail-closed validator for a ZomDroid CP1 opt-proof.log captured on device."""

import argparse
from pathlib import Path
import re
import sys


def fail(message: str) -> None:
    print(f"CP1_DEVICE_PROOF FAIL reason={message}", file=sys.stderr)
    raise SystemExit(1)


parser = argparse.ArgumentParser()
parser.add_argument("--expect", required=True, choices=("off", "on", "fallback"))
parser.add_argument("proof", type=Path)
args = parser.parse_args()

if not args.proof.is_file():
    fail("proof_missing")
text = args.proof.read_text(encoding="utf-8", errors="replace")
lines = [line for line in text.splitlines() if "[ZD-OPT-PROOF]" in line]
if not lines:
    fail("no_proof_lines")
normalized = [line.upper() for line in lines]

sessions = {
    match.group(1)
    for line in lines
    for match in [re.search(r"\bsession=([^ ]+)", line, re.IGNORECASE)]
    if match and match.group(1).lower() != "unknown"
}
if len(sessions) > 1:
    fail("mixed_sessions")


def contains(*tokens: str) -> bool:
    wanted = tuple(token.upper() for token in tokens)
    return any(all(token in line for token in wanted) for line in normalized)


available = contains("PATHFINDING_NATIVE_AVAILABLE", "STATE=AVAILABLE")
active = contains("PATHFINDING_NATIVE_ACTIVE", "STATE=ACTIVE")
off = contains("PATHFINDING_NATIVE_ACTIVE", "STATE=OFF")
exercised = contains("PATHFINDING_NATIVE_EXERCISED", "STATE=EXERCISED")
fallback = contains("PATHFINDING_NATIVE_FALLBACK", "STATE=FALLBACK") or contains(
    "PATHFINDING_NATIVE_FALLBACK", "STATE=FALLBACK_PENDING_RESTART"
)

if args.expect == "on":
    if not available: fail("available_not_proven")
    if not active: fail("active_not_proven")
    if not exercised: fail("exercised_not_proven")
    if fallback: fail("unexpected_fallback")
elif args.expect == "off":
    if not off: fail("off_not_proven")
    if active or exercised: fail("native_route_seen_while_off")
else:
    if not fallback: fail("fallback_not_proven")

session = next(iter(sessions), "unknown")
print(
    "CP1_DEVICE_PROOF PASS"
    f" expect={args.expect} session={session}"
    f" available={int(available)} active={int(active)}"
    f" exercised={int(exercised)} fallback={int(fallback)}"
)
