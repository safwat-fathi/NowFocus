// NowFocus Sitewide Waitlist & Feature Request Modal
(() => {
  let dialog = null;
  const AR = document.documentElement.lang === "ar";
  const L = (en, ar) => (AR ? ar : en);

  const createDialog = () => {
    if (dialog) return dialog;
    dialog = document.createElement("dialog");
    dialog.id = "waitlist-dialog";
    dialog.className = "waitlist-dialog";
    dialog.setAttribute("aria-labelledby", "waitlist-title");

    dialog.innerHTML = `
      <div class="waitlist-card">
        <button type="button" class="waitlist-close" aria-label="${L("Close dialog", "إغلاق النافذة")}" data-waitlist-close>
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="square"><line x1="18" y1="6" x2="6" y2="18"></line><line x1="6" y1="6" x2="18" y2="18"></line></svg>
        </button>

        <div id="waitlist-form-container">
          <div class="waitlist-head">
            <span class="waitlist-kicker">${L("Early Access", "وصول مبكر")}</span>
            <h2 id="waitlist-title">${L("Join the Waitlist", "انضم إلى قائمة الانتظار")}</h2>
            <p class="waitlist-desc">${L("Be the first to access new platform builds, and request the features or focus tools you need most.", "كن أول من يجرّب إصدارات المنصات الجديدة، واطلب الميزات وأدوات التركيز التي تحتاجها أكثر.")}</p>
          </div>

          <form id="waitlist-form" class="waitlist-form" novalidate>
            <div class="field">
              <label for="wl-email">${L("Email address", "البريد الإلكتروني")} <span class="req">*</span></label>
              <input type="email" dir="ltr" id="wl-email" name="email" required placeholder="you@domain.com" autocomplete="email">
              <span class="field-error" id="wl-email-err" hidden>${L("Please enter a valid email address.", "أدخل بريدًا إلكترونيًا صحيحًا.")}</span>
            </div>

            <div class="field">
              <label id="wl-platforms-label">${L("Platforms you plan to use", "المنصات التي تنوي استخدامها")}</label>
              <div class="platform-chips" role="group" aria-labelledby="wl-platforms-label">
                <label class="chip"><input type="checkbox" name="platform" value="android" checked> <span>Android</span></label>
                <label class="chip"><input type="checkbox" name="platform" value="windows" checked> <span>Windows</span></label>
                <label class="chip"><input type="checkbox" name="platform" value="macos"> <span>macOS</span></label>
                <label class="chip"><input type="checkbox" name="platform" value="ios"> <span>iOS</span></label>
              </div>
            </div>

            <div class="field">
              <label for="wl-feature">${L("Feature request / biggest distraction", "طلب ميزة / أكبر ما يشتّتك")} <span class="opt">${L("(optional)", "(اختياري)")}</span></label>
              <textarea id="wl-feature" name="featureRequest" rows="3" placeholder="${L("What distraction loop or feature do you wish focus apps solved?", "ما الذي يشتّتك باستمرار؟ أو ما الميزة التي تتمنى أن تقدّمها تطبيقات التركيز؟")}"></textarea>
              <span class="field-hint">${L("Public: a feature request is posted as an issue on our GitHub repo. Your email is never included.", "علني: يُنشر طلب الميزة كمسألة (issue) على مستودعنا في GitHub، ولا يظهر بريدك فيه أبدًا.")} <a href="${L("", "/ar")}/privacy/#waitlist">${L("Privacy", "الخصوصية")}</a></span>
            </div>

            <p class="field-error" id="wl-submit-err" role="alert" hidden>${L("Couldn’t save that. Please try again in a minute, or email safwat.rashwan@gmail.com.", "تعذّر الحفظ. حاول مرة أخرى بعد دقيقة، أو راسلنا على safwat.rashwan@gmail.com.")}</p>
            <div class="waitlist-actions">
              <button type="submit" class="btn btn-primary" id="wl-submit-btn">${L("Join Waitlist", "انضم إلى القائمة")}</button>
            </div>
          </form>
        </div>

        <div id="waitlist-success-container" class="waitlist-success" hidden>
          <div class="success-mark">✓</div>
          <h3>${L("You're on the list!", "أنت على القائمة!")}</h3>
          <p>${L("We'll email you when your platform is ready. If you included a feature request, it's now an open issue on our GitHub repo.", "سنراسلك عندما تصبح منصتك جاهزة. وإن أرسلت طلب ميزة، فهو الآن مسألة مفتوحة على مستودعنا في GitHub.")}</p>
          <div class="waitlist-actions">
            <button type="button" class="btn btn-primary" data-waitlist-close>${L("Back to site", "العودة إلى الموقع")}</button>
          </div>
        </div>
      </div>
    `;

    // Click on the backdrop closes. Keyboard-activated clicks have 0,0 coordinates, so test the target instead.
    dialog.addEventListener("click", (e) => {
      if (e.target === dialog) close();
    });

    // Delegated close clicks
    dialog.addEventListener("click", (e) => {
      if (e.target.closest("[data-waitlist-close]")) {
        close();
      }
    });

    // Form submission
    const form = dialog.querySelector("#waitlist-form");
    form.addEventListener("submit", async (e) => {
      e.preventDefault();
      const emailInput = dialog.querySelector("#wl-email");
      const emailErr = dialog.querySelector("#wl-email-err");
      const email = emailInput.value.trim();

      if (!email || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
        emailErr.hidden = false;
        emailInput.focus();
        return;
      }
      emailErr.hidden = true;

      const checkedPlatforms = Array.from(dialog.querySelectorAll('input[name="platform"]:checked')).map((c) => c.value);
      const featureRequest = dialog.querySelector("#wl-feature").value.trim();
      const submitBtn = dialog.querySelector("#wl-submit-btn");

      submitBtn.disabled = true;
      submitBtn.textContent = L("Saving...", "جارٍ الحفظ...");

      const payload = {
        email,
        platforms: checkedPlatforms,
        featureRequest: featureRequest || undefined,
      };

      const submitErr = dialog.querySelector("#wl-submit-err");
      submitErr.hidden = true;
      let ok = false;
      try {
        const api = ["localhost", "127.0.0.1"].includes(location.hostname)
          ? "http://127.0.0.1:3000"
          : "https://api.nowfocus.online";
        const res = await fetch(`${api}/v1/waitlist`, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(payload),
        });
        ok = res.ok;
      } catch {}

      submitBtn.disabled = false;
      submitBtn.textContent = L("Join Waitlist", "انضم إلى القائمة");
      if (!ok) {
        submitErr.hidden = false;
        return;
      }
      window.gtag?.("event", "generate_lead", { method: "waitlist", page: location.pathname });
      dialog.querySelector("#waitlist-form-container").hidden = true;
      dialog.querySelector("#waitlist-success-container").hidden = false;
    });

    document.body.appendChild(dialog);
    return dialog;
  };

  const open = () => {
    const dlg = createDialog();
    // Reset state if reopened
    const formContainer = dlg.querySelector("#waitlist-form-container");
    const successContainer = dlg.querySelector("#waitlist-success-container");
    if (successContainer.hidden === false) {
      formContainer.hidden = false;
      successContainer.hidden = true;
    }
    dlg.showModal();
    const emailField = dlg.querySelector("#wl-email");
    if (emailField) setTimeout(() => emailField.focus(), 50);
  };

  const close = () => {
    if (dialog && dialog.open) {
      dialog.close();
    }
  };

  // Open on clicking any [data-waitlist-open] or href="#waitlist"
  document.addEventListener("click", (e) => {
    const trigger = e.target.closest("[data-waitlist-open], a[href='#waitlist']");
    if (trigger) {
      e.preventDefault();
      const where = trigger.closest("header, nav, footer, section[id]");
      window.gtag?.("event", "waitlist_open", { location: where ? where.id || where.tagName.toLowerCase() : "page", page: location.pathname });
      open();
    }
  });

  // Handle URL hash on load
  if (location.hash === "#waitlist") {
    setTimeout(open, 100);
  }
})();
