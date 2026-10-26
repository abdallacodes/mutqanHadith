#!/usr/bin/env python3
"""Builds app/src/main/assets/seed_hadiths.json from an audited open dataset, never from memory.

Every hadith text, chain and takhrij in the seed is derived here from one published dataset; nothing
scholarly is typed in by hand. What is curated by hand is only the ids that keep a hadith and a narrator
the same row from one catalog version to the next (tools/data/selection.json), the groups the library is
browsed by (books and their sections, also in selection.json) and how to word the neutral labels of fields no
dataset provides.

The catalog is the Forty Hadith of an-Nawawi and nothing else. Versions 1 to 3 also carried 10 hadiths of
Sahih al-Bukhari (level 3) and 345 of 'Umdat al-Ahkam (level 2); version 4 retired them, and the app removes
them from a database that still holds them (SeedLoader.sync). Version 5 files the hadiths in groups, the shelves the
library shows, and gives each a `position`: its place in the order the whole catalog is taught in.

Source (pinned by commit and by sha256, see PINNED below)
  * fawazahmed0/hadith-api (Unlicense) - `ara-nawawi`: the Forty Hadith of an-Nawawi with tashkeel and the
    author's own takhrij.

Commands (standard library only; run with `python -I tools/import_hadiths.py <command>`)
  fetch   download the pinned file and refresh tools/data/source_snapshot.json (the records used, nothing else)
  build   (default) regenerate the seed from the snapshot and audit it; works offline
  audit   check that the seed on disk is exactly what the snapshot produces, and that it is sound

The snapshot is committed so that the seed can be reproduced and checked without the network, and so that
SeedDataIntegrityTest can prove every matn and every narrator against its source text.
"""
from __future__ import annotations

import hashlib
import json
import re
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SEED = ROOT / "app" / "src" / "main" / "assets" / "seed_hadiths.json"
SNAPSHOT = ROOT / "tools" / "data" / "source_snapshot.json"
SELECTION = ROOT / "tools" / "data" / "selection.json"

CATALOG_VERSION = 5  # keep in step with SeedLoader.CATALOG_VERSION

FAWAZ = "fawazahmed0/hadith-api"
PINNED = {
    FAWAZ: {
        "commit": "df57907be35291c91ad6a6691180e22ca9920784",
        "license": "Unlicense",
        "files": {
            "editions/ara-nawawi.json": "91f559b90422005b768bb449426d87d2e6fabbb43ac3a31e5c24c50ca77465e6",
        },
    },
}

# Neutral label for the one field no audited dataset provides: every chain is the Companion alone.
TIER_COMPANION = "صحابي"

# --------------------------------------------------------------------------------------------------
# Arabic text helpers
# --------------------------------------------------------------------------------------------------

_DIACRITICS = re.compile("[ؐ-ًؚ-ٰٟۖ-ۭ]")
_INVISIBLE = re.compile("[​-‏‪-‮⁦-⁩﻿]")
_FOLD = str.maketrans({"أ": "ا", "إ": "ا", "آ": "ا", "ٱ": "ا", "ى": "ي", "ة": "ه", "ؤ": "و", "ئ": "ي"})
_BREAK = "\u0001"  # stands for an HTML <br> while text is being split


def letters(text: str) -> str:
    """The comparison form: letters only, folded, single-spaced. Two spellings of one text compare equal."""
    text = _INVISIBLE.sub("", _DIACRITICS.sub("", text)).replace("ـ", "").translate(_FOLD)
    return re.sub(r"[^ء-ي]+", " ", text).strip()


def plain(text: str) -> str:
    """The text without tashkeel or invisible marks, otherwise untouched."""
    return re.sub(r"\s+", " ", _INVISIBLE.sub("", _DIACRITICS.sub("", text))).strip()


def tidy(text: str) -> str:
    """Whitespace, invisible marks and HTML breaks only; never a letter or a mark that carries sound."""
    text = _INVISIBLE.sub("", text.replace(_BREAK, " "))
    text = re.sub(r"\s+", " ", text).strip()
    return re.sub(r"\s+\.$", ".", text)


