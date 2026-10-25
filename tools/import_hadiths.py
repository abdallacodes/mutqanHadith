#!/usr/bin/env python3
"""Builds app/src/main/assets/seed_hadiths.json from audited open datasets, never from memory.

Every hadith text, chain and takhrij in the seed is derived here from one of three published datasets;
nothing scholarly is typed in by hand. What is curated by hand is only *which* hadiths to include
(tools/data/selection.json) and how to word the neutral labels of fields no dataset provides.

Sources (pinned by commit and by sha256, see PINNED below)
  * fawazahmed0/hadith-api (Unlicense) - `ara-nawawi`: the Forty Hadith of an-Nawawi with tashkeel and the
    author's own takhrij; `ara-bukhari`: Sahih al-Bukhari, full text with tashkeel (numbering of Fath al-Bari).
  * ShathaTm/LK-Hadith-Corpus, Bukhari folder (extracted from Sunnah.com, manually checked by its authors):
    the same hadiths with the isnad separated from the matn, chapter and grade.
    Cite: Altammami, Atwell & Alsalka, "The Arabic-English Parallel Corpus of Authentic Hadith", IJASAT 2019.
  * okabe-r/Umdat-al-Ahkam - `hadiths.json`: the 414 numbered hadiths of 'Umdat al-Ahkam (Abd al-Ghani al-Maqdisi) with
    tashkeel, book, chapter and a Companion label. A small personal repository with no licence and no stated edition:
    a source that audited nothing, so nothing of it is trusted until it is proven. A record is imported only if its
    main narration is found, at 0.80 or better of its words in order, in a narration of Sahih al-Bukhari or Sahih
    Muslim in the audited hadith-api editions above (`ara-bukhari`, `ara-muslim`). Those editions also decide the
    takhrij: a hadith is "agreed upon" when both hold it (at 0.50 or better of its content words, since the two books
    often word one hadith differently), otherwise it is the one collector's. A record with a stray non-Arabic character,
    no Companion named in its text, or no such match is left out and reported, never repaired.

Commands (standard library only; run with `python -I tools/import_hadiths.py <command>`)
  fetch   download the pinned files and refresh tools/data/source_snapshot.json (the records used, nothing else)
  build   (default) regenerate the seed from the snapshot and audit it; works offline
  audit   check that the seed on disk is exactly what the snapshot produces, and that it is sound

The snapshot is committed so that the seed can be reproduced and checked without the network, and so that
SeedDataIntegrityTest can prove every matn and every narrator against its source text.
"""
from __future__ import annotations

import csv
import difflib
import hashlib
import io
import json
import re
import sys
import urllib.request
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SEED = ROOT / "app" / "src" / "main" / "assets" / "seed_hadiths.json"
SNAPSHOT = ROOT / "tools" / "data" / "source_snapshot.json"
SELECTION = ROOT / "tools" / "data" / "selection.json"

CATALOG_VERSION = 3  # keep in step with SeedLoader.CATALOG_VERSION
SAME_HADITH = 0.75

FAWAZ = "fawazahmed0/hadith-api"
LK = "ShathaTm/LK-Hadith-Corpus"
UMDAT = "okabe-r/Umdat-al-Ahkam"
PINNED = {
    FAWAZ: {
        "commit": "df57907be35291c91ad6a6691180e22ca9920784",
        "license": "Unlicense",
        "files": {
            "editions/ara-nawawi.json": "91f559b90422005b768bb449426d87d2e6fabbb43ac3a31e5c24c50ca77465e6",
            "editions/ara-bukhari.json": "e34a3402889ca378871da3c5984b7875c680920e93f1c8738ac7afe502179562",
            "editions/ara-muslim.json": "194073b24090c5368e4d14a3f55c9e0ef144c7437bd7e5aa83aa4d2bc54a8ea0",
        },
    },
    LK: {
        "commit": "c45fa64aa40bf1d4a34b9b35359640a583b02881",
        "license": "none stated in the repository; attribution requested by its README",
        "files": {
            "Bukhari/Chapter1.csv": "86cca5c898ea9966f29fc9faac9b528f14c987a5cb8d35c8281c41a7ea986bfc",
            "Bukhari/Chapter2.csv": "a562355e061ed98256d61ca6a9884996cac4b128fcd7a03638893121e9ba3acc",
            "Bukhari/Chapter4.csv": "c5d4b6630100f060ffa01bc052361c781c2a1994339248e061eb88f8397fe541",
            "Bukhari/Chapter10.csv": "398c5cff14d69e2350113c57d32646dc7ee75f6abb36581b340813a6e3c1c3c5",
            "Bukhari/Chapter11.csv": "f7e4596df57ac168755d82bd4c2a470a297bcebcc7cec6599e6c706901961326",
        },
    },
    UMDAT: {
        "commit": "6ab619645814548442635d12f61bfd5ea5c3be5d",
        "license": "none stated in the repository; its text is a classical work, its edition and typesetting are unattributed",
        "files": {
            "hadiths.json": "8b2c957fa46e0307eebfddbc29513b5842d39287cb0d741169ae7415a7570321",
        },
    },
}

