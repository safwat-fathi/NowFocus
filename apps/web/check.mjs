// SEO + consistency check for the static site. Run from anywhere: node apps/web/check.mjs
// No dependencies. Exits 1 and lists every problem it finds.
import { readFileSync, readdirSync, existsSync, statSync } from "node:fs";
import { join, dirname, relative } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = dirname(fileURLToPath(import.meta.url));
const BASE = "https://nowfocus.online";
const SITEWIDE = ["/#org", "/#website", "/#app", "/#logo"].map((s) => BASE + s); // defined on the landing page, referenced elsewhere
const errors = [];
const fail = (page, msg) => errors.push(`${page}: ${msg}`);

const walk = (dir) => readdirSync(dir).flatMap((n) => {
  const p = join(dir, n);
  if (n.startsWith("_") || n === "node_modules") return [];
  return statSync(p).isDirectory() ? walk(p) : p.endsWith(".html") ? [p] : [];
});
const urlPath = (file) => {
  const rel = "/" + relative(ROOT, file).replaceAll("\\", "/");
  return rel.endsWith("/index.html") ? rel.slice(0, -"index.html".length) : rel;
};
const decode = (s) => s.replace(/&nbsp;/g, " ").replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&quot;/g, '"').replace(/&#x27;|&#39;/g, "'").replace(/&amp;/g, "&");
const norm = (s) => decode(s).replace(/\s+/g, " ").trim();
const meta = (h, attr, name) => h.match(new RegExp(`<meta[^>]*${attr}="${name}"[^>]*content="([^"]*)"`, "i"))?.[1];

const pages = walk(ROOT).map((file) => {
  const html = readFileSync(file, "utf8");
  const text = norm(html.replace(/<script[\s\S]*?<\/script>|<style[\s\S]*?<\/style>|<[^>]+>/g, " "));
  return { file, path: urlPath(file), html, text, name: urlPath(file) };
});
const byPath = new Map(pages.map((p) => [p.path, p]));

const nodesOf = (p) => [...p.html.matchAll(/<script type="application\/ld\+json">([\s\S]*?)<\/script>/g)].flatMap((m) => {
  try { const j = JSON.parse(m[1]); return j["@graph"] ?? [j]; }
  catch (e) { fail(p.name, `JSON-LD does not parse: ${e.message}`); return []; }
});
const refs = (o, out = []) => {
  if (Array.isArray(o)) o.forEach((x) => refs(x, out));
  else if (o && typeof o === "object") {
    const keys = Object.keys(o);
    if (keys.length === 1 && keys[0] === "@id") out.push(o["@id"]); else keys.forEach((k) => refs(o[k], out));
  }
  return out;
};

const titles = new Map(), descs = new Map(), canon = new Set();
for (const p of pages) {
  const noindex = /<meta name="robots"[^>]*noindex/i.test(p.html);
  const h1s = (p.html.match(/<h1[\s>]/g) ?? []).length;
  if (h1s !== 1) fail(p.name, `${h1s} <h1> elements (want 1)`);
  if (/\[|\{[^}]*\}|<[A-Za-z][^>]*>|TODO/.test(p.text)) fail(p.name, "placeholder-looking text ([ ], { }, < > or TODO) in visible text");
  for (const m of p.html.matchAll(/<img\b[^>]*>/g)) if (!/\balt="/.test(m[0])) fail(p.name, "image without alt");

  // in-page and root-absolute links, assets and anchors
  for (const m of p.html.matchAll(/\s(?:href|src)="([^"]+)"/g)) {
    const u = m[1];
    if (/^(https?:|mailto:|data:)/.test(u) || u.startsWith("//")) continue;
    const [pathPart, hash] = u.split("#");
    let target = p;
    if (pathPart) {
      if (!pathPart.startsWith("/")) { fail(p.name, `relative link ${u} (use root-absolute)`); continue; }
      const file = join(ROOT, pathPart.endsWith("/") ? pathPart + "index.html" : pathPart);
      if (!existsSync(file)) { fail(p.name, `broken link ${u}`); continue; }
      target = byPath.get(pathPart) ?? null;
    }
    if (hash && target && !new RegExp(`\\bid="${hash}"`).test(target.html)) fail(p.name, `missing anchor ${u}`);
  }

  // structured data
  const nodes = nodesOf(p);
  const ids = nodes.map((n) => n["@id"]).filter(Boolean);
  ids.filter((id, i) => ids.indexOf(id) !== i).forEach((id) => fail(p.name, `duplicate @id ${id}`));
  for (const r of refs(nodes)) if (!ids.includes(r) && !SITEWIDE.includes(r)) fail(p.name, `unresolved @id ${r}`);

  const crumbLd = nodes.find((n) => n["@type"] === "BreadcrumbList");
  const crumbHtml = [...(p.html.match(/<nav class="crumbs"[\s\S]*?<\/nav>/)?.[0].matchAll(/<li[^>]*>([\s\S]*?)<\/li>/g) ?? [])].map((m) => norm(m[1].replace(/<[^>]+>/g, "")));
  if (crumbLd && JSON.stringify(crumbLd.itemListElement.map((i) => i.name)) !== JSON.stringify(crumbHtml)) fail(p.name, "breadcrumb JSON-LD differs from visible breadcrumb");
  if (!crumbLd && crumbHtml.length) fail(p.name, "visible breadcrumb without JSON-LD");
  for (const faq of nodes.filter((n) => n["@type"] === "FAQPage"))
    for (const q of faq.mainEntity) {
      if (!p.text.includes(norm(q.name))) fail(p.name, `FAQ question not visible: ${q.name}`);
      if (!p.text.includes(norm(q.acceptedAnswer.text))) fail(p.name, `FAQ answer not visible: ${q.name}`);
    }

  if (noindex) continue;
  // head tags for indexable pages
  const title = p.html.match(/<title>([\s\S]*?)<\/title>/)?.[1], desc = meta(p.html, "name", "description");
  const canonical = p.html.match(/<link rel="canonical" href="([^"]+)"/)?.[1];
  if (!title) fail(p.name, "missing <title>"); else { if (title.length > 65) fail(p.name, `title is ${title.length} chars`); if (titles.has(title)) fail(p.name, `duplicate title (also ${titles.get(title)})`); titles.set(title, p.name); }
  if (!desc) fail(p.name, "missing meta description"); else { if (desc.length < 70 || desc.length > 170) fail(p.name, `description is ${desc.length} chars`); if (descs.has(desc)) fail(p.name, `duplicate description (also ${descs.get(desc)})`); descs.set(desc, p.name); }
  if (canonical !== BASE + p.path) fail(p.name, `canonical ${canonical} != ${BASE + p.path}`);
  if (meta(p.html, "property", "og:url") !== canonical) fail(p.name, "og:url differs from canonical");
  canon.add(BASE + p.path);
}

