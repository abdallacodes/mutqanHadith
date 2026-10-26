#!/usr/bin/env python3
"""Builds app/src/main/assets/seed_hadiths.json from audited open datasets, never from memory.

Every hadith text, chain and takhrij in the seed is derived here from published datasets; nothing
scholarly is typed in by hand. What is curated by hand is only the ids that keep a hadith and a narrator
the same row from one catalog version to the next (tools/data/selection.json), the groups the library is
browsed by (books and their sections, also in selection.json), which narration of a collector holds the chain of
each hadith (`narrations`, also in selection.json) and how to word the neutral labels of fields no dataset provides.

The catalog is the Forty Hadith of an-Nawawi and nothing else. Versions 1 to 3 also carried 10 hadiths of
Sahih al-Bukhari (level 3) and 345 of 'Umdat al-Ahkam (level 2); version 4 retired them, and the app removes
them from a database that still holds them (SeedLoader.sync). Version 5 files the hadiths in groups, the shelves the
library shows, and gives each a `position`: its place in the order the whole catalog is taught in. Version 6 gives
the hadiths their full chains.

Sources (pinned by commit and by sha256, see PINNED below)
  * fawazahmed0/hadith-api (Unlicense) - `ara-nawawi`: the Forty Hadith of an-Nawawi with tashkeel and the
    author's own takhrij. An-Nawawi gives the Companion alone, so this is where the matn, the takhrij and the
    Companion come from.
  * ShathaTm/LK-Hadith-Corpus (extracted from Sunnah.com, manually checked by its authors): the six books with the
    isnad separated from the matn. The chain of a hadith is the isnad of one narration of it, read from here: from the
    collector's teacher down to the Companion.
    Cite: Altammami, Atwell & Alsalka, "The Arabic-English Parallel Corpus of Authentic Hadith", IJASAT 2019.

A chain is taken only when it can be followed with certainty. An-Nawawi quotes the wording of the books, so the
narration is the one whose wording he quotes (tools/choose_narrations.py picks it; `narrations` in selection.json names
it) and the build refuses it unless
  * it holds the same wording: at 0.85 or better of the matn's words in order (blessings ignored) in the narration of a
    collector the author names, at 0.99 or better in the narration of any other of the six books, and no narration
    that holds his wording better has been passed over (the book is shown with the chain);
  * its isnad is one line of named narrators and ends with the Companion the author names. A taḥwīl (`ح`) or `وحدثنا`
    starts a parallel chain, which is joined to the first at the first narrator they share; of several narrators joined
    by `و` the first named is followed; a narrator given as `أبيه`, a chain that goes on inside the matn, or one that
    names a narrator twice is left out, never repaired.
A hadith with no such narration keeps the Companion alone as its chain.

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
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SEED = ROOT / "app" / "src" / "main" / "assets" / "seed_hadiths.json"
SNAPSHOT = ROOT / "tools" / "data" / "source_snapshot.json"
SELECTION = ROOT / "tools" / "data" / "selection.json"

CATALOG_VERSION = 6  # keep in step with SeedLoader.CATALOG_VERSION

FAWAZ = "fawazahmed0/hadith-api"
LK = "ShathaTm/LK-Hadith-Corpus"
PINNED = {
    FAWAZ: {
        "commit": "df57907be35291c91ad6a6691180e22ca9920784",
        "license": "Unlicense",
        "files": {
            "editions/ara-nawawi.json": "91f559b90422005b768bb449426d87d2e6fabbb43ac3a31e5c24c50ca77465e6",
        },
    },
    LK: {
        "commit": "c45fa64aa40bf1d4a34b9b35359640a583b02881",
        "license": "none stated in the repository; attribution requested by its README",
        "files": {
            "Bukhari/Chapter2.csv": "a562355e061ed98256d61ca6a9884996cac4b128fcd7a03638893121e9ba3acc",
            "Bukhari/Chapter78.csv": "ee8d0a3778f9d3da296a3c1f651ab1595013232d945d94b2674a2b9ad51773a3",
            "Bukhari/Chapter81.csv": "211dff89714398f7fa2462e10a42f7c7e50ef35e58ad80d62e0d57314102f8a5",
            "Bukhari/Chapter83.csv": "1156a84362f3730ff7a301b9186bbd9ffe3e84242d9831db3552b76e3aca1fc2",
            "Muslim/Chapter1.csv": "170d51c396fdf41c80c26447f6bcb5cc315507adfc17ee510943af81803e8e02",
            "Muslim/Chapter12.csv": "f014b720b08e4e71f3d32e3565ccbf5b60c5a08087a8d8bdd2f55ebbc54da790",
            "Muslim/Chapter45.csv": "c9641b5cfcbd39536a464074207ee28ad5923551226604ef62f44ab748a4f852",
            "Nesai/Chapter43.csv": "011bed82b233334e2601ce925c19353e437053ebfad35c636dfd841a39649e04",
            "Tirmizi/Chapter16.csv": "adcb98f84c86334fd8443ccfe592c929cdcc0a6d12f6a7b9fa4ef1533683255e",
            "Tirmizi/Chapter27.csv": "a1543bf6e292880f665d7ab69c6ee8a5b11a0902e67c2c8579b9b0c7ca0e2cf9",
            "Tirmizi/Chapter36.csv": "686ea72f188e717ea20a7cbad13113fc634b8a49bb9c322491377f9d2fcc1f73",
            "Tirmizi/Chapter37.csv": "b604e1a26be275abfeae4fda1fa86eef8656666b99d6f850d61178b7a4889bdb",
            "Tirmizi/Chapter40.csv": "a57ff58315cbcbfb590902bd84b91c51712d5ef580d32267d70ae46a3f049391",
            "Tirmizi/Chapter48.csv": "76d956803d61bb634100011e268c2fbe64f35cad144e6ee30572b16bfc4b8e0d",
        },
    },
}

# Neutral labels for the field no audited dataset provides: a narrator's tier is structural only.
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
# The chains: the isnad of one narration of the hadith, from the collector's teacher to the Companion
# --------------------------------------------------------------------------------------------------

BOOKS = {  # corpus folder -> (the book as the app names it, the collector as a takhrij names him)
    "Bukhari": ("صحيح البخاري", "البخاري"),
    "Muslim": ("صحيح مسلم", "مسلم"),
    "Tirmizi": ("سنن الترمذي", "الترمذي"),
    "IbnMaja": ("سنن ابن ماجه", "ابن ماجه"),
    "AbuDaud": ("سنن أبي داود", "أبو داود"),
    "Nesai": ("سنن النسائي", "النسائي"),
}
SAME_WORDING_NAMED = 0.85  # share of the author's words found in order in a narration of a collector he names
SAME_WORDING_OTHER = 0.99  # ... in a narration of one of the other books: only the very same wording

STARTERS = {"حدثنا", "حدثني", "أخبرنا", "أخبرني", "أنبأنا", "أنبأني", "سمع", "سمعت", "عن", "حدثه", "حدثهم", "قرأت"}
NEW_CHAIN = {"وحدثنا", "وحدثني", "وأخبرنا", "وأخبرني"}
SAYS = {"قال", "قالا", "قالوا", "يقول", "قالت", "ـ", "أن", "أنه"}
BOTH = ("كلاهما", "كلهم")  # `X ... ، Y ... كلاهما عن Z`: Z follows the end of each chain before it
TRAILING_VERBS = {"يحدث", "يحدثه", "يحدثنا", "يقول", "قال", "يرفعه", "يبلغ", "رفعه"}
RELATIVES = {"ابيه", "ابي", "ابى", "جده", "عمه", "اخيه", "امه", "ابنه", "جدي", "عمي", "ابوه"}
GLOSS_WORDS = {"هذا", "حديثه", "لفظ", "المعني", "واحد", "بمعني", "نحو", "معناه"}
WAW_NAMES = {letters(n) for n in ("وقاص", "وكيع", "وهب", "وهيب", "وائل", "واصل", "واقد", "وراد", "ورقاء", "وردان", "واثلة", "وبرة")}
WEAK_TOKENS = {"ابو", "بن", "ام"}


class UncleanChain(ValueError):
    """The isnad is not one line of named narrators that ends with the Companion: the hadith keeps the Companion alone."""


def clean_link(raw: str) -> str:
    """One narrator's name from the words between two transmission terms."""
    name = re.sub(r"\s+(?:يعني|وهو|هو)\s+اب[نى]\s+", " بن ", raw)  # the transmitter's gloss `مروان يعني ابن محمد`
    words = name.split()
    for i, word in enumerate(words):
        if i and word.startswith("و") and len(word) > 2 and letters(word) not in WAW_NAMES:
            words = words[:i]  # `X وY`: the first named is the path followed
            break
    for i, word in enumerate(words):
        if letters(word) in GLOSS_WORDS:
            words = words[:i]  # `قيس بن الحجاج المعنى واحد`
            break
    while words and letters(words[-1]) in TRAILING_VERBS:
        words.pop()
    return " ".join(words)