class Mapped:
    """Plain text that remembers where each of its characters came from in the original."""

    def __init__(self, raw: str):
        self.raw = raw
        chars, index = [], []
        for i, ch in enumerate(raw):
            if _DIACRITICS.match(ch) or _INVISIBLE.match(ch):
                continue
            chars.append(ch)
            index.append(i)
        self.text = "".join(chars)
        self._index = index

    def raw_span(self, start: int, end: int) -> str:
        """The original text between plain positions [start, end), tashkeel included."""
        if start >= len(self._index):
            return ""
        stop = self._index[end] if end < len(self._index) else len(self.raw)
        return self.raw[self._index[start]:stop]


def display_name(name: str) -> str:
    """A narrator's name as the source wrote it, without tashkeel; the spelling `عبدالله` is split like
    `عبد الله`, and a leading kunya takes the nominative (`أبي`/`أبا` become `أبو`) wherever it was quoted."""
    name = plain(name)
    name = re.sub(r"\bعبد(ال)", r"عبد \1", name)
    return re.sub(r"^(أب)[يا](?=\s)", r"\1و", name)


def name_key(name: str) -> str:
    return split_abd(letters(name))


def split_abd(text: str) -> str:
    """`عبدالله` and `عبد الله` are one spelling for comparison."""
    return re.sub(r"\bعبد(ال)", r"عبد \1", text)


def comparable(text: str) -> str:
    """[text] in the form names are compared in: `عبدالله` split, and every kunya in the nominative."""
    return re.sub(r"(?<![ء-ي])اب[يا](?![ء-ي])", "ابو", split_abd(ALTERNATIVE_KUNYA.sub("", text)))


def in_order(names: list[str], source: str) -> bool:
    """Every name occurs in [source] after the previous one."""
    position = 0
    found = " " + comparable(source) + " "
    for name in names:
        at = found.find(" " + comparable(name_key(name)) + " ", position)
        if at < 0:
            return False
        position = at + 1
    return True


# --------------------------------------------------------------------------------------------------
# Forty Hadith of an-Nawawi: companion, matn and takhrij from the author's own text
# --------------------------------------------------------------------------------------------------

HONORIFIC = re.compile(r"رضي الله عن(?:ه|ها|هما|هم)(?![ء-ي])")
ALTERNATIVE_KUNYA = re.compile(r"\s+وقيل:?\s+\S+\s+\S+(?=\s+\S+\s+بن\b)")  # `أبي عمرو وقيل: أبي عمرة سفيان بن ...`
DESCRIPTOR = re.compile(r"^(?:خادم رسول الله صلى الله عليه و? ?سلم|أيضا)\s*")
TAIL_MARKERS = [_BREAK, "[رواه", "رواه", "رويناه", "حديث حسن", "حديث صحيح"]
COLLECTORS = {  # token (folded) -> as the takhrij names them
    "بخاري": "البخاري", "مسلم": "مسلم", "ترمذي": "الترمذي", "نسايي": "النسائي", "دارمي": "الدارمي",
    "دارقطني": "الدارقطني", "بيهقي": "البيهقي", "مالك": "مالك", "احمد": "أحمد",
}
REFERENCE = re.compile(r"\[\s*(?:رقم|[\d/])[^\]]*\]")  # `[رقم:2520]`, `[2/246]`: where the collector has it, not who has it
PRECEDED_BY = {"ماجه": ("ابن", "ابن ماجه"), "داود": ("ابو", "أبو داود")}


def split_arbain(raw: str) -> tuple[str, str, str]:
    """(companion, matn, tail): the first companion the text names, what he reported, and the author's
    takhrij note after it. Each is a verbatim slice of the source, cleaned of markup only."""
    mapped = Mapped(raw.replace("<br>", _BREAK).replace("<br/>", _BREAK))
    intro = re.match(r"عن (?P<name>.+?) " + HONORIFIC.pattern, mapped.text)
    if not intro:
        raise ValueError(f"no companion at the start of: {raw[:80]!r}")
    name = re.split(r"\s+(?:سبط|صلى)\s", intro.group("name"))[0]
    name = ALTERNATIVE_KUNYA.sub("", name)
    name = re.split(r"،", name)[0]  # a second companion after "،" is not the primary one
    body_start = intro.end()
    body = mapped.text[body_start:]
    skipped = 0
    while True:
        stripped = body[skipped:]
        lead = len(stripped) - len(stripped.lstrip(" ،:"))
        descriptor = DESCRIPTOR.match(stripped[lead:])
        said = re.match(r"قال[ت]?\s*:?\s*", stripped[lead:])
        if descriptor and descriptor.end():
            skipped += lead + descriptor.end()
        elif said:
            skipped += lead + said.end()
            break
        else:
            skipped += lead
            break
    start = body_start + skipped
    ends = [mapped.text.find(m, start) for m in TAIL_MARKERS]
    end = min((e for e in ends if e >= 0), default=-1)
    if end < 0:
        raise ValueError(f"no takhrij note after the matn of: {raw[:80]!r}")
    return display_name(name), tidy(mapped.raw_span(start, end)), tidy(mapped.raw_span(end, len(mapped.text)))