# Neutral labels for the fields no audited dataset provides.
TIER_COMPANION = "صحابي"
TIER_COLLECTOR_TEACHER = "شيخ المصنف"
TIER_NARRATOR = "راوٍ"

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


# --------------------------------------------------------------------------------------------------
# Sahih al-Bukhari: chain from the LK corpus, text cross-checked against the hadith-api edition
# --------------------------------------------------------------------------------------------------

TERMS = {"حدثنا", "حدثني", "أخبرنا", "أخبرني", "أنبأنا", "أنبأني", "سمع", "سمعت", "عن"}
SKIPPABLE = {"قال", "يقول", "أن", "أنه", "و"}
HONORIFIC_MARK = "ـ"
TAHWIL = re.compile(r"(?:^|\s)ح(?:\s|$)")
SAME_AS = re.compile(r"\b(?:نحوه|مثله|بمثله|بنحوه)\b")


def parse_isnad(isnad: str) -> list[tuple[str, str]]:
    """[(transmission term, narrator name)] from the collector's side to the Companion.

    Follows the first chain only: a taḥwīl (`ح`) hands over to a second chain whose tail repeats the first,
    so the primary chain is the first one up to the taḥwīl plus whatever follows `نحوه`; a hadith that
    goes on to a second chain after `عن النبي` is cut there."""
    text = plain(isnad)
    text = re.split(r"\s+عن\s+النبي\b", text, maxsplit=1)[0]
    switch = TAHWIL.search(text)
    if switch:
        head, rest = text[:switch.start()], text[switch.end():]
        same = SAME_AS.search(rest)
        if not same:
            raise ValueError(f"taḥwīl without a shared tail: {isnad!r}")
        text = head + " " + rest[same.end():]
    chain = []
    for segment in re.split(r"[،,]", text):
        tokens = segment.split()
        term = None
        while tokens and (tokens[0] in TERMS or tokens[0] in SKIPPABLE):
            if tokens[0] in TERMS:
                term = tokens[0]
            tokens.pop(0)
        name = []
        for token in tokens:
            if token == HONORIFIC_MARK or token in ("قال", "يقول"):
                break
            name.append(token)
        if not name:
            continue
        if term is None:
            raise ValueError(f"no transmission term before {' '.join(name)!r} in {isnad!r}")
        chain.append((term, display_name(" ".join(name))))
    return chain


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


def collapse_repeats(text: str) -> str:
    return re.sub(r"\b(\S+)( \1\b)+", r"\1", text)


def bukhari_hadith(entry: dict, record: dict, fawaz_text: str) -> dict:
    matn = tidy(record["matn"])
    # Two datasets, one text: the corpus' isnad + matn must be the edition's text. The only differences
    # tolerated are a word the corpus repeats at the seam and the spelling `عبدالله`.
    corpus = collapse_repeats(letters(record["isnad"] + " " + record["matn"]))
    edition = collapse_repeats(letters(fawaz_text))
    corpus, edition = (re.sub(r"\bعبد(ال)", r"عبد \1", t) for t in (corpus, edition))
    if corpus != edition:
        raise ValueError(f"Bukhari {entry['number']}: the two datasets disagree\n  {corpus}\n  {edition}")
    chain = parse_isnad(record["isnad"])
    if len(chain) < 2 or not in_order([n for _, n in chain], letters(record["isnad"])):
        raise ValueError(f"Bukhari {entry['number']}: the chain does not follow the isnad: {chain}")
    return {
        "id": entry["id"],
        "matnWithTashkeel": matn,
        "sourceBook": "صحيح البخاري",
        "hadithNumber": entry["number"],
        "chapter": plain(record["chapter"]),
        "grade": plain(record["grade"]),
        "collector": "البخاري",
        "level": 3,
        "takhrij": "رواه البخاري",
        "sahabi": chain[-1][1],
        "_chain": chain,
        "_source": {
            "dataset": LK, "file": entry["file"], "hadithNumber": entry["number"],
            "textCheckedAgainst": f"{FAWAZ}:editions/ara-bukhari.json",
        },
    }


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
# 'Umdat al-Ahkam: the Companion and the matn from the dataset, the authenticity from the two Sahihs
# --------------------------------------------------------------------------------------------------

