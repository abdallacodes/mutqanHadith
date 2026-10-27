// The rules of the catalog that the admin page enforces before anything is written, with no browser and no Firebase in
// it, so that node can test them. The phone skips what these refuse (an empty chain, a blank matn, an unknown
// narrator), and firestore.rules refuses the shapes, so a document that passes here is one the app will show.

export const COLLECTIONS = Object.freeze({
  hadith: 'catalog_hadith',
  narrator: 'catalog_narrator',
  group: 'catalog_group',
});

/** What an author owns in each document; origin, updatedAt and updatedBy are stamped by the store. */
export const FIELDS = Object.freeze({
  catalog_hadith: [
    'id', 'matnWithTashkeel', 'sourceBook', 'hadithNumber', 'chapter', 'grade', 'collector', 'takhrij',
    'sahabi', 'groupId', 'position', 'sanadSource', 'sanad', 'deleted',
  ],
  catalog_narrator: ['id', 'primaryName', 'fullName', 'tier', 'isPivot', 'sameAs', 'bio', 'deleted'],
  catalog_group: ['id', 'parentId', 'title', 'titleEn', 'position', 'deleted'],
});

export const LABELS = Object.freeze({
  id: 'الرقم المعرِّف',
  matnWithTashkeel: 'المتن',
  sourceBook: 'الكتاب',
  hadithNumber: 'رقم الحديث',
  chapter: 'الباب',
  grade: 'الدرجة',
  collector: 'المصنِّف',
  takhrij: 'التخريج',
  sahabi: 'الصحابي',
  groupId: 'المجموعة',
  position: 'الترتيب',
  sanadSource: 'مصدر السند',
  sanad: 'السند',
  deleted: 'محذوف',
  primaryName: 'الاسم',
  fullName: 'الاسم الكامل',
  tier: 'الطبقة',
  isPivot: 'راوٍ محوري',
  sameAs: 'هو نفسه الراوي',
  bio: 'نبذة عن الراوي',
  parentId: 'المجموعة الأم',
  title: 'العنوان',
  titleEn: 'العنوان بالإنجليزية',
});

export const COLLECTION_LABELS = Object.freeze({
  catalog_hadith: 'حديث',
  catalog_narrator: 'راوٍ',
  catalog_group: 'مجموعة',
});

export const TIERS = Object.freeze(['صحابي', 'شيخ المصنف', 'راوٍ']);

/** The terms the seed chains use, offered as suggestions; any other text is allowed. */
export const TRANSMISSION_TERMS = Object.freeze([
  'حدثنا', 'حدثني', 'أخبرنا', 'أخبرني', 'أنبأنا', 'سمعت', 'سمع', 'عن',
]);

export const MAX_CHAIN = 40;
export const MAX_MATN = 20000;
export const MAX_BIO = 20000;

/** Ids made here start well above the bundled catalog and the retired ranges (1-10 and 1001-1413). */
export const ID_FLOOR = 10000;

export function nextId(docs) {
  let top = ID_FLOOR - 1;
  for (const d of docs) if (Number.isInteger(d.id) && d.id > top) top = d.id;
  return top + 1;
}

/** Only the fields an author owns, with the missing ones given their empty value. */
export function authored(collection, data) {
  const out = {};
  for (const key of FIELDS[collection]) {
    let value = data[key];
    if (key === 'sanad') {
      value = (value ?? []).map((l) => ({ narratorId: l.narratorId, transmissionTerm: l.transmissionTerm ?? '' }));
    } else if (key === 'groupId' || key === 'parentId' || key === 'sameAs') {
      value = value ?? null;
    } else if (key === 'deleted' || key === 'isPivot') {
      value = value === true;
    } else if (value === undefined) {
      value = ['hadithNumber', 'position', 'id'].includes(key) ? 0 : '';
    }
    out[key] = value;
  }
  return out;
}

function stable(value) {
  if (Array.isArray(value)) return `[${value.map(stable).join(',')}]`;
  if (value && typeof value === 'object') {
    return `{${Object.keys(value).sort().map((k) => `${JSON.stringify(k)}:${stable(value[k])}`).join(',')}}`;
  }
  return JSON.stringify(value ?? null);
}

export function sameContent(collection, a, b) {
  return stable(authored(collection, a)) === stable(authored(collection, b));
}

/** The fields whose value differs, in the order of the document; a missing side counts as everything. */
export function changedFields(collection, before, after) {
  const a = before ? authored(collection, before) : null;
  const b = authored(collection, after);
  return FIELDS[collection].filter((key) => key !== 'id' && (a === null || stable(a[key]) !== stable(b[key])));
}

const isBlank = (s) => typeof s !== 'string' || s.trim().length === 0;

function lookup(list) {
  return list instanceof Map ? list : new Map(list.map((d) => [d.id, d]));
}

