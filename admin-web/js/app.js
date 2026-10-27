// The admin page: sign in with Google, then edit hadiths, narrators and groups of the cloud catalog, see what changed
// and put a change back. Everything it writes goes through store.js; what is allowed is decided in core.js and, for
// real, by firestore.rules (only an account with the `admin` claim can write).
import { startFirebase } from './firebase.js';
import { createStore } from './store.js';
import * as core from './core.js';

const C = core.COLLECTIONS;
const $ = (id) => document.getElementById(id);
const view = $('view');

let fb;
let store = null;
let user = null;
let catalog = { hadiths: [], narrators: [], groups: [] };
const listFilter = { hadiths: { q: '', group: '', deleted: false }, narrators: { q: '', tier: '', deleted: false } };

// ---- small helpers ----

/** Builds an element; children are text or nodes, never HTML, so nothing typed or stored is ever interpreted. */
function h(tag, attrs = {}, ...kids) {
  const el = document.createElement(tag);
  for (const [key, value] of Object.entries(attrs)) {
    if (value === false || value == null) continue;
    if (key === 'class') el.className = value;
    else if (key.startsWith('on')) el.addEventListener(key.slice(2), value);
    else el.setAttribute(key, value === true ? '' : value);
  }
  for (const kid of kids.flat()) if (kid != null && kid !== false) el.append(kid);
  return el;
}

let toastTimer;
function toast(message, isError = false) {
  const el = $('toast');
  el.textContent = message;
  el.className = isError ? 'toast err' : 'toast';
  el.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { el.hidden = true; }, isError ? 8000 : 3000);
}

const plain = (s) => (s ?? '').replace(/[ً-ٰٟـ]/g, '').replace(/[أإآ]/g, 'ا').toLowerCase();
const toInt = (text) => (text.trim() === '' ? NaN : Number(text));
const when = (ts) => (ts?.toDate ? ts.toDate().toLocaleString('ar-u-nu-latn') : '');
/** "راوٍ واحد", "راويان", "3 رواة"… the way Arabic counts a few things. */
const narratorCount = (n) => (n === 1 ? 'راوٍ واحد' : n === 2 ? 'راويان' : n >= 3 && n <= 10 ? `${n} رواة` : `${n} راوياً`);
const snippet = (s, n = 70) => (s.length > n ? `${s.slice(0, n)}…` : s);

function field(label, input, hint) {
  return h('label', { class: 'f' }, h('span', {}, label), input, hint ? h('span', { class: 'hint' }, hint) : null);
}

function notice(kind, ...kids) {
  return h('div', { class: `notice ${kind}` }, ...kids);
}

let shown = 0;
function show(...nodes) {
  shown += 1;
  view.replaceChildren(...nodes.flat().filter((n) => n != null && n !== false));
}

/** Adds the history of one document under the editor, unless the page has moved on by the time it loads. */
async function appendHistory(name, id) {
  const mine = shown;
  const section = await historySection(name, id);
  if (mine === shown) view.append(section);
}

const narratorById = (id) => catalog.narrators.find((n) => n.id === id);
const groupById = (id) => catalog.groups.find((g) => g.id === id);

async function reload() {
  try {
    catalog = await store.loadAll();
  } catch (e) {
    toast(`تعذّر تحميل الكتالوج: ${e.message}`, true);
  }
}

// ---- sign in ----

function renderAccount() {
  const box = $('account');
  box.replaceChildren();
  $('nav').hidden = !store;
  if (!user) return;
  box.append(
    h('span', {}, user.email ?? ''),
    store ? h('button', { onclick: async () => { await reload(); render(); toast('تم التحديث'); } }, 'تحديث') : null,
    h('button', { onclick: () => fb.authSdk.signOut(fb.auth) }, 'خروج'),
  );
}

function showSignIn() {
  show(h('div', { class: 'card' },
    h('h2', {}, 'تسجيل الدخول'),
    h('p', {}, 'هذه الصفحة لمن يدير مكتبة الأحاديث. ادخل بحساب Google المخوَّل.'),
    h('button', { class: 'primary', onclick: signIn }, 'الدخول بحساب Google')));
}

