"""Stateless 7-day HMAC demo token minting, verification, and CLI helper."""

from __future__ import annotations

import argparse
import hashlib
import hmac
import os
import re
import sys
import time
from dataclasses import dataclass
from pathlib import Path

from fastapi import HTTPException

_TENANT_RE = re.compile(r"^[a-z0-9-]{1,32}$")
_EXP_HEX_RE = re.compile(r"^[0-9a-f]{1,16}$")
_SIG_HEX_RE = re.compile(r"^[0-9a-f]{24}$")


@dataclass(frozen=True)
class DemoTokenClaims:
    tenant_id: str
    expires_at: int


def _compute_signature(secret: str, exp_epoch_hex: str, tenant_id: str) -> str:
    payload = f"{exp_epoch_hex}:{tenant_id}".encode("utf-8")
    return hmac.new(secret.encode("utf-8"), payload, hashlib.sha256).hexdigest()[:24]


def mint_demo_token(
    tenant_id: str,
    days: float = 7.0,
    secret: str | None = None,
    now_epoch: int | None = None,
) -> str:
    """Mint a stateless token formatted as eng_<expEpochHex>_<tenantId>_<hmacHex24>."""
    normalized_tenant = tenant_id.strip().lower()
    if not _TENANT_RE.fullmatch(normalized_tenant):
        raise ValueError("tenant_id must match ^[a-z0-9-]{1,32}$")
    if days <= 0:
        raise ValueError("days must be positive")

    resolved_secret = (
        secret if secret is not None else os.getenv("ENGRAM_DEMO_SIGNING_SECRET", "")
    ).strip()
    if not resolved_secret:
        raise ValueError("ENGRAM_DEMO_SIGNING_SECRET must not be empty")

    current_epoch = int(time.time()) if now_epoch is None else int(now_epoch)
    exp_epoch = current_epoch + max(1, int(round(days * 86400)))
    exp_epoch_hex = f"{exp_epoch:x}"
    sig_hex = _compute_signature(resolved_secret, exp_epoch_hex, normalized_tenant)
    return f"eng_{exp_epoch_hex}_{normalized_tenant}_{sig_hex}"


def verify_demo_token(
    token: str,
    secret: str,
    now_epoch: int | None = None,
) -> DemoTokenClaims:
    """Verify HMAC signature and expiration of eng_<expEpochHex>_<tenantId>_<hmacHex24>."""
    resolved_secret = secret.strip()
    if not resolved_secret:
        raise ValueError("Signing secret must not be empty")

    cleaned_token = token.strip()
    parts = cleaned_token.split("_")
    if len(parts) != 4 or parts[0] != "eng":
        raise ValueError("Invalid token structure")

    _, exp_epoch_hex, tenant_id, sig_hex = parts
    if not _EXP_HEX_RE.fullmatch(exp_epoch_hex):
        raise ValueError("Invalid token expiration field")
    if not _TENANT_RE.fullmatch(tenant_id):
        raise ValueError("Invalid token tenant field")
    if not _SIG_HEX_RE.fullmatch(sig_hex):
        raise ValueError("Invalid token signature format")

    expected_sig = _compute_signature(resolved_secret, exp_epoch_hex, tenant_id)
    if not hmac.compare_digest(sig_hex, expected_sig):
        raise ValueError("Invalid token signature")

    expires_at = int(exp_epoch_hex, 16)
    current_epoch = int(time.time()) if now_epoch is None else int(now_epoch)
    if expires_at <= current_epoch:
        raise ValueError("Demo token expired")

    return DemoTokenClaims(tenant_id=tenant_id, expires_at=expires_at)


def require_demo_claims_if_enabled(
    authorization: str | None,
    now_epoch: int | None = None,
) -> DemoTokenClaims | None:
    """Enforce Bearer demo token when ENGRAM_DEMO_SIGNING_SECRET is configured."""
    secret = os.getenv("ENGRAM_DEMO_SIGNING_SECRET", "").strip()
    if not secret:
        return None

    if not authorization:
        raise HTTPException(status_code=401, detail="Valid demo token required")

    parts = authorization.strip().split(None, 1)
    if len(parts) != 2 or parts[0].lower() != "bearer" or not parts[1].strip():
        raise HTTPException(status_code=401, detail="Valid demo token required")

    try:
        return verify_demo_token(parts[1], secret=secret, now_epoch=now_epoch)
    except ValueError as exc:
        raise HTTPException(status_code=401, detail="Valid demo token required") from exc


def _read_secret_from_dotenv() -> str:
    candidates = [
        Path.cwd() / ".env",
        Path(__file__).resolve().parents[1] / ".env",
    ]
    for path in candidates:
        if not path.is_file():
            continue
        for raw_line in path.read_text(encoding="utf-8").splitlines():
            line = raw_line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, val = line.split("=", 1)
            if key.strip() == "ENGRAM_DEMO_SIGNING_SECRET":
                cleaned = val.strip().strip('"').strip("'")
                if cleaned:
                    return cleaned
    return ""


def _cli(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="python -m app.tokens")
    subparsers = parser.add_subparsers(dest="command", required=True)

    mint_parser = subparsers.add_parser("mint", help="Mint a stateless HMAC demo token")
    mint_parser.add_argument("--tenant", required=True, help="Tenant identifier (lowercase alphanumeric/hyphen)")
    mint_parser.add_argument("--days", type=float, default=7.0, help="Validity window in days (default: 7)")
    mint_parser.add_argument("--secret", default=None, help="Optional signing secret override")

    args = parser.parse_args(argv)
    if args.command == "mint":
        secret = (
            args.secret
            if args.secret is not None
            else (os.getenv("ENGRAM_DEMO_SIGNING_SECRET", "").strip() or _read_secret_from_dotenv())
        )
        if not secret:
            print("Error: ENGRAM_DEMO_SIGNING_SECRET is not set", file=sys.stderr)
            return 1
        try:
            token = mint_demo_token(tenant_id=args.tenant, days=args.days, secret=secret)
        except ValueError as exc:
            print(f"Error: {exc}", file=sys.stderr)
            return 1
        print(token)
        return 0
    return 1


if __name__ == "__main__":
    raise SystemExit(_cli())