VERIFIED = 0.80  # a record is imported if this share of its matn's words is found, in order, in a Sahih narration: chance gives ~0.34, 0.50 at the 90th percentile
FOUND = 0.50  # a Sahih holds the hadith if this share of the matn's content words is found in order in one of its narrations: chance gives ~0.11, 0.33 at the 95th percentile
COMMON = 0.02  # a word is not a content word when it is in more than this share of the narrations of the two Sahihs
MIN_WORDS = 6  # a shorter matn can be found in order almost anywhere
NUMBER_PREFIX = re.compile(r"^\s*[٠-٩0-9]+\s*-\s*")
ANTECEDENT = re.compile(r"^وعن(?:ه|ها|هما|هم)(?:\s*،?\s*عن(?:ه|ها|هما|هم))?$")  # `وَعَنْهُ`: the Companion of the hadith before
LINK_SPLIT = re.compile(r"(?:^|\s|،)(?:و?عن|عمن)\s+")  # each `عَنْ` starts a link of the chain
REPORT_VERB = re.compile(r"\s*(?:[،:]\s*)?(?:(?:قال[تا]?|قالوا)\s*:\s*|قال[تا]?\s+(?=«))?")
NOT_A_NAME = re.compile(r"(?:^|\s)(?:قال|قالت|أن|أنه|أنها)(?:\s|$)")
FORMULA = re.compile(
    r"(?<![ء-ي])(?:صلي الله عليه و ?سلم|رضي الله عن(?:ه|ها|هما|هم|هن)|عليه السلام|عز وجل|تبارك وتعالي)(?![ء-ي])")
SHORTLIST = 60


class Skip(Exception):
    """A dataset record that is left out, and why."""


def tokens(name: str) -> list[str]:
    return ["بن" if t == "ابن" else t for t in comparable(letters(name)).split()]


def same_companion(label: str, written: str) -> bool:
    """The dataset's label for a Companion and the name the hadith's own text gives him: the same name, one
    the beginning of the other, or the shorter one a part of the longer in order (`ابن عباس`, `عبد الله بن عباس`)."""
    want, have = tokens(label), tokens(written)
    if not want or not have:
        return False
    if have[:len(want)] == want:
        return True
    if len(have) < 2 and have[0] != want[0]:
        return False  # one word is a name only when it opens the fuller one (`حذيفة`, `حذيفة بن اليمان`)
    rest = iter(want)
    return all(token in rest for token in have)


def match_words(text: str) -> list[str]:
    """The words a matn is compared in with a Sahih narration: letters folded, the formulae of blessing left out."""
    return FORMULA.sub(" ", letters(text.replace("ﷺ", " صلى الله عليه وسلم "))).split()


def containment(words: list[str], source: list[str]) -> float:
    """The share of [words] found in [source], in order."""
    blocks = difflib.SequenceMatcher(None, words, source, autojunk=False).get_matching_blocks()
    return sum(block.size for block in blocks) / max(len(words), 1)