def takhrij_from_tail(tail: str) -> str:
    """"متفق عليه" when the author cites al-Bukhari and Muslim together, otherwise "رواه" and the collectors he
    names in the order he names them. A note that names none of the known collectors is quoted as he wrote it."""
    tail = re.split(r"\.\s|\s(?=و?في رواية)", plain(tail), maxsplit=1)[0]  # later sentences are other narrations
    tokens = letters(REFERENCE.sub(" ", tail)).split()
    found: list[str] = []
    for i, token in enumerate(tokens):
        base = token[1:] if token.startswith("و") else token
        base = base[2:] if base.startswith("ال") else base
        previous = tokens[i - 1] if i else ""
        if base in PRECEDED_BY and previous == PRECEDED_BY[base][0]:
            found.append(PRECEDED_BY[base][1])
        elif base in COLLECTORS:
            found.append(COLLECTORS[base])
    found = list(dict.fromkeys(found))
    if "البخاري" in found and "مسلم" in found:
        return "متفق عليه"
    if found:
        return "رواه " + " و".join(found)
    clause = re.search(r"رويناه.*", REFERENCE.sub(" ", tail))
    if clause:
        return clause.group(0).strip(" .،")
    raise ValueError(f"no collector in the takhrij note: {tail!r}")


def arbain_hadith(number: int, hadith_id: int, record: dict) -> dict:
    companion, matn, tail = split_arbain(record["text"])
    return {
        "id": hadith_id,
        "matnWithTashkeel": matn,
        "sourceBook": "الأربعون النووية",
        "hadithNumber": number,
        "chapter": "",
        "grade": "",
        "collector": "النووي",
        "level": 1,
        "takhrij": takhrij_from_tail(tail),
        "sahabi": companion,
        "_chain": [("عن", companion)],
        "_source": {"dataset": FAWAZ, "file": "editions/ara-nawawi.json", "hadithNumber": number},
        "_tail": tail,
    }


# --------------------------------------------------------------------------------------------------
# The groups
# --------------------------------------------------------------------------------------------------


def groups_in_order(groups: list[dict]) -> list[dict]:
    """The groups depth first, each one's sections right after it, siblings by `position` then id.

    That is the order the library lists them in and the order the catalog is taught in. A group id is a key in
    users' databases once shipped: it is never renumbered or reused, and a group may be dropped only with its hadiths.
    """
    children: dict = {}
    for group in groups:
        children.setdefault(group["parentId"], []).append(group)
    ordered: list[dict] = []

    def visit(parent) -> None:
        for group in sorted(children.get(parent, []), key=lambda g: (g["position"], g["id"])):
            ordered.append(group)
            visit(group["id"])

    visit(None)
    return ordered


def place(hadiths: list[dict], groups: list[dict], book_groups: dict) -> None:
    """Files every hadith under the group of its book and numbers the catalog's teaching order in `position`."""
    rank = {group["id"]: index for index, group in enumerate(groups_in_order(groups))}
    for hadith in hadiths:
        hadith["groupId"] = book_groups[hadith["sourceBook"]]
    hadiths.sort(key=lambda h: (rank[h["groupId"]], h["hadithNumber"], h["id"]))
    for position, hadith in enumerate(hadiths, start=1):
        hadith["position"] = position


# --------------------------------------------------------------------------------------------------
# The seed
# --------------------------------------------------------------------------------------------------


