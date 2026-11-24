// 북마크 카드 DOM을 클라이언트에서 생성하는 공용 함수. fragments/bookmark-card.html의
// 서버 렌더 마크업과 구조를 맞춰야 한다(무한 스크롤로 추가되는 카드가 첫 페이지 카드와
// 동일하게 보이도록). feed.js와 organize.js가 함께 쓴다.
//
// 체크박스는 정리 모드가 아닌 곳(피드 등)에서도 항상 DOM에 렌더링되지만, blackbox.css에서
// 조상에 .organize-mode 클래스가 있을 때만 보이도록 숨겨둔다 — bookmark-card.html fragment도
// 같은 방식이라 두 렌더러의 마크업이 어긋나지 않는다.
// ContentType.java의 라벨과 맞춰서 유지한다.
const CONTENT_TYPE_LABELS = { INFO: "정보", PLACE: "장소", PRODUCT: "제품", RESOURCE: "자료", OTHER: "기타" };

function el(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}

function buildBookmarkCard(b) {
  const card = el("div", "bookmark-item");
  card.dataset.bookmarkId = b.tweetId;

  const checkbox = document.createElement("input");
  checkbox.type = "checkbox";
  checkbox.className = "bookmark-select-checkbox";
  checkbox.dataset.tweetId = b.tweetId;
  card.appendChild(checkbox);

  if (b.authorName) {
    const header = el("a", "card-header");
    header.href = `https://x.com/${encodeURIComponent(b.authorHandle)}`;
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

  // textHtml은 서버(Bookmark.getTextHtml())가 이미 안전하게 이스케이프하고 URL만 <a>로
  // 감싸둔 HTML이라 innerHTML로 그대로 꽂아도 안전하다 — 링크 판단/이스케이프 로직을
  // 서버 한 곳에만 두고 여기서 다시 구현하지 않는다.
  const textDiv = el("div", "text");
  textDiv.innerHTML = b.textHtml;
  card.appendChild(textDiv);

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
  tagsRow.dataset.contentType = b.contentType || "";
  tagsRow.dataset.place = b.place || "";
  if (b.contentType || b.place) {
    const badges = el("div", "meta-badges");
    if (b.contentType) {
      badges.appendChild(el("span", "content-type-badge", CONTENT_TYPE_LABELS[b.contentType] || b.contentType));
    }
    if (b.place) {
      badges.appendChild(el("span", "place-badge", b.place));
    }
    tagsRow.appendChild(badges);
  }
  const tagList = el("div", "tag-list");
  (b.tags || []).forEach((tag) => {
    const link = el("a", "tag-pill", tag);
    link.href = `/tags/${encodeURIComponent(tag)}`;
    tagList.appendChild(link);
  });
  tagsRow.appendChild(tagList);
  tagsRow.appendChild(el("button", "tag-edit-toggle", "태그 수정")).type = "button";
  tagsRow.appendChild(el("button", "metadata-edit-toggle", "유형·장소 수정")).type = "button";
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

  const metadataEditRow = el("div", "metadata-edit-row");
  const typeSelect = document.createElement("select");
  typeSelect.className = "metadata-type-select";
  [["", "선택 안 함"], ["INFO", "정보"], ["PLACE", "장소"], ["PRODUCT", "제품"], ["RESOURCE", "자료"], ["OTHER", "기타"]]
    .forEach(([value, label]) => {
      const opt = document.createElement("option");
      opt.value = value;
      opt.textContent = label;
      typeSelect.appendChild(opt);
    });
  const placeInput = document.createElement("input");
  placeInput.type = "text";
  placeInput.className = "metadata-place-input";
  placeInput.placeholder = "장소 (선택사항)";
  const metadataSaveBtn = el("button", "metadata-save-btn tag-save-btn", "저장");
  metadataSaveBtn.type = "button";
  metadataEditRow.appendChild(typeSelect);
  metadataEditRow.appendChild(placeInput);
  metadataEditRow.appendChild(metadataSaveBtn);
  tagsRow.appendChild(metadataEditRow);

  card.appendChild(tagsRow);

  return card;
}
