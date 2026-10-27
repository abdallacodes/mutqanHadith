// The bundled catalog (app/src/main/assets/seed_hadiths.json) as the documents the cloud catalog holds.
const fs = require('node:fs');
const path = require('node:path');

const SEED_PATH = path.join(__dirname, '..', '..', 'app', 'src', 'main', 'assets', 'seed_hadiths.json');

/** The fields an author owns; origin, updatedAt, updatedBy and deleted are stamped when a document is written. */
function hadithDoc(h) {
  return {
    id: h.id,
    matnWithTashkeel: h.matnWithTashkeel,
    sourceBook: h.sourceBook,
    hadithNumber: h.hadithNumber,
    chapter: h.chapter,
    grade: h.grade,
    collector: h.collector,
    takhrij: h.takhrij,
    sahabi: h.sahabi,
    groupId: h.groupId ?? null,
    position: h.position ?? 0,
    sanadSource: h.sanadSource ?? '',
    sanad: h.sanad.map((link) => ({ narratorId: link.narratorId, transmissionTerm: link.transmissionTerm })),
  };
}

function narratorDoc(n) {
  return { id: n.id, primaryName: n.primaryName, fullName: n.fullName, tier: n.tier, isPivot: n.isPivot === true };
}

function groupDoc(g) {
  return { id: g.id, parentId: g.parentId ?? null, title: g.title, titleEn: g.titleEn ?? '', position: g.position };
}

function readSeed(file = SEED_PATH) {
  const seed = JSON.parse(fs.readFileSync(file, 'utf8'));
  return {
    catalogVersion: seed.catalogVersion,
    catalog_group: (seed.groups ?? []).map(groupDoc),
    catalog_narrator: seed.narrators.map(narratorDoc),
    catalog_hadith: seed.hadiths.map(hadithDoc),
  };
}

module.exports = { readSeed, hadithDoc, narratorDoc, groupDoc, SEED_PATH };