def build(snapshot: dict, selection: dict) -> dict:
    kept = {int(k): v for k, v in selection["arbainKeepIds"].items()}
    next_id = max(kept.values()) + 1
    hadiths = []
    for number in sorted(int(k) for k in snapshot["arbain"]):
        hadith_id = kept.get(number)
        if hadith_id is None:
            hadith_id, next_id = next_id, next_id + 1
        hadiths.append(arbain_hadith(number, hadith_id, snapshot["arbain"][str(number)]))
    groups = [dict(g) for g in selection["groups"]]
    place(hadiths, groups, selection["bookGroups"])

    # A narrator keeps the row it had, so that the chains and narrator-level history a database already holds stay valid.
    kept_narrators = selection["narratorKeepIds"]
    next_narrator = max(kept_narrators.values()) + 1
    by_key = {}
    for hadith in hadiths:
        for _, name in hadith["_chain"]:
            key = name_key(name)
            if key in by_key:
                continue
            number = kept_narrators.get(name)
            if number is None:
                number, next_narrator = next_narrator, next_narrator + 1
            by_key[key] = {"id": number, "primaryName": name, "fullName": name, "tier": TIER_COMPANION, "isPivot": False}

    out = []
    for hadith in hadiths:
        hadith["sahabi"] = by_key[name_key(hadith["_chain"][-1][1])]["primaryName"]
        sanad = [{"narratorId": by_key[name_key(name)]["id"], "transmissionTerm": term} for term, name in hadith.pop("_chain")]
        item = {k: v for k, v in hadith.items() if not k.startswith("_")}
        item["sanad"] = sanad
        item["source"] = hadith["_source"]
        if "_tail" in hadith:
            item["source"]["takhrijNote"] = hadith["_tail"]
        out.append(item)
    return {
        "catalogVersion": CATALOG_VERSION,
        "provenance": {
            "generatedBy": "tools/import_hadiths.py",
            "sources": {repo: {"commit": pin["commit"], "license": pin["license"]} for repo, pin in PINNED.items()},
            "notes": "No hadith text, chain or takhrij here was written by hand; see tools/import_hadiths.py. "
                     "A chain is the Companion alone, so every narrator is a Companion, and no narrator is flagged as a "
                     "pivot: no audited dataset provides either.",
        },
        "groups": groups_in_order(groups),
        "narrators": sorted(by_key.values(), key=lambda n: n["id"]),
        "hadiths": sorted(out, key=lambda h: h["id"]),
    }


# --------------------------------------------------------------------------------------------------
# Snapshot (fetch) and checks (audit)
# --------------------------------------------------------------------------------------------------


def download(repo: str, path: str) -> bytes:
    url = f"https://raw.githubusercontent.com/{repo}/{PINNED[repo]['commit']}/{path}"
    with urllib.request.urlopen(url, timeout=120) as response:
        data = response.read()
    digest = hashlib.sha256(data).hexdigest()
    if digest != PINNED[repo]["files"][path]:
        raise SystemExit(f"{url}\n  sha256 {digest} is not the pinned {PINNED[repo]['files'][path]}")
    return data


def fetch() -> dict:
    nawawi = json.loads(download(FAWAZ, "editions/ara-nawawi.json"))["hadiths"]
    return {
        "sources": PINNED,
        "arbain": {str(h["hadithnumber"]): {"text": h["text"]} for h in nawawi},
    }


def dump(path: Path, value: dict) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=1, sort_keys=False) + "\n", encoding="utf-8", newline="\n")


