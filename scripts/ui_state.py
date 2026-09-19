#!/usr/bin/env python3
"""Liest einen uiautomator-Dump und gibt die interessierenden Widgets aus.

Attributreihenfolge im Dump ist nicht stabil (`text` steht vor `resource-id`),
deshalb echtes XML-Parsing statt Regex. Rein lesend.
"""
import re
import sys
import xml.etree.ElementTree as ET

WANTED = ("resultHeadline", "resultSubline", "resultTops", "warningText",
          "emergencyText", "qualityHint", "shutterButton", "torchButton")

xml = sys.stdin.read()
# Der Dump enthaelt gelegentlich ein fuehrendes "UI hierchary dumped to:" --
# alles vor dem ersten '<' verwerfen.
start = xml.find("<")
if start < 0:
    print("  (kein XML im Dump)")
    raise SystemExit(1)

try:
    root = ET.fromstring(xml[start:])
except ET.ParseError as exc:
    print(f"  (XML nicht lesbar: {exc})")
    raise SystemExit(1)

found = {}
for node in root.iter("node"):
    rid = node.get("resource-id", "")
    for name in WANTED:
        if rid.endswith(name):
            found[name] = {
                "text": node.get("text", ""),
                "enabled": node.get("enabled"),
                "bounds": node.get("bounds", ""),
            }

for name in WANTED:
    if name in found:
        info = found[name]
        text = info["text"]
        if len(text) > 70:
            text = text[:67] + "…"
        print(f"  {name:16s} \"{text}\"  enabled={info['enabled']}  {info['bounds']}")
    else:
        print(f"  {name:16s} -- nicht im Baum")