class Umdat:
    """The records of the dataset (or of the snapshot, which keeps them as they are) read as hadiths."""

    def __init__(self, records: dict):
        self.records = records
        self._companions: dict[int, object] = {}

    def paragraphs(self, number: int) -> list[str]:
        return [p.strip() for p in self.records[str(number)]["text"].split("\n") if p.strip()]

    def _intro(self, number: int):
        first = NUMBER_PREFIX.sub("", self.paragraphs(number)[0], count=1)
        mapped = Mapped(first)
        found = re.match(r"(?P<names>.+?)\s+" + HONORIFIC.pattern, mapped.text)
        if not found or any(mark in found.group("names") for mark in "«:؛") or NOT_A_NAME.search(found.group("names")):
            raise Skip("no Companion is named in front of the blessing")
        return mapped, found

    def companion(self, number: int) -> dict:
        """{label, from, written, links}: the Companion as the dataset labels him, the record whose text names him
        (this one, or the one `وَعَنْهُ` points back to), the name as that text writes it, and the narrators the text
        puts before him."""
        if number not in self._companions:
            try:
                self._companions[number] = self._resolve(number)
            except Skip as why:
                self._companions[number] = why
        value = self._companions[number]
        if isinstance(value, Skip):
            raise value
        return value

    def _resolve(self, number: int) -> dict:
        label = display_name(self.records[str(number)]["sahabi"][0])
        _, found = self._intro(number)
        names = found.group("names")
        if ANTECEDENT.match(names):
            if str(number - 1) not in self.records:
                raise Skip("`وعنه` with no hadith before it")
            before = self.companion(number - 1)
            if before["label"] != label:
                raise Skip(f"`وعنه` points to {before['label']} but the dataset labels {label}")
            return {"label": label, "from": before["from"], "written": before["written"], "links": []}
        links = [link.strip(" ،") for link in LINK_SPLIT.split(names) if link.strip(" ،")]
        if not links:
            raise Skip("the chain in front of the blessing has no narrator")
        written = display_name(links[-1].split("،")[0])
        if not same_companion(label, written):
            raise Skip(f"the dataset labels {label} but the text says {links[-1]!r}")
        for link in links[:-1]:
            if "،" in link or len(link.split()) > 9:
                raise Skip(f"a link with more than one narrator: {link!r}")
        return {"label": label, "from": number, "written": written, "links": [display_name(link) for link in links[:-1]]}

    def parse(self, number: int) -> dict:
        """{label, from, links, matn, variants} or Skip. The matn is the main narration of the record, as the
        dataset writes it: the book's own variants (`وفي لفظ`, `ولمسلم`) are the lines after it."""
        who = self.companion(number)
        mapped, found = self._intro(number)
        body = mapped.text[found.end():]
        start = found.end() + REPORT_VERB.match(body).end()
        matn = tidy(mapped.raw_span(start, len(mapped.text)))
        if re.search(r"[A-Za-z*]", matn):
            raise Skip("a stray non-Latin-script character in the dataset's text (a transcription error)")
        if matn.count("{") != matn.count("}"):
            raise Skip("unbalanced braces in the dataset's text (a transcription error)")
        if len(match_words(matn)) < MIN_WORDS:
            raise Skip("too short to be verified against a Sahih")
        return {**who, "matn": matn, "variants": [tidy(p) for p in self.paragraphs(number)[1:]]}


def sahih_index(sahih: dict) -> tuple[dict, list[str]]:
    """({collector: ([(number, text, words)], {word pair: indexes})}, the common words): every narration of the two
    Sahihs, searchable, and the words that more than COMMON of them share (they say nothing about which hadith it is)."""
    out, seen, total = {}, Counter(), 0
    for collector, narrations in sahih.items():
        rows = [(n["hadithnumber"], n["text"], match_words(n["text"])) for n in narrations if n["text"].strip()]
        pairs: dict[str, set[int]] = {}
        for i, (_, _, words) in enumerate(rows):
            seen.update(set(words))
            for j in range(len(words) - 1):
                pairs.setdefault(words[j] + " " + words[j + 1], set()).add(i)
        total += len(rows)
        out[collector] = (rows, pairs)
    return out, sorted(word for word, n in seen.items() if n / total > COMMON)


def content(words: list[str], common: set[str]) -> list[str]:
    return [word for word in words if word not in common]


def scores(words: list[str], source: list[str], common: set[str]) -> tuple[float, float]:
    """(all words, content words): the share of the matn found in order in the source narration."""
    return containment(words, source), containment(content(words, common), content(source, common))


def verify(matn: str, index: dict, common: set[str]) -> dict:
    """{collector: {hadithNumber, score, content, text}} for each Sahih with a narration that holds the hadith (FOUND)."""
    words = match_words(matn)
    found = {}
    for collector, (rows, pairs) in index.items():
        votes: dict[int, int] = {}
        for j in range(len(words) - 1):
            for i in pairs.get(words[j] + " " + words[j + 1], ()):
                votes[i] = votes.get(i, 0) + 1
        best = None
        for i in sorted(votes, key=lambda k: -votes[k])[:SHORTLIST]:
            every, core = scores(words, rows[i][2], common)
            if best is None or (core, every) > best[:2]:
                best = (core, every, i)
        if best and best[0] >= FOUND:
            number, text, _ = rows[best[2]]
            found[collector] = {"hadithNumber": number, "score": round(best[1], 4), "content": round(best[0], 4), "text": text}
    return found