async function signIn() {
  try {
    await fb.authSdk.signInWithPopup(fb.auth, new fb.authSdk.GoogleAuthProvider());
  } catch (e) {
    if (e.code !== 'auth/popup-closed-by-user' && e.code !== 'auth/cancelled-popup-request') {
      toast(`تعذّر الدخول: ${e.message}`, true);
    }
  }
}

function showNotAdmin() {
  show(h('div', { class: 'card' },
    h('h2', {}, 'هذا الحساب غير مخوَّل'),
    h('p', {}, `الحساب ${user.email} لا يملك صلاحية الإدارة.`),
    h('p', { class: 'muted' }, 'إن كنت قد منحتَ الصلاحية للتو، فاضغط الزر لتحديثها. وإلا فشغِّل admin-tools/set-admin.js لهذا الحساب.'),
    h('div', { class: 'actions' },
      h('button', { class: 'primary', onclick: async () => { await user.getIdToken(true); await onUser(user); } }, 'تحديث الصلاحية'),
      h('button', { onclick: () => fb.authSdk.signOut(fb.auth) }, 'خروج'))));
}

async function onUser(u) {
  user = u;
  store = null;
  if (!u) {
    renderAccount();
    showSignIn();
    return;
  }
  const token = await u.getIdTokenResult();
  if (token.claims.admin !== true) {
    renderAccount();
    showNotAdmin();
    return;
  }
  store = createStore({ db: fb.db, sdk: fb.fsSdk, uid: u.uid });
  renderAccount();
  show(h('p', { class: 'muted' }, 'جارٍ تحميل الكتالوج…'));
  await reload();
  render();
}

// ---- routing ----

