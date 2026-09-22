from __future__ import annotations

import re

PATTERNS = [
    (re.compile(r"(?<!\d)(?:\+?86[- ]?)?1[3-9]\d[- ]?\d{4}[- ]?\d{4}(?!\d)"), "[手机号已隐藏]"),
    (re.compile(r"(?<!\d)0\d{2,3}-?\d{7,8}(?!\d)"), "[手机号已隐藏]"),
    (re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}"), "[邮箱已隐藏]"),
    (re.compile(r"(?<!\d)\d{17}[\dXx](?!\d)"), "[证件号已隐藏]"),
    (re.compile(r"https?://[^\s，。；;]+", re.I), "[链接已隐藏]"),
    (re.compile(r"(?m)(?:现居地址|家庭住址|联系地址|地址)\s*[：:]\s*[^\n]{4,80}"), "[地址已隐藏]"),
]


def mask(text: str) -> str:
    result = text or ""
    for pattern, replacement in PATTERNS:
        result = pattern.sub(replacement, result)
    return result


def contains_pii(text: str) -> bool:
    return any(pattern.search(text or "") for pattern, _ in PATTERNS)


def scan(text: str) -> dict[str, int]:
    names = ["phone", "phone", "email", "idCard", "url", "address"]
    result: dict[str, int] = {}
    for (pattern, _), name in zip(PATTERNS, names, strict=False):
        count = len(pattern.findall(text or ""))
        if count:
            result[name] = result.get(name, 0) + count
    return result


def for_log(text: str, maximum: int = 200) -> str:
    safe = mask(text).replace("\r", " ").replace("\n", " ")
    return safe if len(safe) <= maximum else safe[:maximum] + "…"
