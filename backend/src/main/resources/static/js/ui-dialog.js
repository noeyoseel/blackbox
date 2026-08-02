// 브라우저 기본 alert/prompt/confirm 대신 사이트 톤앤매너에 맞춘 토스트/모달.
// 전역 함수로 노출: showToast(message, type), showConfirm(message), showPrompt(message, defaultValue)
(() => {
  function ensureToastContainer() {
    let el = document.getElementById("toast-container");
    if (!el) {
      el = document.createElement("div");
      el.id = "toast-container";
      document.body.appendChild(el);
    }
    return el;
  }

  window.showToast = function (message, type) {
    const container = ensureToastContainer();
    const toast = document.createElement("div");
    toast.className = "bx-toast" + (type === "error" ? " bx-toast-error" : "");
    toast.textContent = message;
    container.appendChild(toast);
    requestAnimationFrame(() => toast.classList.add("bx-toast-show"));
    setTimeout(() => {
      toast.classList.remove("bx-toast-show");
      setTimeout(() => toast.remove(), 200);
    }, 2600);
  };

  function buildOverlay() {
    const overlay = document.createElement("div");
    overlay.className = "bx-modal-overlay";
    const panel = document.createElement("div");
    panel.className = "bx-modal-panel";
    overlay.appendChild(panel);
    document.body.appendChild(overlay);
    requestAnimationFrame(() => overlay.classList.add("bx-modal-show"));
    return { overlay, panel };
  }

  function closeOverlay(overlay) {
    overlay.classList.remove("bx-modal-show");
    setTimeout(() => overlay.remove(), 150);
  }

  function buildActions(panel, okLabel) {
    const actions = document.createElement("div");
    actions.className = "bx-modal-actions";
    const cancelBtn = document.createElement("button");
    cancelBtn.type = "button";
    cancelBtn.className = "bx-modal-btn bx-modal-btn-ghost";
    cancelBtn.textContent = "취소";
    const okBtn = document.createElement("button");
    okBtn.type = "button";
    okBtn.className = "bx-modal-btn";
    okBtn.textContent = okLabel || "확인";
    actions.appendChild(cancelBtn);
    actions.appendChild(okBtn);
    panel.appendChild(actions);
    return { cancelBtn, okBtn };
  }

  window.showConfirm = function (message) {
    return new Promise((resolve) => {
      const { overlay, panel } = buildOverlay();
      const text = document.createElement("div");
      text.className = "bx-modal-text";
      text.textContent = message;
      panel.appendChild(text);

      const { cancelBtn, okBtn } = buildActions(panel, "확인");

      function finish(result) {
        closeOverlay(overlay);
        resolve(result);
      }
      cancelBtn.addEventListener("click", () => finish(false));
      okBtn.addEventListener("click", () => finish(true));
      overlay.addEventListener("click", (e) => {
        if (e.target === overlay) finish(false);
      });
    });
  };

  window.showPrompt = function (message, defaultValue) {
    return new Promise((resolve) => {
      const { overlay, panel } = buildOverlay();
      const text = document.createElement("div");
      text.className = "bx-modal-text";
      text.textContent = message;
      panel.appendChild(text);

      const input = document.createElement("input");
      input.type = "text";
      input.className = "bx-modal-input";
      input.value = defaultValue || "";
      panel.appendChild(input);

      const { cancelBtn, okBtn } = buildActions(panel, "확인");

      function finish(result) {
        closeOverlay(overlay);
        resolve(result);
      }
      cancelBtn.addEventListener("click", () => finish(null));
      okBtn.addEventListener("click", () => finish(input.value));
      input.addEventListener("keydown", (e) => {
        if (e.key === "Enter") finish(input.value);
        if (e.key === "Escape") finish(null);
      });
      overlay.addEventListener("click", (e) => {
        if (e.target === overlay) finish(null);
      });
      setTimeout(() => input.focus(), 50);
    });
  };
})();