def umdat_takhrij(verified: dict) -> str:
    if "bukhari" in verified and "muslim" in verified:
        return "متفق عليه"
    return "رواه البخاري" if "bukhari" in verified else "رواه مسلم"


def umdat_hadiths(snapshot: dict, selection: dict) -> tuple[list[dict], dict[str, str]]:
    """The hadiths of Level 2 and, for each record left out, why."""
    book = Umdat(snapshot["umdat"])
    offset = selection["umdat"]["idOffset"]
    items, skipped = [], {}
    for number in sorted(snapshot["umdat"], key=int):
        record = snapshot["umdat"][number]
        try:
            parsed = book.parse(int(number))
            if not any(v["score"] >= VERIFIED for v in record["verified"].values()):
                raise Skip(f"its main narration is in neither Sahih at {VERIFIED} or better")
        except Skip as why:
            skipped[number] = str(why)
            continue
        chapter = record["book"] if record["section"] in ("", record["book"]) else f"{record['book']} - {record['section']}"
        verified = record["verified"]
        items.append({
            "id": offset + int(number),
            "matnWithTashkeel": parsed["matn"],
            "sourceBook": "عمدة الأحكام",
            "hadithNumber": int(number),
            "chapter": chapter,
            "grade": "",
            "collector": "عبد الغني المقدسي",
            "level": 2,
            "takhrij": umdat_takhrij(verified),
            "sahabi": parsed["label"],
            "_chain": [("عن", name) for name in parsed["links"]] + [("عن", parsed["label"])],
            "_source": {
                "dataset": UMDAT, "file": "hadiths.json", "hadithNumber": int(number),
                "companionFrom": parsed["from"], "companionWrittenAs": parsed["written"],
                "verifiedIn": {
                    c: {"dataset": FAWAZ, "file": f"editions/ara-{c}.json", "hadithNumber": v["hadithNumber"],
                        "matnMatch": v["score"], "contentMatch": v["content"]}
                    for c, v in sorted(verified.items())
                },
                "variants": parsed["variants"],
            },
        })
    return items, skipped


# --------------------------------------------------------------------------------------------------
# The seed
# --------------------------------------------------------------------------------------------------


def build(snapshot: dict, selection: dict) -> dict:
    hadiths = []
    for entry in selection["bukhari"]:
        number = str(entry["number"])
        hadiths.append(bukhari_hadith(entry, snapshot["bukhariCorpus"][number], snapshot["bukhariEdition"][number]["text"]))
    kept = {int(k): v for k, v in selection["arbainKeepIds"].items()}
    next_id = max([e["id"] for e in selection["bukhari"]] + list(kept.values())) + 1
    for number in sorted(int(k) for k in snapshot["arbain"]):
        hadith_id = kept.get(number)
        if hadith_id is None:
            hadith_id, next_id = next_id, next_id + 1
        hadiths.append(arbain_hadith(number, hadith_id, snapshot["arbain"][str(number)]))
    hadiths.sort(key=lambda h: h["id"])
    upgrade_takhrij(hadiths)
    level_two, skipped = umdat_hadiths(snapshot, selection)
    hadiths.extend(level_two)
    hadiths.sort(key=lambda h: h["id"])

    narrators: dict[str, dict] = {}
    for hadith in hadiths:
        last = len(hadith["_chain"]) - 1
        for position, (_, name) in enumerate(hadith["_chain"]):
            entry = narrators.setdefault(name_key(name), {"primaryName": name, "roles": set()})
            if position == last:
                entry["roles"].add(TIER_COMPANION)
            elif position == 0 and hadith["level"] == 3:
                entry["roles"].add(TIER_COLLECTOR_TEACHER)
    by_key = {}
    for number, (key, entry) in enumerate(narrators.items(), start=1):
        tier = TIER_COMPANION if TIER_COMPANION in entry["roles"] else (
            TIER_COLLECTOR_TEACHER if TIER_COLLECTOR_TEACHER in entry["roles"] else TIER_NARRATOR)
        by_key[key] = {
            "id": number, "primaryName": entry["primaryName"], "fullName": entry["primaryName"],
            "tier": tier, "isPivot": False,
        }

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
            "notes": "No hadith text, chain, grade or takhrij here was written by hand; see tools/import_hadiths.py. "
                     "Narrator tier is structural (the last narrator is the Companion, the first of a Bukhari chain is "
                     "the collector's teacher) and no narrator is flagged as a pivot: no audited dataset provides either. "
                     "Level 2 ('Umdat al-Ahkam) is the main narration of each record of a single unlicensed dataset, kept only "
                     "where it was found in Sahih al-Bukhari or Sahih Muslim; its numbering is that dataset's.",
            "umdatAlAhkam": {
                "records": len(snapshot["umdat"]),
                "imported": len(level_two),
                "leftOut": dict(sorted(Counter(skipped.values()).items(), key=lambda kv: -kv[1])),
            },
        },
        "narrators": list(by_key.values()),
        "hadiths": out,
    }