def parse_links(words: list[str]) -> list[tuple[str, str]]:
    """[(transmission term, name)] of one chain, read from the front until the text stops being a chain."""
    chain, term, name = [], None, []
    previous = None

    def close() -> None:
        nonlocal name
        if name:
            chain.append((term, clean_link(" ".join(name))))
        name = []

    for word in words:
        if word == "على" and previous == "قرأت":  # `قرأت على مالك`: the name follows `على`
            previous = word
            continue
        previous = word
        if word in STARTERS:
            close()
            term = word
        elif word in SAYS:
            close()
            term = None
        elif term is None:
            if chain or name:
                break  # the matn starts here
            raise UncleanChain(f"no transmission term before {word!r}")
        else:
            name.append(word)
    close()
    links = []
    for term, name in chain:
        if re.match(r"^(?:رسول|النبي|نبي)\b", name):
            break  # `حدثنا رسول الله`: the Companion speaks of the Prophet, the matn begins
        links.append((term, name))
    return links


def split_chains(isnad: str) -> list[list[str]]:
    """The words of each chain the isnad holds: a taḥwīl (`ح`) or a `وحدثنا` starts the next one."""
    text = plain(isnad)
    text = re.sub(r"\s*ـ?\s*رضى\s+الله\s+عن(?:هما|ها|هم|ه)\s*ـ?", " ", text)  # the corpus' `ـ رضى الله عنه ـ`
    text = re.split(r"\s+عن\s+النبي\b", text, maxsplit=1)[0].strip(" ،,")
    words = text.replace("،", " ").replace(",", " ").split()
    if words and words[0] in NEW_CHAIN:
        words[0] = words[0][1:]
    chains: list[list[str]] = []
    current: list[str] = []
    for word in words:
        if word == "ح":
            chains.append(current)
            current = []
        elif word in NEW_CHAIN and current:
            chains.append(current)
            current = [word[1:]]
        else:
            current.append(word)
    chains.append(current)
    chains = [c for c in chains if c]
    chains = [[c[0][1:]] + c[1:] if c[0] in NEW_CHAIN else c for c in chains]
    last = chains[-1] if chains else []
    for both in BOTH:
        if both in last:
            at = last.index(both)
            tail = last[at + 1:]
            return [c + tail for c in chains[:-1]] + [last[:at] + tail]
    return chains