def audit_groups(seed: dict) -> list[str]:
    problems = []
    groups = {g["id"]: g for g in seed["groups"]}
    if len(groups) != len(seed["groups"]):
        problems.append("duplicate group ids")
    for group in seed["groups"]:
        for field in ("title", "titleEn"):
            if not group[field].strip():
                problems.append(f"group {group['id']}: empty {field}")
        parent = group["parentId"]
        if parent is not None and parent not in groups:
            problems.append(f"group {group['id']}: unknown parent {parent}")
        seen, at = {group["id"]}, parent
        while at is not None and at in groups:
            if at in seen:
                problems.append(f"group {group['id']}: its parents run in a circle")
                break
            seen.add(at)
            at = groups[at]["parentId"]
    siblings: dict = {}
    for group in seed["groups"]:
        siblings.setdefault(group["parentId"], []).append(group["position"])
    for parent, positions in siblings.items():
        if len(set(positions)) != len(positions):
            problems.append(f"the sections of group {parent} share a position")
    filed = {h["groupId"] for h in seed["hadiths"]}
    if not filed <= set(groups):
        problems.append(f"hadiths filed under unknown groups: {sorted(filed - set(groups))}")
    positions = sorted(h["position"] for h in seed["hadiths"])
    if positions != list(range(1, len(positions) + 1)):
        problems.append("hadith positions are not 1..n")
    if [g["id"] for g in seed["groups"]] != [g["id"] for g in groups_in_order(seed["groups"])]:
        problems.append("the groups are not listed depth first")
    rank = {g["id"]: i for i, g in enumerate(seed["groups"])}
    by_position = sorted(seed["hadiths"], key=lambda h: h["position"])
    if by_position != sorted(by_position, key=lambda h: (rank[h["groupId"]], h["hadithNumber"], h["id"])):
        problems.append("hadith positions do not follow the groups and the numbers")
    return problems


def audit(seed: dict, snapshot: dict) -> list[str]:
    problems = []
    narrators = {n["id"]: n for n in seed["narrators"]}
    if len(narrators) != len(seed["narrators"]):
        problems.append("duplicate narrator ids")
    ids = [h["id"] for h in seed["hadiths"]]
    if len(set(ids)) != len(ids):
        problems.append("duplicate hadith ids")
    if sorted(h["hadithNumber"] for h in seed["hadiths"]) != sorted(int(k) for k in snapshot["arbain"]):
        problems.append("the seed does not hold exactly the hadiths of the snapshot")
    problems += audit_groups(seed)
    used = set()
    for h in seed["hadiths"]:
        label = f"{h['sourceBook']} {h['hadithNumber']}"
        for field in ("matnWithTashkeel", "sourceBook", "takhrij", "sahabi"):
            if not h[field].strip():
                problems.append(f"{label}: empty {field}")
        if h["level"] != 1:
            problems.append(f"{label}: level {h['level']}, the catalog is the Forty Hadith alone")
        if not h["sanad"]:
            problems.append(f"{label}: no chain")
        for link in h["sanad"]:
            used.add(link["narratorId"])
            if link["narratorId"] not in narrators:
                problems.append(f"{label}: unknown narrator {link['narratorId']}")
        if h["sanad"] and narrators[h["sanad"][-1]["narratorId"]]["primaryName"] != h["sahabi"]:
            problems.append(f"{label}: the chain does not end with the Companion {h['sahabi']}")
        source = snapshot["arbain"][str(h["hadithNumber"])]["text"]
        if letters(h["matnWithTashkeel"]) not in letters(source):
            problems.append(f"{label}: the matn is not in its source")
        if not in_order([narrators[l["narratorId"]]["primaryName"] for l in h["sanad"]], letters(source)):
            problems.append(f"{label}: the chain is not in the order of its source")
    if used != set(narrators):
        problems.append(f"narrators without a chain: {sorted(set(narrators) - used)}")
    return problems


def main(argv: list[str]) -> int:
    sys.stdout.reconfigure(encoding="utf-8")
    command = argv[1] if len(argv) > 1 else "build"
    if command == "fetch":
        dump(SNAPSHOT, fetch())
        print(f"wrote {SNAPSHOT.relative_to(ROOT)}")
        return 0
    selection = json.loads(SELECTION.read_text(encoding="utf-8"))
    snapshot = json.loads(SNAPSHOT.read_text(encoding="utf-8"))
    seed = build(snapshot, selection)
    problems = audit(seed, snapshot)
    if command == "build":
        if problems:
            print("\n".join(problems))
            return 1
        dump(SEED, seed)
        print(f"wrote {SEED.relative_to(ROOT)}: {len(seed['hadiths'])} hadiths, {len(seed['narrators'])} narrators")
        return 0
    if command == "audit":
        on_disk = json.loads(SEED.read_text(encoding="utf-8"))
        if on_disk != seed:
            problems.append("seed_hadiths.json is not what the snapshot produces; run `build`")
        print("\n".join(problems) or "seed is sound and reproducible")
        return 1 if problems else 0
    print(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
