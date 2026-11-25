#!/usr/bin/env python3
"""Checks a phone model's drawing reply like the app reads it (MakeFiles.svg: from the first
"<svg" to the last "</svg>"): is there a drawing, does it parse, how big is it. Prints one line."""
import sys
import xml.etree.ElementTree as ET

text = open(sys.argv[1], encoding="utf-8", errors="replace").read()
low = text.lower()
start, end = low.find("<svg"), low.rfind("</svg>")
if start < 0 or end < start:
    print(f"DRAWING: none (reply {len(text)} chars): {text[:200]!r}")
    sys.exit(0)
svg = text[start:end + 6]
try:
    root = ET.fromstring(svg)
    shapes = sum(1 for _ in root.iter())
    print(f"DRAWING: valid SVG, {len(svg)} chars, {shapes} elements (reply {len(text)} chars)")
except ET.ParseError as e:
    print(f"DRAWING: SVG that does not parse ({e}), {len(svg)} chars (reply {len(text)} chars)")