def merge_chains(first: list[tuple[str, str]], second: list[tuple[str, str]]) -> list[tuple[str, str]] | None:
    """`first` up to the last narrator `second` also names, then `second` from that narrator on."""
    shared = {name_key(name): i for i, (_, name) in enumerate(second)}
    for j in range(len(first) - 1, -1, -1):
        at = shared.get(name_key(first[j][1]))
        if at is not None:
            return first[: j + 1] + second[at + 1:]
    return None


def name_words(name: str) -> list[str]:
    """The words of a name in the form names are compared in: `ابن` is `بن`, a kunya is in the nominative."""
    words = []
    for word in name_key(name).split():
        words.append("بن" if word == "ابن" else "ابو" if word in ("ابي", "ابا") else word)
    return words


def bare_article(words: list[str]) -> list[str]:
    """The name's first word without its article: the books write `النواس` and `نواس` of one man."""
    if words and words[0].startswith("ال") and len(words[0]) > 4:
        return [words[0][2:]] + words[1:]
    return words


def names_one_person(first: str, second: str) -> bool:
    """One name is a contiguous run of the other. A run that follows `بن` in the longer name is a father's name, not
    the person (`عمر` is not `ابن عمر`)."""
    a, b = [bare_article(name_words(name)) for name in (first, second)]
    if len(a) > len(b):
        a, b = b, a
    if not a or all(word in WEAK_TOKENS for word in a):
        return False
    for i in range(len(b) - len(a) + 1):
        if b[i:i + len(a)] == a:
            if i and b[i - 1] == "بن" and a[0] != "بن":
                continue
            return True
    return False