def upgrade_takhrij(hadiths: list[dict]) -> None:
    """A Bukhari hadith is "agreed upon" when an-Nawawi cites al-Bukhari and Muslim together for the same
    hadith. "The same" is decided on the matn alone: the word sequences must match at 0.75 or better (the
    matching pairs here score 0.81-0.91, the nearest unrelated pair 0.54)."""
    for hadith in hadiths:
        if hadith["level"] != 3:
            continue
        for other in hadiths:
            if other["level"] != 1 or other["takhrij"] != "متفق عليه":
                continue
            ratio = difflib.SequenceMatcher(
                None, letters(hadith["matnWithTashkeel"]).split(), letters(other["matnWithTashkeel"]).split(), autojunk=False
            ).ratio()
            if ratio >= SAME_HADITH:
                hadith["takhrij"] = "متفق عليه"
                hadith["_source"]["takhrijBasis"] = (
                    f"an-Nawawi {other['hadithNumber']} cites al-Bukhari and Muslim for this hadith (matn match {ratio:.2f})")


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


def lk_number(cell: str) -> int | None:
    match = re.fullmatch(r"\s*(\d+)(?:\.0)?\s*", cell)
    return int(match.group(1)) if match else None


def fetch(selection: dict) -> dict:
    nawawi = json.loads(download(FAWAZ, "editions/ara-nawawi.json"))["hadiths"]
    edition = {str(h["hadithnumber"]): h for h in json.loads(download(FAWAZ, "editions/ara-bukhari.json"))["hadiths"]}
    corpus = {}
    for entry in selection["bukhari"]:
        rows = csv.DictReader(io.StringIO(download(LK, entry["file"]).decode("utf-8-sig"), newline=""))
        row = next((r for r in rows if lk_number(r["Hadith_number"]) == entry["number"]), None)
        if row is None:
            raise SystemExit(f"Bukhari {entry['number']} is not in {entry['file']}")
        corpus[str(entry["number"])] = {
            "file": entry["file"], "chapter": row["Chapter_Arabic"], "section": row["Section_Arabic"],
            "isnad": row["Arabic_Isnad"], "matn": row["Arabic_Matn"], "grade": row["Arabic_Grade"],
            "narrated": row["English_Isnad"],
        }
    return {
        "sources": PINNED,
        "arbain": {str(h["hadithnumber"]): {"text": h["text"]} for h in nawawi},
        "bukhariEdition": {str(e["number"]): {"text": edition[str(e["number"])]["text"]} for e in selection["bukhari"]},
        "bukhariCorpus": corpus,
        **fetch_umdat(),
    }


def fetch_umdat() -> dict:
    """Every record of the dataset as it is, and for each that reads as a hadith the Sahih narrations that hold its matn."""
    records = json.loads(download(UMDAT, "hadiths.json"))
    index, common = sahih_index({
        "bukhari": json.loads(download(FAWAZ, "editions/ara-bukhari.json"))["hadiths"],
        "muslim": json.loads(download(FAWAZ, "editions/ara-muslim.json"))["hadiths"],
    })
    book = Umdat(records)
    out = {}
    for number in sorted(records, key=int):
        record = records[number]
        try:
            verified = verify(book.parse(int(number))["matn"], index, set(common))
        except Skip:
            verified = {}
        out[number] = {
            "text": record["text"], "sahabi": record["sahabi"], "book": record["book"], "section": record["section"],
            "verified": verified,
        }
    return {"umdat": out, "umdatCommonWords": common}


def dump(path: Path, value: dict) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=1, sort_keys=False) + "\n", encoding="utf-8", newline="\n")