function render() {
  if (!store) return;
  const [tab = 'hadiths', arg] = location.hash.replace(/^#\//, '').split('/');
  const tabOf = { hadith: 'hadiths', narrator: 'narrators', group: 'groups' }[tab] ?? tab;
  document.querySelectorAll('#nav a').forEach((a) => a.classList.toggle('on', a.dataset.tab === tabOf));
  window.scrollTo(0, 0);
  switch (tab) {
    case 'hadiths': return hadithList();
    case 'hadith': return hadithEditor(arg);
    case 'narrators': return narratorList();
    case 'narrator': return narratorEditor(arg);
    case 'groups': return groupList();
    case 'group': return groupEditor(arg);
    case 'history': return historyView();
    default: return hadithList();
  }
}

const notFound = (what) => show(notice('err', `${what} غير موجود.`), h('a', { href: '#/hadiths' }, 'رجوع'));

// ---- history (shared by the editors and the history tab) ----

function describe(entry) {
  if (!entry.before) return 'إنشاء';
  if (!entry.before.deleted && entry.after.deleted) return 'حذف';
  if (entry.before.deleted && !entry.after.deleted) return 'استرجاع';
  const fields = core.changedFields(entry.collection, entry.before, entry.after);
  return `تعديل: ${fields.map((k) => core.LABELS[k]).join('، ')}`;
}

/** A stored value as a reader wants to see it: names for ids, a word for yes and no, and "(فارغ)" for nothing. */
function showValue(key, value) {
  if (key === 'sanad') {
    return value.length === 0 ? '(فارغ)' : value.map((l) => `${narratorById(l.narratorId)?.primaryName ?? `#${l.narratorId}`}${l.transmissionTerm ? ` (${l.transmissionTerm})` : ''}`).join('، ');
  }
  if (key === 'groupId' || key === 'parentId') return value === null ? 'بلا مجموعة' : (groupById(value)?.title ?? `#${value}`);
  if (typeof value === 'boolean') return value ? 'نعم' : 'لا';
  if (value === '' || value == null) return '(فارغ)';
  return snippet(String(value), 160);
}

/** What a change did, field by field: the value before and the value after. */
function changeDetails(entry) {
  if (!entry.before) return null;
  const fields = core.changedFields(entry.collection, entry.before, entry.after);
  if (fields.length === 0) return null;
  return h('details', {}, h('summary', { class: 'muted small' }, 'التفاصيل'),
    h('ul', { class: 'small' }, fields.map((k) => h('li', {}, h('strong', {}, `${core.LABELS[k]}: `),
      h('span', { class: 'muted' }, showValue(k, entry.before[k])), ' ← ', h('span', {}, showValue(k, entry.after[k]))))));
}

const routeOf = { catalog_hadith: 'hadith', catalog_narrator: 'narrator', catalog_group: 'group' };
const listOf = { catalog_hadith: () => catalog.hadiths, catalog_narrator: () => catalog.narrators, catalog_group: () => catalog.groups };

async function revertEntry(entry) {
  const current = listOf[entry.collection]().find((d) => String(d.id) === entry.docId);
  const what = entry.before ? 'إرجاع الوثيقة إلى ما قبل هذا التغيير؟' : 'هذا التغيير أنشأ الوثيقة، فالتراجع عنه يحذفها. متابعة؟';
  if (!window.confirm(what)) return;
  try {
    await store.revert(entry, current);
    await reload();
    toast('تم التراجع');
    render();
  } catch (e) {
    toast(`تعذّر التراجع: ${e.message}`, true);
  }
}

function historyTable(entries, { withDoc }) {
  if (entries.length === 0) return h('p', { class: 'muted' }, 'لا تغييرات مسجَّلة بعد.');
  return h('table', {},
    h('thead', {}, h('tr', {}, h('th', {}, 'الوقت'), h('th', {}, 'بواسطة'), withDoc ? h('th', {}, 'الوثيقة') : null, h('th', {}, 'التغيير'), h('th', {}))),
    h('tbody', {}, entries.map((e) => h('tr', {},
      h('td', { class: 'num' }, when(e.at)),
      h('td', { class: 'num' }, e.by === user.uid ? 'أنت' : e.by.slice(0, 6)),
      withDoc ? h('td', {}, h('a', { href: `#/${routeOf[e.collection]}/${e.docId}` }, `${core.COLLECTION_LABELS[e.collection]} ${e.docId}`)) : null,
      h('td', {}, describe(e), changeDetails(e)),
      h('td', {}, h('button', { class: 'link', onclick: () => revertEntry(e) }, 'تراجع'))))));
}

async function historyView() {
  show(h('h2', {}, 'آخر التغييرات'), h('p', { class: 'muted' }, 'جارٍ التحميل…'));
  try {
    show(h('h2', {}, 'آخر التغييرات'), historyTable(await store.recentHistory(50), { withDoc: true }));
  } catch (e) {
    show(h('h2', {}, 'آخر التغييرات'), notice('err', `تعذّر التحميل: ${e.message}`));
  }
}

async function historySection(name, id) {
  const holder = h('div', {}, h('p', { class: 'muted' }, 'جارٍ تحميل السجل…'));
  store.historyOf(name, id)
    .then((entries) => holder.replaceChildren(historyTable(entries, { withDoc: false })))
    .catch((e) => holder.replaceChildren(notice('err', `تعذّر تحميل السجل: ${e.message}`)));
  return h('div', {}, h('h3', {}, 'سجل هذه الوثيقة'), holder);
}

/** Runs a write, then reloads and leaves for `next`; shows the reason when the write fails. */
async function commit(buttons, work, next, message = 'تم الحفظ') {
  buttons.forEach((b) => { b.disabled = true; });
  try {
    const wrote = await work();
    await reload();
    toast(wrote === false ? 'لا تغيير يُحفظ.' : message);
    if (next) location.hash = next;
    else render();
  } catch (e) {
    toast(`تعذّر الحفظ: ${e.message}`, true);
    buttons.forEach((b) => { b.disabled = false; });
  }
}

function errorBox(errors) {
  return notice('err', h('strong', {}, 'لا يمكن الحفظ:'), h('ul', {}, errors.map((e) => h('li', {}, e))));
}

// ---- hadiths ----

function hadithList() {
  const f = listFilter.hadiths;
  const order = new Map(core.groupRows(catalog.groups).map((r, i) => [r.group.id, i]));
  const body = h('tbody');
  const count = h('span', { class: 'muted small' });
  const draw = () => {
    const q = plain(f.q.trim());
    const rows = catalog.hadiths
      .filter((x) => (f.deleted || !x.deleted)
        && (f.group === '' || String(x.groupId ?? '') === f.group)
        && (q === '' || plain(x.matnWithTashkeel).includes(q) || String(x.hadithNumber) === q || String(x.id) === q
          || plain(x.sourceBook).includes(q)))
      .sort((a, b) => (order.get(a.groupId) ?? 1e9) - (order.get(b.groupId) ?? 1e9) || a.position - b.position || a.id - b.id);
    count.textContent = `${rows.length} من ${catalog.hadiths.length}`;
    body.replaceChildren(...rows.map((x) => h('tr', { class: `clickable${x.deleted ? ' gone' : ''}`, onclick: () => { location.hash = `#/hadith/${x.id}`; } },
      h('td', { class: 'num' }, String(x.hadithNumber)),
      h('td', {}, snippet(x.matnWithTashkeel)),
      h('td', { class: 'num' }, narratorCount(x.sanad.length)),
      h('td', { class: 'num' }, x.origin === 'admin' ? h('span', { class: 'tag' }, 'عُدِّل هنا') : null))));
  };
  const search = h('input', { type: 'text', class: 'grow', placeholder: 'ابحث في المتن أو الرقم', value: f.q, oninput: (e) => { f.q = e.target.value; draw(); } });
  const groups = h('select', { onchange: (e) => { f.group = e.target.value; draw(); } },
    h('option', { value: '' }, 'كل المجموعات'),
    core.groupRows(catalog.groups.filter((g) => !g.deleted)).map(({ group, depth }) =>
      h('option', { value: String(group.id), selected: f.group === String(group.id) }, `${'— '.repeat(depth)}${group.title}`)));
  const deleted = h('input', { type: 'checkbox', checked: f.deleted, onchange: (e) => { f.deleted = e.target.checked; draw(); } });
  show(
    h('h2', {}, 'الأحاديث'),
    h('div', { class: 'bar' }, search, groups, h('label', { class: 'check' }, deleted, 'المحذوفة'), count,
      h('a', { href: '#/hadith/new' }, h('button', { class: 'primary' }, 'حديث جديد'))),
    h('table', {}, h('thead', {}, h('tr', {}, h('th', {}, 'الرقم'), h('th', {}, 'المتن'), h('th', {}, 'السند'), h('th', {}))), body),
  );
  draw();
}

/** The ordered list of narrators of a chain, from the collector's side to the Companion. */
function chainEditor(initial) {
  const list = h('div');
  const options = h('datalist', { id: 'narrator-options' });
  const terms = h('datalist', { id: 'term-options' }, core.TRANSMISSION_TERMS.map((t) => h('option', { value: t })));
  const fillOptions = () => options.replaceChildren(...catalog.narrators.filter((n) => !n.deleted)
    .map((n) => h('option', { value: core.narratorLabel(n) })));

  const renumber = () => [...list.children].forEach((row, i) => { row.querySelector('.idx').textContent = String(i + 1); });
  const move = (row, by) => {
    const sibling = by < 0 ? row.previousElementSibling : row.nextElementSibling;
    if (!sibling) return;
    if (by < 0) list.insertBefore(row, sibling); else list.insertBefore(sibling, row);
    renumber();
  };
  const addRow = (link = { narratorId: null, transmissionTerm: '' }) => {
    const n = link.narratorId == null ? null : narratorById(link.narratorId);
    const row = h('div', { class: 'chain-row' },
      h('span', { class: 'idx' }),
      h('input', { type: 'text', class: 'who', list: 'narrator-options', placeholder: 'ابحث عن راوٍ بالاسم أو الرقم', value: n ? core.narratorLabel(n) : (link.narratorId ?? '') }),
      h('input', { type: 'text', class: 'term', list: 'term-options', placeholder: 'صيغة التحمل', value: link.transmissionTerm }),
      h('span', { class: 'tools' },
        h('button', { type: 'button', title: 'أعلى', onclick: () => move(row, -1) }, '↑'),
        h('button', { type: 'button', title: 'أسفل', onclick: () => move(row, 1) }, '↓'),
        h('button', { type: 'button', class: 'danger', title: 'حذف', onclick: () => { row.remove(); renumber(); } }, '✕')));
    list.append(row);
    renumber();
  };
  (initial.length ? initial : [undefined]).forEach((l) => addRow(l));
  fillOptions();

  const el = h('div', {},
    options, terms, list,
    h('div', { class: 'bar' },
      h('button', { type: 'button', onclick: () => addRow() }, 'إضافة راوٍ'),
      h('a', { href: '#/narrator/new', target: '_blank', rel: 'noopener' }, 'راوٍ جديد (تبويب جديد)'),
      h('button', { type: 'button', class: 'link', onclick: async () => { await reload(); fillOptions(); toast('حُدِّثت قائمة الرواة'); } }, 'تحديث قائمة الرواة')));
  return {
    el,
    // A row with nothing in it is the empty starting row, not a link.
    read: () => [...list.children]
      .filter((row) => row.querySelector('.who').value.trim() !== '' || row.querySelector('.term').value.trim() !== '')
      .map((row) => ({
        narratorId: core.idFromLabel(row.querySelector('.who').value),
        transmissionTerm: row.querySelector('.term').value.trim(),
      })),
  };
}

function groupSelect(selected, excluded = new Set()) {
  return h('select', {},
    h('option', { value: '' }, 'بلا مجموعة'),
    core.groupRows(catalog.groups.filter((g) => !g.deleted && !excluded.has(g.id))).map(({ group, depth }) =>
      h('option', { value: String(group.id), selected: selected === group.id }, `${'— '.repeat(depth)}${group.title}`)));
}

function hadithEditor(arg) {
  const isNew = arg === 'new';
  const existing = isNew ? null : catalog.hadiths.find((x) => x.id === Number(arg));
  if (!isNew && !existing) return notFound('الحديث');
  const base = existing ?? {
    id: core.nextId(catalog.hadiths), matnWithTashkeel: '', sourceBook: '', hadithNumber: 1, chapter: '', grade: '', collector: '',
    takhrij: '', sahabi: '', groupId: null, position: 1, sanadSource: '', sanad: [], deleted: false,
  };
  const L = core.LABELS;
  const matn = h('textarea', { dir: 'rtl' }, base.matnWithTashkeel);
  const text = (key) => h('input', { type: 'text', value: base[key] });
  const number = (key) => h('input', { type: 'number', step: '1', value: String(base[key]) });
  const inputs = {
    sourceBook: text('sourceBook'), hadithNumber: number('hadithNumber'), chapter: text('chapter'), grade: text('grade'),
    collector: text('collector'), takhrij: text('takhrij'), sahabi: text('sahabi'), position: number('position'),
    sanadSource: text('sanadSource'), groupId: groupSelect(base.groupId),
  };
  const chain = chainEditor(base.sanad);
  const problems = h('div');

  const read = () => ({
    id: base.id,
    matnWithTashkeel: matn.value.trim(),
    sourceBook: inputs.sourceBook.value.trim(),
    hadithNumber: toInt(inputs.hadithNumber.value),
    chapter: inputs.chapter.value.trim(),
    grade: inputs.grade.value.trim(),
    collector: inputs.collector.value.trim(),
    takhrij: inputs.takhrij.value.trim(),
    sahabi: inputs.sahabi.value.trim(),
    groupId: inputs.groupId.value === '' ? null : Number(inputs.groupId.value),
    position: toInt(inputs.position.value),
    sanadSource: inputs.sanadSource.value.trim(),
    sanad: chain.read(),
    deleted: base.deleted === true,
  });

  const save = h('button', { class: 'primary' }, isNew ? 'إضافة' : 'حفظ');
  save.addEventListener('click', () => {
    const data = read();
    const errors = core.validateHadith(data, catalog);
    problems.replaceChildren(...(errors.length ? [errorBox(errors)] : []));
    if (errors.length) { problems.scrollIntoView({ block: 'center' }); return; }
    commit([save], () => store.save(C.hadith, data, { isNew }), '#/hadiths');
  });
  const buttons = [save, h('a', { href: '#/hadiths' }, h('button', {}, 'رجوع'))];
  if (existing) {
    const gone = existing.deleted;
    const toggle = h('button', { class: gone ? '' : 'danger' }, gone ? 'استرجاع الحديث' : 'حذف الحديث');
    toggle.addEventListener('click', () => {
      if (!gone && !window.confirm('يُحذف الحديث من المكتبة عند الجميع، وتُحذف معه حصيلة المتعلمين عنه في الأجهزة. يمكنك استرجاعه لاحقاً. متابعة؟')) return;
      commit([toggle], () => store.setDeleted(C.hadith, existing, !gone), '#/hadiths', gone ? 'تم الاسترجاع' : 'تم الحذف');
    });
    buttons.push(toggle);
  }

  show(
    h('h2', {}, isNew ? 'حديث جديد' : `الحديث ${base.id}`),
    existing?.deleted ? notice('warn', 'هذا الحديث محذوف. استرجعه ليظهر في التطبيق.') : null,
    h('div', { class: 'card' },
      field(L.matnWithTashkeel, matn, 'يُلصق النص كما هو بتشكيله؛ لا تُعدَّل الأحرف تلقائياً.'),
      h('div', { class: 'grid' },
        field(L.sourceBook, inputs.sourceBook), field(L.hadithNumber, inputs.hadithNumber), field(L.groupId, inputs.groupId),
        field(L.position, inputs.position, 'ترتيب الحديث داخل مجموعته.'), field(L.collector, inputs.collector),
        field(L.sanadSource, inputs.sanadSource, 'الكتاب الذي أُخذ منه السند.'), field(L.sahabi, inputs.sahabi),
        field(L.takhrij, inputs.takhrij), field(L.chapter, inputs.chapter), field(L.grade, inputs.grade))),
    h('div', { class: 'card' }, h('h3', { style: 'margin-top:0' }, 'السند'),
      h('p', { class: 'muted small' }, 'رتِّب الرواة من جهة المصنف إلى الصحابي. الراوي الجديد يُنشأ أولاً في تبويب الرواة.'),
      chain.el),
    problems,
    h('div', { class: 'actions' }, buttons),
  );
  if (existing) appendHistory(C.hadith, base.id);
}

// ---- narrators ----

function narratorList() {
  const f = listFilter.narrators;
  const body = h('tbody');
  const count = h('span', { class: 'muted small' });
  const draw = () => {
    const q = plain(f.q.trim());
    const rows = catalog.narrators
      .filter((n) => (f.deleted || !n.deleted) && (f.tier === '' || n.tier === f.tier)
        && (q === '' || plain(n.primaryName).includes(q) || plain(n.fullName).includes(q) || String(n.id) === q))
      .sort((a, b) => a.primaryName.localeCompare(b.primaryName, 'ar') || a.id - b.id);
    count.textContent = `${rows.length} من ${catalog.narrators.length}`;
    body.replaceChildren(...rows.map((n) => h('tr', { class: `clickable${n.deleted ? ' gone' : ''}`, onclick: () => { location.hash = `#/narrator/${n.id}`; } },
      h('td', { class: 'num' }, String(n.id)), h('td', {}, n.primaryName), h('td', {}, n.tier),
      h('td', { class: 'num' }, String(core.hadithsUsing(catalog.hadiths, n.id).length)))));
  };
  const search = h('input', { type: 'text', class: 'grow', placeholder: 'ابحث بالاسم أو الرقم', value: f.q, oninput: (e) => { f.q = e.target.value; draw(); } });
  const tier = h('select', { onchange: (e) => { f.tier = e.target.value; draw(); } },
    h('option', { value: '' }, 'كل الطبقات'), core.TIERS.map((t) => h('option', { value: t, selected: f.tier === t }, t)));
  const deleted = h('input', { type: 'checkbox', checked: f.deleted, onchange: (e) => { f.deleted = e.target.checked; draw(); } });
  show(
    h('h2', {}, 'الرواة'),
    h('div', { class: 'bar' }, search, tier, h('label', { class: 'check' }, deleted, 'المحذوفون'), count,
      h('a', { href: '#/narrator/new' }, h('button', { class: 'primary' }, 'راوٍ جديد'))),
    h('table', {}, h('thead', {}, h('tr', {}, h('th', {}, 'الرقم'), h('th', {}, 'الاسم'), h('th', {}, 'الطبقة'), h('th', {}, 'أحاديثه'))), body),
  );
  draw();
}

function narratorEditor(arg) {
  const isNew = arg === 'new';
  const existing = isNew ? null : catalog.narrators.find((n) => n.id === Number(arg));
  if (!isNew && !existing) return notFound('الراوي');
  const base = existing ?? { id: core.nextId(catalog.narrators), primaryName: '', fullName: '', tier: 'راوٍ', isPivot: false, deleted: false };
  const L = core.LABELS;
  const name = h('input', { type: 'text', value: base.primaryName });
  const full = h('input', { type: 'text', value: base.fullName });
  const tier = h('select', {}, core.TIERS.map((t) => h('option', { value: t, selected: base.tier === t }, t)));
  const pivot = h('input', { type: 'checkbox', checked: base.isPivot });
  const problems = h('div');
  const using = existing ? core.hadithsUsing(catalog.hadiths, base.id) : [];

  const save = h('button', { class: 'primary' }, isNew ? 'إضافة' : 'حفظ');
  save.addEventListener('click', () => {
    const data = {
      id: base.id, primaryName: name.value.trim(), fullName: full.value.trim() || name.value.trim(),
      tier: tier.value, isPivot: pivot.checked, deleted: base.deleted === true,
    };
    const errors = core.validateNarrator(data);
    problems.replaceChildren(...(errors.length ? [errorBox(errors)] : []));
    if (errors.length) return;
    commit([save], () => store.save(C.narrator, data, { isNew }), '#/narrators');
  });
  const buttons = [save, h('a', { href: '#/narrators' }, h('button', {}, 'رجوع'))];
  if (existing) {
    const gone = existing.deleted;
    const toggle = h('button', { class: gone ? '' : 'danger', disabled: !gone && using.length > 0 }, gone ? 'استرجاع الراوي' : 'حذف الراوي');
    toggle.addEventListener('click', () => commit([toggle], () => store.setDeleted(C.narrator, existing, !gone), '#/narrators', gone ? 'تم الاسترجاع' : 'تم الحذف'));
    buttons.push(toggle);
  }

  show(
    h('h2', {}, isNew ? 'راوٍ جديد' : `الراوي ${base.id}`),
    existing?.deleted ? notice('warn', 'هذا الراوي محذوف.') : null,
    h('div', { class: 'card' },
      h('div', { class: 'grid' },
        field(L.primaryName, name, 'الاسم الذي يظهر في السند.'), field(L.fullName, full, 'اتركه فارغاً ليساوي الاسم.'),
        field(L.tier, tier), h('label', { class: 'check' }, pivot, L.isPivot))),
    isNew ? notice('warn', 'الراوي الجديد يُعدّ في التطبيق شخصاً مستقلاً. إن كان هو نفسه راوياً موجوداً بكنية أخرى، فاستخدم الراوي الموجود في السند بدل إنشاء آخر.') : null,
    problems,
    h('div', { class: 'actions' }, buttons),
    existing ? h('div', {}, h('h3', {}, `يرد في ${using.length} حديثاً`),
      using.length ? h('ul', {}, using.map((x) => h('li', {}, h('a', { href: `#/hadith/${x.id}` }, `${x.hadithNumber}: ${snippet(x.matnWithTashkeel, 50)}`)))) : null,
      using.length ? h('p', { class: 'muted small' }, 'لا يُحذف راوٍ ما دام في سند حديث؛ أزِله من السند أولاً.') : null) : null,
  );
  if (existing) appendHistory(C.narrator, base.id);
}

// ---- groups ----

function groupList() {
  const rows = core.groupRows(catalog.groups);
  show(
    h('h2', {}, 'المجموعات'),
    h('div', { class: 'bar' }, h('a', { href: '#/group/new' }, h('button', { class: 'primary' }, 'مجموعة جديدة'))),
    h('table', {}, h('thead', {}, h('tr', {}, h('th', {}, 'الرقم'), h('th', {}, 'العنوان'), h('th', {}, 'أحاديثها'))),
      h('tbody', {}, rows.map(({ group, depth }) => h('tr', { class: `clickable${group.deleted ? ' gone' : ''}`, onclick: () => { location.hash = `#/group/${group.id}`; } },
        h('td', { class: 'num' }, String(group.id)),
        h('td', { style: `padding-inline-start:${10 + depth * 22}px` }, group.title),
        h('td', { class: 'num' }, String(core.hadithsIn(catalog.hadiths, group.id).length)))))),
  );
}

function groupEditor(arg) {
  const isNew = arg === 'new';
  const existing = isNew ? null : catalog.groups.find((g) => g.id === Number(arg));
  if (!isNew && !existing) return notFound('المجموعة');
  const base = existing ?? { id: core.nextId(catalog.groups), parentId: null, title: '', titleEn: '', position: catalog.groups.length, deleted: false };
  const L = core.LABELS;
  const title = h('input', { type: 'text', value: base.title });
  const titleEn = h('input', { type: 'text', value: base.titleEn, dir: 'ltr' });
  const parent = groupSelect(base.parentId, core.groupAndDescendants(catalog.groups, base.id));
  const position = h('input', { type: 'number', step: '1', value: String(base.position) });
  const problems = h('div');
  const inside = existing ? core.hadithsIn(catalog.hadiths, base.id) : [];
  const children = existing ? catalog.groups.filter((g) => !g.deleted && g.parentId === base.id) : [];

  const save = h('button', { class: 'primary' }, isNew ? 'إضافة' : 'حفظ');
  save.addEventListener('click', () => {
    const data = {
      id: base.id, parentId: parent.value === '' ? null : Number(parent.value), title: title.value.trim(),
      titleEn: titleEn.value.trim(), position: toInt(position.value), deleted: base.deleted === true,
    };
    const errors = core.validateGroup(data, catalog.groups);
    problems.replaceChildren(...(errors.length ? [errorBox(errors)] : []));
    if (errors.length) return;
    commit([save], () => store.save(C.group, data, { isNew }), '#/groups');
  });
  const buttons = [save, h('a', { href: '#/groups' }, h('button', {}, 'رجوع'))];
  if (existing) {
    const gone = existing.deleted;
    const toggle = h('button', { class: gone ? '' : 'danger', disabled: !gone && children.length > 0 }, gone ? 'استرجاع المجموعة' : 'حذف المجموعة');
    toggle.addEventListener('click', () => {
      if (!gone && inside.length && !window.confirm(`ستخرج ${inside.length} أحاديث من هذه المجموعة ولا تبقى في أي مجموعة. متابعة؟`)) return;
      commit([toggle], () => store.setDeleted(C.group, existing, !gone), '#/groups', gone ? 'تم الاسترجاع' : 'تم الحذف');
    });
    buttons.push(toggle);
  }

  show(
    h('h2', {}, isNew ? 'مجموعة جديدة' : `المجموعة ${base.id}`),
    existing?.deleted ? notice('warn', 'هذه المجموعة محذوفة.') : null,
    h('div', { class: 'card' },
      h('div', { class: 'grid' },
        field(L.title, title), field(L.titleEn, titleEn), field(L.parentId, parent), field(L.position, position))),
    problems,
    h('div', { class: 'actions' }, buttons),
    existing && children.length ? h('p', { class: 'muted small' }, 'لا تُحذف مجموعة فيها مجموعات فرعية؛ انقلها أو احذفها أولاً.') : null,
    existing ? h('p', { class: 'muted' }, `فيها ${inside.length} حديثاً.`) : null,
  );
  if (existing) appendHistory(C.group, base.id);
}

// ---- start ----

async function boot() {
  try {
    fb = await startFirebase();
  } catch (e) {
    show(notice('err', e.message));
    return;
  }
  window.addEventListener('hashchange', render);
  fb.authSdk.onAuthStateChanged(fb.auth, (u) => { onUser(u).catch((e) => show(notice('err', `تعذّر البدء: ${e.message}`))); });
}

boot();