def end_at_companion(chain: list[tuple[str, str]], companion: str) -> list[tuple[str, str]] | None:
    """The chain cut after the Companion's name, else None. The last link may carry up to three words of the matn."""
    if len(chain) < 2:
        return None
    term, last = chain[-1]
    words = last.split()
    for end in range(len(words), 0, -1):
        if len(words) - end > 3:
            break
        if names_one_person(" ".join(words[:end]), companion):
            return chain[:-1] + [(term, " ".join(words[:end]))]
    return None


def chain_for(isnad: str, companion: str) -> list[tuple[str, str]]:
    """[(transmission term, name)] of the isnad, collector's teacher first and the Companion last, or UncleanChain."""
    parts = [links for links in (parse_links(words) for words in split_chains(isnad)) if links]
    if not parts:
        raise UncleanChain("no chain")
    merged = parts[-1]
    for earlier in reversed(parts[:-1]):
        joined = merge_chains(earlier, merged)
        merged = joined if joined is not None else earlier if end_at_companion(earlier, companion) else None
        if merged is None:
            raise UncleanChain("parallel chains that never meet")
    chain = end_at_companion(merged, companion)
    if chain is None:
        raise UncleanChain(f"the chain ends with {merged[-1][1]!r}, not with {companion!r}")
    for _, name in chain:
        words = letters(name).split()
        if not words:
            raise UncleanChain("an empty name")
        if letters(name) in RELATIVES:
            raise UncleanChain(f"a relative, not a name: {name}")
        if words[-1] == "بن":
            raise UncleanChain(f"a name that stops at `بن`: {name}")
    keys = [name_key(name) for _, name in chain]
    if len(set(keys)) != len(keys):
        raise UncleanChain("a narrator twice")
    return chain


HONORIFICS = re.compile(r"صلي الله عليه و ?سلم|رضي الله عن(?:ه|ها|هما|هم)|عليه السلام")


def wording(text: str) -> list[str]:
    """The words of a matn that tell one wording from another: letters only, folded, without the blessings a book may
    or may not write after a name (and `و سلم` joined, as the corpus writes it)."""
    return HONORIFICS.sub(" ", letters(text)).split()


def coverage(matn: str, source: str) -> float:
    """The share of the matn's words found, in order, in the source's matn."""
    a, b = wording(matn), wording(source)
    blocks = difflib.SequenceMatcher(None, a, b, autojunk=False).get_matching_blocks()
    return sum(block.size for block in blocks) / len(a)


