#!/usr/bin/env python3
"""
Apply regenerated French example sentences (column 6) to the dictionary
CSV files. Reads tools/regenerated_sentences.json (a {hash: sentence}
mapping) and writes column 6 of every matching row in:
    app/src/main/assets/french/dictionary_sorted_2.csv
    app/src/main/assets/french/dictionary_sorted_french_01..10.csv

Usage:
    python tools/regenerate_french_sentences.py
"""
import json
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
ASSETS_DIR = REPO_ROOT / "app" / "src" / "main" / "assets" / "french"
MASTER = ASSETS_DIR / "dictionary_sorted_2.csv"
SPLITS = sorted(ASSETS_DIR.glob("dictionary_sorted_french_*.csv"))
SENTENCES_PATH = REPO_ROOT / "tools" / "regenerated_sentences.json"


def read_rows(path: Path):
    raw = path.read_bytes()
    le = "\r\n" if b"\r\n" in raw[:4096] else "\n"
    rows = [line.split("\t") for line in raw.decode("utf-8").split(le) if line]
    return le, rows


def write_rows(path: Path, line_ending: str, rows):
    out = line_ending.join("\t".join(r) for r in rows) + line_ending
    path.write_bytes(out.encode("utf-8"))


def main():
    sentences = json.loads(SENTENCES_PATH.read_text(encoding="utf-8"))
    print(f"Loaded {len(sentences)} sentences from {SENTENCES_PATH.name}")

    for path in [MASTER, *SPLITS]:
        le, rows = read_rows(path)
        changed = missing = 0
        for r in rows:
            if len(r) < 6:
                continue
            new = sentences.get(r[3])
            if new is None:
                missing += 1
            elif r[4] != new:
                r[4] = new
                changed += 1
        write_rows(path, le, rows)
        msg = f"  {path.relative_to(REPO_ROOT)}: {changed} updated"
        if missing:
            msg += f", {missing} missing in JSON"
        print(msg)


if __name__ == "__main__":
    main()
