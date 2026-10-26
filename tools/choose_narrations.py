#!/usr/bin/env python3
"""Chooses, for each of the Forty Hadith, the narration in the six books whose isnad is its chain, and prints the
`narrations` block of tools/data/selection.json and the file pins for `PINNED` in tools/import_hadiths.py.

An-Nawawi quotes the wording of the books, so the chain must be the isnad of the narration whose wording he quotes:
  * the candidates are the narrations holding at least 0.95 of his words, in order, when the collector is one his
    takhrij names, and at least 0.99 for any other of the six books (the very same wording);
  * the best-scoring candidate is taken; one that scores up to 0.02 less is taken instead only when the best one's
    isnad cannot be followed to the Companion (collectors the author names first, then Bukhari, then the others);
  * a hadith with no such narration is not listed and keeps the Companion alone as its chain.

Run it against a local clone of ShathaTm/LK-Hadith-Corpus at the pinned commit:
  python -I tools/choose_narrations.py <path to the clone>
"""
from __future__ import annotations

import csv
import difflib
import hashlib
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import import_hadiths as importer  # noqa: E402

BEST_WITHIN = 0.02
ORDER = ["Bukhari", "Muslim", "Tirmizi", "IbnMaja", "AbuDaud", "Nesai"]


def main(corpus: Path) -> None:
    sys.stdout.reconfigure(encoding="utf-8")
    csv.field_size_limit(2**30)
    snapshot = json.loads(importer.SNAPSHOT.read_text(encoding="utf-8"))
    hadiths = {int(n): importer.arbain_hadith(int(n), 0, record) for n, record in snapshot["arbain"].items()}

    narrations = []
    for folder in ORDER:
        for path in sorted((corpus / folder).glob("*.csv")):
            with open(path, encoding="utf-8-sig", newline="") as handle:
                for row in csv.DictReader(handle):
                    number = importer.corpus_number(row["Hadith_number"])
                    if number is not None:
                        narrations.append({
                            "file": f"{folder}/{path.name}", "hadithNumber": number,
                            "isnad": row["Arabic_Isnad"], "matn": row["Arabic_Matn"],
                            "words": importer.wording(row["Arabic_Matn"]),
                        })

    chosen: dict = {}
    for number, hadith in sorted(hadiths.items()):
        ours = importer.wording(hadith["matnWithTashkeel"])
        wanted = set(ours)
        candidates = []
        for record in narrations:
            if len(wanted & set(record["words"])) / len(wanted) < 0.6:
                continue
            blocks = difflib.SequenceMatcher(None, ours, record["words"], autojunk=False).get_matching_blocks()
            score = sum(block.size for block in blocks) / len(ours)
            folder = record["file"].split("/")[0]
            named = importer.names_collector(folder, hadith["takhrij"])
            if score >= (importer.SAME_WORDING_NAMED if named else importer.SAME_WORDING_OTHER):
                candidates.append((score, named, record))
        if not candidates:
            print(f"{number:>2} -- no narration holds his wording")
            continue
        best = max(score for score, _, _ in candidates)
        pool = [c for c in candidates if c[0] >= best - BEST_WITHIN]
        pool.sort(key=lambda c: (-c[0], not c[1], ORDER.index(c[2]["file"].split("/")[0])))
        pool.sort(key=lambda c: c[0] < best - 1e-9)  # the best first, the near-best after it
        for score, named, record in pool:
            try:
                chain = importer.narration_chain(record, hadith)
            except ValueError as problem:
                print(f"{number:>2}    skipped {record['file']} no. {record['hadithNumber']} ({score:.2f}): {str(problem).split(': ', 1)[-1]}")
                continue
            chosen[number] = {"file": record["file"], "number": record["hadithNumber"]}
            print(f"{number:>2} ok {record['file']} no. {record['hadithNumber']} ({score:.2f}, {'named' if named else 'other'}): "
                  + " > ".join(name for _, name in chain))
            break
        else:
            print(f"{number:>2} -- the best narrations hold his wording but no isnad can be followed")

    print("\nnarrations:")
    print(json.dumps({str(n): v for n, v in chosen.items()}, ensure_ascii=False, indent=2))
    print("\nPINNED files:")
    for file in sorted({v["file"] for v in chosen.values()}):
        print(f'            "{file}": "{hashlib.sha256((corpus / file).read_bytes()).hexdigest()}",')
    print(f"\n{len(chosen)} of {len(hadiths)} hadith")


if __name__ == "__main__":
    main(Path(sys.argv[1]))