def names_collector(folder: str, takhrij: str) -> bool:
    """The author's takhrij names the collector of this book (`متفق عليه` names al-Bukhari and Muslim)."""
    if takhrij == "متفق عليه":
        return folder in ("Bukhari", "Muslim")
    return letters(BOOKS[folder][1]) in letters(takhrij)


def narration_chain(record: dict, hadith: dict) -> list[tuple[str, str]]:
    """The chain of [hadith] from the corpus narration [record]; the Companion is named as the author names him."""
    folder = record["file"].split("/")[0]
    label = f"{hadith['sourceBook']} {hadith['hadithNumber']} in {record['file']} no. {record['hadithNumber']}"
    needed = SAME_WORDING_NAMED if names_collector(folder, hadith["takhrij"]) else SAME_WORDING_OTHER
    score = coverage(hadith["matnWithTashkeel"], record["matn"])
    if score < needed:
        raise ValueError(f"{label}: it is not the wording the author quotes ({score:.2f} of the words, {needed:.2f} needed)")
    try:
        chain = chain_for(record["isnad"], hadith["sahabi"])
    except UncleanChain as problem:
        raise ValueError(f"{label}: {problem}") from problem
    return [(term, display_name(name)) for term, name in chain[:-1]] + [(chain[-1][0], hadith["sahabi"])]


def words_in_order(names: list[str], source: str) -> bool:
    """Every word of every name occurs in [source], the names in the order of the chain."""
    def fold(text: str) -> list[str]:
        return ["بن" if w == "ابن" else "ابو" if w in ("ابي", "ابا") else w for w in split_abd(letters(text)).split()]
    words, at = fold(source), 0
    for name in names:
        for word in fold(name):
            try:
                at = words.index(word, at) + 1
            except ValueError:
                return False
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
        "sanadSource": "",  # the book whose narration the chain is read from; blank while the chain is the Companion alone
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
        hadith = arbain_hadith(number, hadith_id, snapshot["arbain"][str(number)])
        record = snapshot["narrations"].get(str(number))
        if record is not None:
            hadith["_chain"] = narration_chain(record, hadith)
            # The compiler at the head of a chain is the one whose book it is read from, not the author of the Forty.
            hadith["sanadSource"], hadith["collector"] = BOOKS[record["file"].split("/")[0]]
            hadith["_source"]["chain"] = {"dataset": LK, "file": record["file"], "hadithNumber": record["hadithNumber"]}
        hadiths.append(hadith)
    groups = [dict(g) for g in selection["groups"]]
    place(hadiths, groups, selection["bookGroups"])

    # A narrator keeps the row it had, so that the chains and narrator-level history a database already holds stay valid.
    # A tier is structural: a Companion is the end of some chain, a collector's teacher only ever opens one.
    kept_narrators = selection["narratorKeepIds"]
    next_narrator = max(kept_narrators.values()) + 1
    # A name that is the same in two chains is one narrator, unless `separateRows` says the books mean two men by it
    # (the kunya «أبو حازم» is al-Ashja'i in one hadith and Salama ibn Dinar in another): then each hadith has its own row.
    separate = {name_key(name) for name in selection["separateRows"]}

    def row_key(hadith: dict, name: str) -> str:
        key = name_key(name)
        return f"{key}#{hadith['hadithNumber']}" if key in separate else key

    by_key: dict = {}
    places: dict = {}
    taken: set = set()
    for hadith in hadiths:
        chain = hadith["_chain"]
        for index, (_, name) in enumerate(chain):
            key = row_key(hadith, name)
            if key not in by_key:
                number = kept_narrators.get(name)
                if number is None or number in taken:
                    number, next_narrator = next_narrator, next_narrator + 1
                taken.add(number)
                by_key[key] = {"id": number, "primaryName": name, "fullName": name, "tier": TIER_NARRATOR, "isPivot": False}
            places.setdefault(key, set()).add("last" if index == len(chain) - 1 else "first" if index == 0 else "middle")
    for key, row in by_key.items():
        row["tier"] = TIER_COMPANION if "last" in places[key] else TIER_COLLECTOR_TEACHER if places[key] == {"first"} else TIER_NARRATOR

    out = []
    for hadith in hadiths:
        hadith["sahabi"] = by_key[row_key(hadith, hadith["_chain"][-1][1])]["primaryName"]
        sanad = [{"narratorId": by_key[row_key(hadith, name)]["id"], "transmissionTerm": term} for term, name in hadith.pop("_chain")]
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
                     "A chain is the isnad of one narration of the hadith in one of the six books, named in `sanadSource` "
                     "and located in `source.chain`, where the corpus holds one that can be followed to the Companion the "
                     "author names; otherwise it is the Companion alone. A narrator's tier is structural and no narrator is "
                     "flagged as a pivot: no audited dataset provides either.",
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