def audit_umdat(h: dict, label: str, narrators: dict, snapshot: dict) -> list[str]:
    """A Level 2 hadith: its matn is in its record, its chain is what the record says, and each Sahih it cites holds it."""
    problems = []
    record = snapshot["umdat"][str(h["hadithNumber"])]
    own, source = record["text"], h["source"]
    if letters(h["matnWithTashkeel"]) not in letters(own):
        problems.append(f"{label}: the matn is not in its record")
    names = [narrators[link["narratorId"]]["primaryName"] for link in h["sanad"]]
    if not in_order(names[:-1], letters(own)):
        problems.append(f"{label}: the narrators in front of the Companion are not in the order of the record")
    try:
        who = Umdat(snapshot["umdat"]).companion(source["companionFrom"])
        if tokens(who["label"]) != tokens(h["sahabi"]):
            problems.append(f"{label}: the Companion is {who['label']} in the record that names him, not {h['sahabi']}")
    except Skip as why:
        problems.append(f"{label}: the record that names the Companion does not: {why}")
    if not source["verifiedIn"]:
        problems.append(f"{label}: found in neither Sahih")
    words, common = match_words(h["matnWithTashkeel"]), set(snapshot["umdatCommonWords"])
    if not any(proof["matnMatch"] >= VERIFIED for proof in source["verifiedIn"].values()):
        problems.append(f"{label}: no Sahih holds its words at {VERIFIED}")
    for collector, proof in source["verifiedIn"].items():
        stored = record["verified"][collector]
        every, core = scores(words, match_words(stored["text"]), common)
        if core < FOUND or abs(every - proof["matnMatch"]) > 1e-3 or abs(core - proof["contentMatch"]) > 1e-3 \
                or stored["hadithNumber"] != proof["hadithNumber"]:
            problems.append(f"{label}: {collector} {proof['hadithNumber']} holds the matn at {every:.2f} / {core:.2f}, not {proof['matnMatch']} / {proof['contentMatch']}")
    if h["takhrij"] != umdat_takhrij(source["verifiedIn"]):
        problems.append(f"{label}: takhrij {h['takhrij']} is not what the Sahihs it was found in say")
    return problems


def audit(seed: dict, snapshot: dict) -> list[str]:
    problems = []
    narrators = {n["id"]: n for n in seed["narrators"]}
    if len(narrators) != len(seed["narrators"]):
        problems.append("duplicate narrator ids")
    used = set()
    for h in seed["hadiths"]:
        label = f"{h['sourceBook']} {h['hadithNumber']}"
        for field in ("matnWithTashkeel", "sourceBook", "takhrij", "sahabi"):
            if not h[field].strip():
                problems.append(f"{label}: empty {field}")
        if not h["sanad"]:
            problems.append(f"{label}: no chain")
        for link in h["sanad"]:
            used.add(link["narratorId"])
            if link["narratorId"] not in narrators:
                problems.append(f"{label}: unknown narrator {link['narratorId']}")
        if h["sanad"] and narrators[h["sanad"][-1]["narratorId"]]["primaryName"] != h["sahabi"]:
            problems.append(f"{label}: the chain does not end with the Companion {h['sahabi']}")
        if h["level"] == 2:
            problems.extend(audit_umdat(h, label, narrators, snapshot))
            continue
        source = snapshot["arbain"][str(h["hadithNumber"])]["text"] if h["level"] == 1 else snapshot["bukhariEdition"][str(h["hadithNumber"])]["text"]
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
    selection = json.loads(SELECTION.read_text(encoding="utf-8"))
    if command == "fetch":
        dump(SNAPSHOT, fetch(selection))
        print(f"wrote {SNAPSHOT.relative_to(ROOT)}")
        return 0
    snapshot = json.loads(SNAPSHOT.read_text(encoding="utf-8"))
    seed = build(snapshot, selection)
    problems = audit(seed, snapshot)
    if command == "build":
        if problems:
            print("\n".join(problems))
            return 1
        dump(SEED, seed)
        print(f"wrote {SEED.relative_to(ROOT)}: {len(seed['hadiths'])} hadiths, {len(seed['narrators'])} narrators")
        report = seed["provenance"]["umdatAlAhkam"]
        print(f"'Umdat al-Ahkam: {report['imported']} of {report['records']} records imported; left out:")
        for reason, count in report["leftOut"].items():
            print(f"  {count:4d}  {reason}")
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
