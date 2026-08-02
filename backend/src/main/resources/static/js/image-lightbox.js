// 이미지 클릭하면 원본 트윗으로 이동하는 대신 크게보기 라이트박스를 띄운다.
// 한 트윗에 이미지가 여러 장이면 화살표(또는 ←/→ 키)로 넘겨볼 수 있다.
// 이벤트 위임 방식이라, 무한 스크롤로 새로 추가된 카드에도 별도 바인딩 없이 바로 동작한다.
(() => {
  let overlay = null;
  let images = [];
  let currentIndex = 0;

  function render() {
    if (!overlay) return;
    overlay.querySelector(".bx-lightbox-img").src = images[currentIndex];
    const counter = overlay.querySelector(".bx-lightbox-counter");
    if (counter) counter.textContent = `${currentIndex + 1} / ${images.length}`;
  }

  function navigate(delta) {
    if (images.length <= 1) return;
    currentIndex = (currentIndex + delta + images.length) % images.length;
    render();
  }

  function openLightbox(mediaUrls, startIndex, tweetUrl) {
    closeLightbox();
    images = mediaUrls;
    currentIndex = startIndex;

    overlay = document.createElement("div");
    overlay.className = "bx-lightbox-overlay";

    if (images.length > 1) {
      const prevBtn = document.createElement("button");
      prevBtn.type = "button";
      prevBtn.className = "bx-lightbox-nav bx-lightbox-prev";
      prevBtn.textContent = "‹";
      prevBtn.addEventListener("click", (e) => {
        e.stopPropagation();
        navigate(-1);
      });
      overlay.appendChild(prevBtn);
    }

    const img = document.createElement("img");
    img.className = "bx-lightbox-img";
    img.src = images[currentIndex];
    overlay.appendChild(img);

    if (images.length > 1) {
      const nextBtn = document.createElement("button");
      nextBtn.type = "button";
      nextBtn.className = "bx-lightbox-nav bx-lightbox-next";
      nextBtn.textContent = "›";
      nextBtn.addEventListener("click", (e) => {
        e.stopPropagation();
        navigate(1);
      });
      overlay.appendChild(nextBtn);
    }

    const footer = document.createElement("div");
    footer.className = "bx-lightbox-footer";
    if (images.length > 1) {
      const counter = document.createElement("span");
      counter.className = "bx-lightbox-counter";
      counter.textContent = `${currentIndex + 1} / ${images.length}`;
      footer.appendChild(counter);
    }
    const link = document.createElement("a");
    link.className = "bx-lightbox-link";
    link.href = tweetUrl;
    link.target = "_blank";
    link.rel = "noopener";
    link.textContent = "원본 트윗 보기 →";
    footer.appendChild(link);
    overlay.appendChild(footer);

    overlay.addEventListener("click", (e) => {
      if (e.target === overlay) closeLightbox();
    });

    document.body.appendChild(overlay);
    requestAnimationFrame(() => overlay.classList.add("bx-lightbox-show"));
  }

  function closeLightbox() {
    if (!overlay) return;
    const el = overlay;
    overlay = null;
    el.classList.remove("bx-lightbox-show");
    setTimeout(() => el.remove(), 150);
  }

  document.addEventListener("click", (e) => {
    const item = e.target.closest(".media-item");
    if (!item) return;
    const grid = item.closest(".media-grid");
    const items = Array.from(grid.querySelectorAll(".media-item"));
    const mediaUrls = items.map((el) => el.dataset.mediaUrl);
    openLightbox(mediaUrls, items.indexOf(item), item.dataset.tweetUrl);
  });

  document.addEventListener("keydown", (e) => {
    if (!overlay) return;
    if (e.key === "Escape") closeLightbox();
    if (e.key === "ArrowLeft") navigate(-1);
    if (e.key === "ArrowRight") navigate(1);
  });
})();
