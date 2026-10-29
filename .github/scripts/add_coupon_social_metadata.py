#!/usr/bin/env python3
"""Inject per-coupon Open Graph metadata into the GitHub Pages build output.

This script only edits the checked-out deployment workspace; it does not alter
the source coupon HTML files in the repository. Every existing or newly added
root-level CODE/(number)/index.html coupon page is handled automatically.
"""
from html import escape, unescape
from pathlib import Path
import re

SITE_BASE = "https://walking2129.github.io/Teenoi-Coupon"
PREVIEW_TITLE = "e-Coupon Teenoi 100 bath"
CODE_PATTERN = re.compile(r"^[A-Za-z0-9_-]{4,32}$")
VARIANT_PATTERN = re.compile(r"^\((\d+)\)$")
COUPON_KEY_PATTERN = re.compile(r"""const\s+COUPON_KEY\s*=\s*(?:'[^']*'|"[^"]*")\s*;""")
TITLE_PATTERN = re.compile(r"<title\b[^>]*>(.*?)</title\s*>", re.IGNORECASE | re.DOTALL)
HEAD_PATTERN = re.compile(r"<head\b[^>]*>", re.IGNORECASE)
META_PATTERN = re.compile(
    r'<meta\b(?=[^>]*(?:property|name)\s*=\s*["\'](?:og:[^"\']+|twitter:card|description)["\'])[^>]*>\s*',
    re.IGNORECASE,
)

def plain_title(value):
    value = re.sub(r"<[^>]*>", "", value)
    return " ".join(unescape(value).split())

def replace_or_insert_meta(head, key_type, key, content):
    # Replace existing metadata with this key so repeated builds don't duplicate tags.
    if key_type == "property":
        pattern = re.compile(
            r'<meta\b(?=[^>]*\bproperty\s*=\s*["\']' + re.escape(key) +
            r'["\'])[^>]*>', re.IGNORECASE
        )
        tag = '<meta property="' + escape(key, quote=True) + '" content="' + escape(content, quote=True) + '">'
    else:
        pattern = re.compile(
            r'<meta\b(?=[^>]*\bname\s*=\s*["\']' + re.escape(key) +
            r'["\'])[^>]*>', re.IGNORECASE
        )
        tag = '<meta name="' + escape(key, quote=True) + '" content="' + escape(content, quote=True) + '">'
    if pattern.search(head):
        return pattern.sub(lambda _: tag, head)
    return head + "\n" + tag

processed = 0
for code_dir in Path(".").iterdir():
    if not code_dir.is_dir() or not CODE_PATTERN.fullmatch(code_dir.name):
        continue

    for variant_dir in code_dir.iterdir():
        if not variant_dir.is_dir():
            continue
        variant_match = VARIANT_PATTERN.fullmatch(variant_dir.name)
        if not variant_match:
            continue

        page = variant_dir / "index.html"
        if not page.is_file():
            continue

        html = page.read_text(encoding="utf-8")
        # Derive the used-state key from the visible coupon code at runtime.
        # This works for static pages and for coupon-preview.html, which reuses
        # this template but changes the visible code before writing the page.
        dynamic_coupon_key = (
            "const COUPON_KEY = 'teenoi_' + "
            "(((document.getElementById('code') && "
            "document.getElementById('code').textContent) || 'unknown')"
            ".trim().toLowerCase()) + '_used_v4_arrow_follow_reswipe';"
        )
        html, key_count = COUPON_KEY_PATTERN.subn(dynamic_coupon_key, html)
        if key_count:
            print("Set runtime usage key:", page, "=> key derived from displayed code")
        variant_number = variant_match.group(1)
        canonical_url = (
            SITE_BASE + "/" + code_dir.name +
            "/%28" + variant_number + "%29/"
        )
        # Keep the card title and description identical for every coupon URL.
        description = PREVIEW_TITLE

        head_match = HEAD_PATTERN.search(html)
        if not head_match:
            continue
        head_start, head_end = head_match.span()
        head = html[head_start:html.lower().find("</head", head_end)]
        if not head:
            continue

        # Clean existing OG/Twitter/description tags first, then insert canonical values.
        head = META_PATTERN.sub("", head)
        head = replace_or_insert_meta(head, "property", "og:type", "website")
        head = replace_or_insert_meta(head, "property", "og:title", PREVIEW_TITLE)
        head = replace_or_insert_meta(head, "property", "og:description", description)
        head = replace_or_insert_meta(head, "property", "og:site_name", "Teenoi Coupon")
        head = replace_or_insert_meta(head, "property", "og:url", canonical_url)
        head = replace_or_insert_meta(head, "name", "description", description)
        head = replace_or_insert_meta(head, "name", "twitter:card", "summary")

        html = html[:head_start] + head + html[html.lower().find("</head", head_end):]
        page.write_text(html, encoding="utf-8")
        print("Added social preview metadata:", page, "=>", canonical_url)
        processed += 1

print("Coupon pages processed:", processed)
