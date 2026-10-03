// NowFocus Sitewide Waitlist & Feature Request Modal
(() => {
  let dialog = null;

  const createDialog = () => {
    if (dialog) return dialog;
    dialog = document.createElement("dialog");
    dialog.id = "waitlist-dialog";
    dialog.className = "waitlist-dialog";
    dialog.setAttribute("aria-labelledby", "waitlist-title");

    dialog.innerHTML = `
      <div class="waitlist-card">
        <button type="button" class="waitlist-close" aria-label="Close dialog" data-waitlist-close>
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="square"><line x1="18" y1="6" x2="6" y2="18"></line><line x1="6" y1="6" x2="18" y2="18"></line></svg>
        </button>

        <div id="waitlist-form-container">
          <div class="waitlist-head">
            <span class="waitlist-kicker">Early Access</span>
            <h2 id="waitlist-title">Join the Waitlist</h2>
            <p class="waitlist-desc">Be the first to access new platform builds, and request the features or focus tools you need most.</p>
          </div>

          <form id="waitlist-form" class="waitlist-form" novalidate>
            <div class="field">
              <label for="wl-email">Email address <span class="req">*</span></label>
              <input type="email" id="wl-email" name="email" required placeholder="you@domain.com" autocomplete="email">
              <span class="field-error" id="wl-email-err" hidden>Please enter a valid email address.</span>
            </div>

            <div class="field">
              <label id="wl-platforms-label">Platforms you plan to use</label>
              <div class="platform-chips" role="group" aria-labelledby="wl-platforms-label">
                <label class="chip"><input type="checkbox" name="platform" value="android" checked> <span>Android</span></label>
                <label class="chip"><input type="checkbox" name="platform" value="windows" checked> <span>Windows</span></label>
                <label class="chip"><input type="checkbox" name="platform" value="macos"> <span>macOS</span></label>
                <label class="chip"><input type="checkbox" name="platform" value="ios"> <span>iOS</span></label>
              </div>
            </div>

            <div class="field">
              <label for="wl-feature">Feature request / biggest distraction <span class="opt">(optional)</span></label>
              <textarea id="wl-feature" name="featureRequest" rows="3" placeholder="What distraction loop or feature do you wish focus apps solved?"></textarea>
              <span class="field-hint">Public: a feature request is posted as an issue on our GitHub repo. Your email is never included. <a href="/privacy/#waitlist">Privacy</a></span>
            </div>

            <p class="field-error" id="wl-submit-err" role="alert" hidden>Couldn’t save that. Please try again in a minute, or email safwat.rashwan@gmail.com.</p>
            <div class="waitlist-actions">
              <button type="submit" class="btn btn-primary" id="wl-submit-btn">Join Waitlist</button>
            </div>
          </form>
        </div>

        <div id="waitlist-success-container" class="waitlist-success" hidden>
          <div class="success-mark">✓</div>
          <h3>You're on the list!</h3>
          <p>We'll email you when your platform is ready. If you included a feature request, it's now an open issue on our GitHub repo.</p>
          <div class="waitlist-actions">
            <button type="button" class="btn btn-primary" data-waitlist-close>Back to site</button>
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
      submitBtn.textContent = "Saving...";

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
      submitBtn.textContent = "Join Waitlist";
      if (!ok) {
        submitErr.hidden = false;
        return;
      }
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
      open();
    }
  });

  // Handle URL hash on load
  if (location.hash === "#waitlist") {
    setTimeout(open, 100);
  }
})();
