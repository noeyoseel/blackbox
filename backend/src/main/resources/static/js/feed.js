// 메인 피드("/") 무한 스크롤. 최근 저장한 북마크 순서를 그대로 유지하며
// 서버에서 페이지 단위로 더 불러와 카드 목록 아래에 이어붙인다.
(() => {
  const listEl = document.getElementById("feed-list");
  const sentinelEl = document.getElementById("feed-sentinel");
  if (!listEl || !sentinelEl) return;

  let page = initialPage;
  let loading = false;

  function el(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined) node.textContent = text;
    return node;
  }

  function buildCard(b) {
    const card = el("div", "bookmark-item");
    card.dataset.bookmarkId = b.tweetId;

    if (b.authorName) {
      const header = el("a", "card-header");
      header.href = `https://x.com/${b.authorHandle}`;
      header.target = "_blank";
      header.rel = "noopener";
      if (b.authorAvatar) {
        const avatar = document.createElement("img");
        avatar.className = "card-avatar";
        avatar.src = b.authorAvatar;
        avatar.alt = "";
        header.appendChild(avatar);
      }
      const headerText = el("div", "card-header-text");
      headerText.appendChild(el("span", "card-name", b.authorName));
      headerText.appendChild(el("span", "card-handle", `@${b.authorHandle}`));
      header.appendChild(headerText);
      card.appendChild(header);
    }

    card.appendChild(el("div", "text", b.text));

    if (b.media && b.media.length > 0) {
      const grid = el("div", `media-grid media-count-${b.media.length}`);
      b.media.forEach((m) => {
        const item = el("button", "media-item");
        item.type = "button";
        item.dataset.mediaUrl = `${m.url}?format=jpg&name=large`;
        item.dataset.tweetUrl = `https://x.com/i/status/${b.tweetId}`;
        const img = document.createElement("img");
        img.src = m.url;
        img.alt = "";
        img.loading = "lazy";
        item.appendChild(img);
        grid.appendChild(item);
      });
      card.appendChild(grid);
    }

    if (b.quotedText) {
      const quoteLink = el("a", "quote-card");
      quoteLink.href = b.quotedTweetId
        ? `https://x.com/i/status/${b.quotedTweetId}`
        : "#";
      quoteLink.target = "_blank";
      quoteLink.rel = "noopener";

      if (b.quotedAuthorName) {
        const header = el("div", "quote-card-header");
        if (b.quotedAuthorAvatar) {
          const avatar = document.createElement("img");
          avatar.className = "quote-avatar";
          avatar.src = b.quotedAuthorAvatar;
          avatar.alt = "";
          header.appendChild(avatar);
        }
        header.appendChild(el("span", "quote-name", b.quotedAuthorName));
        header.appendChild(el("span", "quote-handle", `@${b.quotedAuthorHandle}`));
        quoteLink.appendChild(header);
      }
      quoteLink.appendChild(el("div", "quote-text", b.quotedText));
      card.appendChild(quoteLink);
    }

    const meta = el("div", "meta");
    meta.appendChild(el("span", null, b.displayCreatedAt));
    const originalLink = el("a", null, "원본 보기 →");
    originalLink.href = `https://x.com/i/status/${b.tweetId}`;
    originalLink.target = "_blank";
    originalLink.rel = "noopener";
    meta.appendChild(originalLink);
    card.appendChild(meta);

    const tagsRow = el("div", "tags-row");
    tagsRow.dataset.tweetId = b.tweetId;
    tagsRow.dataset.folderId = b.folderId != null ? String(b.folderId) : "";
    const tagList = el("div", "tag-list");
    (b.tags || []).forEach((tag) => {
      const link = el("a", "tag-pill", tag);
      link.href = `/tags/${encodeURIComponent(tag)}`;
      tagList.appendChild(link);
    });
    if (b.folderName) {
      const folderBadge = el("a", "folder-badge", b.folderName);
      folderBadge.href = `/folders/${b.folderId}`;
      tagList.appendChild(folderBadge);
    }
    tagsRow.appendChild(tagList);
    tagsRow.appendChild(el("button", "tag-edit-toggle", "태그 수정")).type = "button";
    tagsRow.appendChild(el("button", "folder-toggle", "폴더")).type = "button";
    const archiveBtn = el("button", "archive-toggle", "보관");
    archiveBtn.type = "button";
    archiveBtn.dataset.archived = "false";
    tagsRow.appendChild(archiveBtn);
    const editRow = el("div", "tag-edit-row");
    const input = document.createElement("input");
    input.type = "text";
    input.className = "tag-edit-input";
    input.placeholder = "태그를 콤마로 구분해서 입력";
    const saveBtn = el("button", "tag-save-btn", "저장");
    saveBtn.type = "button";
    editRow.appendChild(input);
    editRow.appendChild(saveBtn);
    tagsRow.appendChild(editRow);
    const folderPickerRow = el("div", "folder-picker-row");
    const folderSelect = document.createElement("select");
    folderSelect.className = "folder-select";
    folderPickerRow.appendChild(folderSelect);
    tagsRow.appendChild(folderPickerRow);
    card.appendChild(tagsRow);

    return card;
  }

  async function loadNextPage() {
    if (loading || !hasMore) return;
    loading = true;
    sentinelEl.textContent = "불러오는 중...";
    page += 1;
    try {
      const res = await fetch(`/api/bookmarks/feed?page=${page}&size=${pageSize}&sort=${feedSort}`);
      if (!res.ok) throw new Error("피드 로딩 실패: " + res.status);
      const data = await res.json();
      data.bookmarks.forEach((b) => {
        const card = buildCard(b);
        listEl.appendChild(card);
        bindTagEditors(card);
        bindArchiveButtons(card);
        bindFolderPickers(card);
      });
      hasMore = data.hasMore;
    } catch (err) {
      console.error(err);
      page -= 1;
    } finally {
      loading = false;
      sentinelEl.textContent = "";
    }
  }

  const observer = new IntersectionObserver(
    (entries) => {
      if (entries[0].isIntersecting) loadNextPage();
    },
    { rootMargin: "400px" }
  );
  observer.observe(sentinelEl);
})();