/** @returns {string[]} what is wrong with the hadith, in Arabic; empty when it can be saved. */
export function validateHadith(h, { narrators, groups }) {
  const errors = [];
  const narratorById = lookup(narrators);
  const groupById = lookup(groups);
  if (isBlank(h.matnWithTashkeel)) errors.push('المتن فارغ.');
  else if (h.matnWithTashkeel.length > MAX_MATN) errors.push(`المتن أطول من ${MAX_MATN} حرفاً.`);
  if (!Number.isInteger(h.hadithNumber)) errors.push('رقم الحديث يجب أن يكون عدداً صحيحاً.');
  if (!Number.isInteger(h.position)) errors.push('الترتيب يجب أن يكون عدداً صحيحاً.');
  if (h.groupId !== null && h.groupId !== undefined) {
    const group = groupById.get(h.groupId);
    if (!group || group.deleted) errors.push('المجموعة المختارة غير موجودة.');
  }
  const chain = h.sanad ?? [];
  if (chain.length === 0) errors.push('السند فارغ: أضف راوياً واحداً على الأقل.');
  if (chain.length > MAX_CHAIN) errors.push(`السند أطول من ${MAX_CHAIN} راوياً.`);
  chain.forEach((link, i) => {
    const narrator = narratorById.get(link.narratorId);
    if (!Number.isInteger(link.narratorId) || !narrator) errors.push(`الراوي رقم ${i + 1} في السند غير معروف.`);
    else if (narrator.deleted) errors.push(`الراوي رقم ${i + 1} في السند («${narrator.primaryName}») محذوف.`);
  });
  return errors;
}

/**
 * @param narrators every narrator of the catalog, to check who this one may be said to be the same person as.
 * A narrator is "the same as" one other, the one that stays; that one is not itself pointed elsewhere, and a narrator
 * others point at cannot be pointed away, so the pointers never form chains or loops.
 */
export function validateNarrator(n, narrators = []) {
  const errors = [];
  if (isBlank(n.primaryName)) errors.push('الاسم فارغ.');
  if (!TIERS.includes(n.tier)) errors.push('اختر طبقة الراوي.');
  if ((n.bio ?? '').length > MAX_BIO) errors.push(`النبذة أطول من ${MAX_BIO} حرفاً.`);
  if (n.sameAs !== null && n.sameAs !== undefined) {
    const target = narrators.find((x) => x.id === n.sameAs);
    if (n.sameAs === n.id) errors.push('لا يمكن أن يكون الراوي هو نفسه.');
    else if (!target || target.deleted) errors.push('الراوي المختار غير موجود.');
    else if (target.sameAs !== null && target.sameAs !== undefined) {
      errors.push(`«${target.primaryName}» نفسه مدموج في راوٍ آخر؛ اختر الراوي الأصلي.`);
    }
    if (mergedInto(narrators, n.id).length > 0) errors.push('هناك رواة مدموجون في هذا الراوي؛ انقلهم إلى الراوي الأصلي أولاً.');
  }
  return errors;
}

/** The live narrators an admin said are the same person as [narratorId]. */
export function mergedInto(narrators, narratorId) {
  return narrators.filter((x) => !x.deleted && x.sameAs === narratorId);
}

/** The ids of a group and of everything below it, so that a group is never made a child of its own descendant. */
export function groupAndDescendants(groups, id) {
  const found = new Set([id]);
  let grew = true;
  while (grew) {
    grew = false;
    for (const g of groups) {
      if (g.parentId !== null && found.has(g.parentId) && !found.has(g.id)) {
        found.add(g.id);
        grew = true;
      }
    }
  }
  return found;
}

export function validateGroup(g, groups) {
  const errors = [];
  if (isBlank(g.title)) errors.push('العنوان فارغ.');
  if (!Number.isInteger(g.position)) errors.push('الترتيب يجب أن يكون عدداً صحيحاً.');
  if (g.parentId !== null && g.parentId !== undefined) {
    const parent = groups.find((x) => x.id === g.parentId);
    if (!parent || parent.deleted) errors.push('المجموعة الأم غير موجودة.');
    else if (groupAndDescendants(groups, g.id).has(g.parentId)) errors.push('لا يمكن جعل المجموعة داخل نفسها أو داخل أحد فروعها.');
  }
  return errors;
}

/** The live hadiths whose chain holds this narrator. */
export function hadithsUsing(hadiths, narratorId) {
  return hadiths.filter((h) => !h.deleted && h.sanad.some((l) => l.narratorId === narratorId));
}

export function hadithsIn(hadiths, groupId) {
  return hadiths.filter((h) => !h.deleted && h.groupId === groupId);
}

/** Groups in reading order: each parent followed by its children, with how deep each one sits. */
export function groupRows(groups) {
  const rows = [];
  const byPosition = (a, b) => a.position - b.position || a.id - b.id;
  const walk = (parentId, depth) => {
    for (const g of groups.filter((x) => (x.parentId ?? null) === parentId).sort(byPosition)) {
      rows.push({ group: g, depth });
      walk(g.id, depth + 1);
    }
  };
  walk(null, 0);
  // A group whose parent is missing would never be reached; show it at the top rather than lose it.
  const shown = new Set(rows.map((r) => r.group.id));
  for (const g of groups.filter((x) => !shown.has(x.id)).sort(byPosition)) rows.push({ group: g, depth: 0 });
  return rows;
}

export const narratorLabel = (n) => `${n.id} — ${n.primaryName} (${n.tier})`;

/** The id at the start of a picker's text ("73 — الحميدي (راوٍ)"), or NaN when there is none. */
export function idFromLabel(text) {
  const m = /^\s*(\d+)/.exec(text ?? '');
  return m ? Number(m[1]) : NaN;
}