def corpus_number(cell: str) -> int | None:
    match = re.fullmatch(r"\s*(\d+)(?:\.0)?\s*", cell)
    return int(match.group(1)) if match else None


def fetch(selection: dict) -> dict:
    nawawi = json.loads(download(FAWAZ, "editions/ara-nawawi.json"))["hadiths"]
    csv.field_size_limit(2**30)
    files: dict = {}
    narrations = {}
    for number, entry in sorted(selection["narrations"].items(), key=lambda kv: int(kv[0])):
        if entry["file"] not in files:
            data = download(LK, entry["file"]).decode("utf-8-sig")
            files[entry["file"]] = list(csv.DictReader(io.StringIO(data, newline="")))
        row = next((r for r in files[entry["file"]] if corpus_number(r["Hadith_number"]) == entry["number"]), None)
        if row is None:
            raise SystemExit(f"no. {entry['number']} is not in {entry['file']}")
        narrations[number] = {
            "file": entry["file"], "hadithNumber": entry["number"], "isnad": row["Arabic_Isnad"], "matn": row["Arabic_Matn"],
        }
    return {
        "sources": PINNED,
        "arbain": {str(h["hadithnumber"]): {"text": h["text"]} for h in nawawi},
        "narrations": narrations,
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
        names = [narrators[l["narratorId"]]["primaryName"] for l in h["sanad"] if l["narratorId"] in narrators]
        if len({l["narratorId"] for l in h["sanad"]}) != len(h["sanad"]):
            problems.append(f"{label}: a narrator twice in one chain")
        record = snapshot["narrations"].get(str(h["hadithNumber"]))
        if record is None:
            if h["sanadSource"] or len(h["sanad"]) != 1:
                problems.append(f"{label}: a chain of {len(h['sanad'])} with no narration it is read from")
            if not in_order(names, letters(source)):
                problems.append(f"{label}: the chain is not in the order of its source")
            continue
        folder = record["file"].split("/")[0]
        if (h["sanadSource"], h["collector"]) != BOOKS[folder]:
            problems.append(f"{label}: the chain is said to come from {h['sanadSource']!r} of {h['collector']!r}, it is read from {BOOKS[folder]!r}")
        if len(names) < 2:
            problems.append(f"{label}: the chain read from {record['file']} has fewer than two narrators")
        needed = SAME_WORDING_NAMED if names_collector(folder, h["takhrij"]) else SAME_WORDING_OTHER
        score = coverage(h["matnWithTashkeel"], record["matn"])
        if score < needed:
            problems.append(f"{label}: {record['file']} no. {record['hadithNumber']} holds {score:.2f} of the words, {needed:.2f} needed")
        if not words_in_order(names[:-1], record["isnad"]):
            problems.append(f"{label}: the chain is not in the order of the isnad it is read from")
        if names and not names_one_person(chain_for(record["isnad"], h["sahabi"])[-1][1], h["sahabi"]):
            problems.append(f"{label}: the isnad does not end with the Companion {h['sahabi']}")
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
    try:
        seed = build(snapshot, selection)
    except ValueError as problem:
        print(problem)
        return 1
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
