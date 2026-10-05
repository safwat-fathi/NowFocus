// Google Analytics 4 behind Consent Mode v2. Loaded by every page (<head>, defer).
// gtag runs on every visit with all storage denied: no cookies, cookieless pings only. "Accept" grants analytics_storage.
// The choice is kept in localStorage ("nf-consent"); the footer "Cookie settings" button reopens the banner.
(() => {
  const ID = "G-MYM23S2Q8E", KEY = "nf-consent";
  const AR = document.documentElement.lang === "ar";
  const L = (en, ar) => (AR ? ar : en);
  const get = () => { try { return localStorage.getItem(KEY); } catch { return null; } };
  const set = (v) => { try { localStorage.setItem(KEY, v); } catch {} };
  const state = (v) => ({ analytics_storage: v === "granted" ? "granted" : "denied" });
  // Declining after accepting must also drop the cookies already set (_ga, _ga_<id>).
  const clear = () => document.cookie.split("; ").map((c) => c.split("=")[0]).filter((n) => /^_ga(_|$)/.test(n))
    .forEach((n) => ["", ";domain=" + location.hostname.replace(/^www\./, "")].forEach((d) => { document.cookie = n + "=;max-age=0;path=/" + d; }));

  window.dataLayer = window.dataLayer || [];
  function gtag() { dataLayer.push(arguments); }
  window.gtag = gtag; // waitlist.js fires generate_lead through this
  gtag("consent", "default", { ...state(get()), ad_storage: "denied", ad_user_data: "denied", ad_personalization: "denied" });
  gtag("js", new Date());
  gtag("config", ID, { allow_google_signals: false, allow_ad_personalization_signals: false });
  const s = document.createElement("script");
  s.async = true;
  s.src = "https://www.googletagmanager.com/gtag/js?id=" + ID;
  document.head.append(s);

  let banner;
  const show = () => {
    if (banner) return;
    banner = document.createElement("aside");
    banner.className = "consent";
    banner.setAttribute("aria-label", L("Analytics consent", "موافقة التحليلات"));
    banner.innerHTML = '<p>' + L("We use Google Analytics to count visits. If you accept, it sets cookies; if not, it sets none and only sends basic, cookieless pings.", "نستخدم Google Analytics لعدّ الزيارات. إن وافقت فسيضع ملفات تعريف ارتباط، وإن رفضت فلن يضع أيًّا منها وسيرسل إشارات أساسية فقط دون ملفات تعريف ارتباط.") + ' <a href="' + L("", "/ar") + '/privacy/#website">' + L("Details", "التفاصيل") + '</a></p>' +
      '<div class="consent-actions"><button type="button" class="btn btn-secondary" data-v="denied">' + L("Decline", "رفض") + '</button><button type="button" class="btn btn-primary" data-v="granted">' + L("Accept", "موافقة") + '</button></div>';
    banner.addEventListener("click", (e) => {
      const v = e.target.closest("[data-v]")?.dataset.v;
      if (!v) return;
      set(v);
      gtag("consent", "update", state(v));
      if (v !== "granted") clear();
      banner.remove();
      banner = null;
    });
    document.body.append(banner);
  };

  if (!get()) show();
  document.addEventListener("click", (e) => { if (e.target.closest("[data-consent-reset]")) show(); });
})();