// sitemap lists exactly the indexable pages
const sm = new Set([...readFileSync(join(ROOT, "sitemap.xml"), "utf8").matchAll(/<loc>([^<]+)<\/loc>/g)].map((m) => m[1]));
for (const u of canon) if (!sm.has(u)) fail("sitemap.xml", `missing ${u}`);
for (const u of sm) if (!canon.has(u)) fail("sitemap.xml", `lists a page that isn't indexable or doesn't exist: ${u}`);
if (!/Sitemap: https:\/\/nowfocus\.online\/sitemap\.xml/.test(readFileSync(join(ROOT, "robots.txt"), "utf8"))) fail("robots.txt", "missing Sitemap line");

// every nowfocus.online URL in llms.txt resolves to a page or file
for (const m of readFileSync(join(ROOT, "llms.txt"), "utf8").matchAll(/\(https:\/\/nowfocus\.online(\/[^)#]*)\)/g))
  if (!existsSync(join(ROOT, m[1].endsWith("/") ? m[1] + "index.html" : m[1]))) fail("llms.txt", `broken link ${m[1]}`);

// nav and footer are identical on every page (apart from aria-current)
const shared = (p, re) => p.html.match(re)?.[0].replace(/ aria-current="page"/g, "").replace(/\s*<a class="nav-link nav-lang"[^>]*>[^<]*<\/a>/, "");
const isAr = (p) => p.path.startsWith("/ar/");
for (const [label, re] of [["nav", /<nav class="nav"[\s\S]*?<\/nav>/], ["footer", /<footer class="foot">[\s\S]*?<\/footer>/]]) {
  for (const group of [pages.filter((p) => !isAr(p)), pages.filter(isAr)]) {
    const ref = shared(group[0], re);
    for (const p of group) if (shared(p, re) !== ref) fail(p.name, `${label} differs from ${group[0].name}`);
  }
}

// Arabic pages: rtl, and every hreflang pair is reciprocal and resolves
for (const p of pages) {
  const alts = [...p.html.matchAll(/<link rel="alternate" hreflang="([^"]+)" href="([^"]+)">/g)].map((m) => [m[1], m[2]]);
  if (isAr(p) && !/<html lang="ar" dir="rtl">/.test(p.html)) fail(p.name, 'Arabic page without <html lang="ar" dir="rtl">');
  if (!alts.length) continue;
  const self = BASE + p.path;
  if (!alts.some(([, h]) => h === self)) fail(p.name, "hreflang set doesn't include the page itself");
  for (const [, h] of alts) {
    const twin = byPath.get(h.slice(BASE.length));
    if (!twin) { fail(p.name, `hreflang points at a missing page ${h}`); continue; }
    const back = [...twin.html.matchAll(/<link rel="alternate" hreflang="[^"]+" href="([^"]+)">/g)].map((m) => m[1]);
    if (!back.includes(self)) fail(p.name, `hreflang to ${h} is not reciprocal`);
  }
}

if (errors.length) { console.error(errors.join("\n")); console.error(`\n${errors.length} problem(s)`); process.exit(1); }
console.log(`ok: ${pages.length} pages, ${canon.size} indexable, sitemap matches`);
