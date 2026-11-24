// 홈("/") 통합 스크립트. mode(feed|organize)에 따라 리스트 로딩 엔드포인트와 정리 모드의
// 선택·일괄액션 UI를 켜고 끈다. 기존 feed.js(무한스크롤)와 organize.js(선택/일괄액션/
// 메타데이터 인라인편집)를 병합했다 — 정리 모드가 별도 페이지가 아니라 홈의 한 상태이므로
// 스크립트도 하나로 합친다. 카드 DOM 생성은 bookmark-card-render.js(공유)를 그대로 쓴다.
(() => {
  const listEl = document.getElementById("feed-list");
  const sentinelEl = document.getElementById("feed-sentinel");
  const emptyEl = document.getElementById("feed-empty");
  const isOrganize = homeMode === "organize";

  let page = 0;
  let loading = false;
  // 정리 모드의 카드 인라인 유형·장소 편집 바인딩. 정리 모드일 때만 아래에서 실제 함수로
  // 채워진다 — 무한 스크롤로 추가되는 카드에도 같은 함수를 적용해야 해서 바깥 스코프에 둔다.
  let bindMetadataEditors = null;

  function showEmptyStateIfDone() {
    if (!listEl || !emptyEl) return;
    if (!homeHasMore && listEl.querySelectorAll(".bookmark-item").length === 0) {
      listEl.style.display = "none";
      emptyEl.style.display = "flex";
    }
  }

  function buildListUrl(page) {
    if (isOrganize) {
      return `/api/bookmarks/organize?filter=${encodeURIComponent(homeFilter)}&page=${page}&size=${homePageSize}&sort=${homeSort}`;
    }
    const params = new URLSearchParams({ page, size: homePageSize, sort: homeSort });
    if (homeContentType) params.set("contentType", homeContentType);
    if (homeTag) params.set("tag", homeTag);
    return `/api/bookmarks/feed?${params.toString()}`;
  }

  async function loadNextPage() {
    if (loading || !homeHasMore || !listEl || !sentinelEl) return;
    loading = true;
    sentinelEl.textContent = "불러오는 중...";
    page += 1;
    try {
      const res = await fetch(buildListUrl(page));
      if (!res.ok) throw new Error("목록 로딩 실패: " + res.status);
      const data = await res.json();
      data.bookmarks.forEach((b) => {
        const card = buildBookmarkCard(b);
        listEl.appendChild(card);
        bindTagEditors(card);
        bindArchiveButtons(card);
        if (isOrganize && bindMetadataEditors) bindMetadataEditors(card);
      });
      homeHasMore = data.hasMore;
    } catch (err) {
      console.error(err);
      page -= 1;
    } finally {
      loading = false;
      sentinelEl.textContent = "";
    }
  }

  if (sentinelEl) {
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries[0].isIntersecting) loadNextPage();
      },
      { rootMargin: "400px" }
    );
    observer.observe(sentinelEl);
  }

  // ---- 정리 모드: 다중 선택 + 일괄 액션(태그/유형/장소/보관) + 카드 인라인 편집 ----
  const actionbar = document.getElementById("organize-actionbar");
  if (actionbar) {
    const countEl = document.getElementById("organize-selection-count");
    const totalCountEl = document.getElementById("organize-total-count");
    const selectAllBtn = document.getElementById("organize-select-all");
    const selectNoneBtn = document.getElementById("organize-select-none");
    const archiveBtn = document.getElementById("organize-archive-btn");
    const classifyToggle = document.getElementById("organize-classify-toggle");
    const classifyPanel = document.getElementById("organize-classify-panel");
    const tagInput = document.getElementById("organize-tag-input");
    const tagConfirmBtn = document.getElementById("organize-tag-confirm-btn");
    const contentTypeSelect = document.getElementById("organize-content-type-select");
    const placeInput = document.getElementById("organize-place-input");
    const placeApplyBtn = document.getElementById("organize-place-apply-btn");

    let totalCandidates = parseInt((totalCountEl.textContent.match(/\d+/) || ["0"])[0], 10);
    const selected = new Set();

    // 현재 활성 필터 조건을 카드가 지금 보여주는 태그/유형 상태 기준으로 재평가한다.
    // place는 필터 대상이 아니다 — 장소가 없는 게 정상인 콘텐츠가 많아서(개발/재테크 정보 등)
    // place IS NULL을 "미정리"로 취급하면 대부분의 정상 콘텐츠가 영원히 후보에 남기 때문이다.
    function matchesCurrentFilter(card) {
      const row = card.querySelector(".tags-row");
      const hasTags = row.querySelector(".tag-list .tag-pill") !== null;
      const hasContentType = !!row.dataset.contentType;
      switch (homeFilter) {
        case "no_tag":
          return !hasTags;
        case "no_content_type":
          return !hasContentType;
        default:
          return !hasTags || !hasContentType;
      }
    }

    function updateActionbar() {
      if (selected.size > 0) {
        actionbar.classList.add("visible");
        countEl.textContent = `${selected.size}개 선택됨`;
      } else {
        actionbar.classList.remove("visible");
        classifyPanel.classList.remove("visible");
        classifyToggle.textContent = "▸ 분류 일괄 적용";
      }
    }

    function setTotalCandidates(value) {
      totalCandidates = Math.max(0, value);
      totalCountEl.textContent = `정리 대상 ${totalCandidates}건`;
    }

    function findCard(tweetId) {
      return listEl.querySelector(`.bookmark-item[data-bookmark-id="${CSS.escape(tweetId)}"]`);
    }

    function addTagPillsToCard(card, tags) {
      const row = card.querySelector(".tags-row");
      const tagList = row.querySelector(".tag-list");
      const existing = new Set(
        Array.from(tagList.querySelectorAll(".tag-pill")).map((el) => el.textContent)
      );
      tags.forEach((tag) => {
        if (existing.has(tag)) return;
        const link = document.createElement("a");
        link.className = "tag-pill";
        link.textContent = tag;
        link.href = `/tags/${encodeURIComponent(tag)}`;
        tagList.appendChild(link);
      });
    }

    // 유형/장소 배지를 최신 상태로 다시 그린다. 개별 저장(bindMetadataEditors)과 배치 유형/
    // 장소 지정(setCardContentType/setCardPlace) 양쪽에서 공유하는 단일 렌더 지점이다.
    function updateCardMetadataBadges(row, contentType, place) {
      row.dataset.contentType = contentType || "";
      row.dataset.place = place || "";
      let badges = row.querySelector(".meta-badges");
      if (!contentType && !place) {
        if (badges) badges.remove();
        return;
      }
      if (!badges) {
        badges = document.createElement("div");
        badges.className = "meta-badges";
        row.insertBefore(badges, row.firstChild);
      }
      badges.innerHTML = "";
      if (contentType) {
        const span = document.createElement("span");
        span.className = "content-type-badge";
        span.textContent = CONTENT_TYPE_LABELS[contentType] || contentType;
        badges.appendChild(span);
      }
      if (place) {
        const span = document.createElement("span");
        span.className = "place-badge";
        span.textContent = place;
        badges.appendChild(span);
      }
    }

    function setCardContentType(card, contentType) {
      const row = card.querySelector(".tags-row");
      updateCardMetadataBadges(row, contentType, row.dataset.place || null);
    }

    function setCardPlace(card, place) {
      const row = card.querySelector(".tags-row");
      updateCardMetadataBadges(row, row.dataset.contentType || null, place);
    }

    // 배치 액션 성공 후 공통 처리: 서버가 실제로 반영한 tweetId(updatedTweetIds)만 화면에
    // 반영한다 — 선택 당시와 처리 사이에 다른 곳에서 지워진 항목까지 반영된 것처럼 그리지
    // 않기 위함. patchCard가 카드를 바꾼 뒤, 더 이상 필터 조건에 안 맞으면 제거한다.
    function applyBatchResult(result, patchCard, { alwaysRemove = false } = {}) {
      let removedCount = 0;
      result.updatedTweetIds.forEach((tweetId) => {
        selected.delete(tweetId);
        const card = findCard(tweetId);
        if (!card) return;
        const checkbox = card.querySelector(".bookmark-select-checkbox");
        if (checkbox) checkbox.checked = false;

        if (alwaysRemove) {
          card.remove();
          removedCount += 1;
          return;
        }
        patchCard(card);
        if (!matchesCurrentFilter(card)) {
          card.remove();
          removedCount += 1;
        }
      });

      if (result.notFound && result.notFound.length > 0) {
        showToast(`${result.notFound.length}건은 이미 변경되어 처리되지 못했습니다.`, "error");
      }
      if (removedCount > 0) setTotalCandidates(totalCandidates - removedCount);
      updateActionbar();
      showEmptyStateIfDone();
    }

    // 카드 내 "유형·장소 수정" 토글 + 인라인 편집(select+input+저장). 정리 모드 전용이라
    // 여기서만 바인딩한다(다른 모드는 CSS로 버튼 자체를 숨김 — blackbox.css 참고).
    bindMetadataEditors = function (root) {
      root.querySelectorAll(".tags-row").forEach((row) => {
        const tweetId = row.dataset.tweetId;
        const toggleBtn = row.querySelector(".metadata-edit-toggle");
        const editRow = row.querySelector(".metadata-edit-row");
        const typeSelect = row.querySelector(".metadata-type-select");
        const placeInputEl = row.querySelector(".metadata-place-input");
        const saveBtn = row.querySelector(".metadata-save-btn");
        if (!toggleBtn) return;

        toggleBtn.addEventListener("click", () => {
          const showing = editRow.classList.contains("visible");
          if (!showing) {
            typeSelect.value = row.dataset.contentType || "";
            placeInputEl.value = row.dataset.place || "";
          }
          editRow.classList.toggle("visible", !showing);
        });

        saveBtn.addEventListener("click", async () => {
          const contentType = typeSelect.value || null;
          const place = placeInputEl.value.trim() || null;
          saveBtn.disabled = true;
          try {
            const res = await fetch(`/api/bookmarks/${encodeURIComponent(tweetId)}/metadata`, {
              method: "PATCH",
              headers: { "Content-Type": "application/json" },
              body: JSON.stringify({ contentType, place }),
            });
            if (!res.ok) throw new Error("메타데이터 저장 실패: " + res.status);
            const data = await res.json();
            updateCardMetadataBadges(row, data.contentType, data.place);
            editRow.classList.remove("visible");

            const card = row.closest(".bookmark-item");
            if (card && !matchesCurrentFilter(card)) {
              card.remove();
              setTotalCandidates(totalCandidates - 1);
              showEmptyStateIfDone();
            }
          } catch (err) {
            console.error(err);
            showToast("메타데이터 저장에 실패했습니다.", "error");
          } finally {
            saveBtn.disabled = false;
          }
        });
      });
    };

    listEl.addEventListener("change", (e) => {
      const cb = e.target;
      if (!cb.classList || !cb.classList.contains("bookmark-select-checkbox")) return;
      const tweetId = cb.dataset.tweetId;
      if (cb.checked) selected.add(tweetId);
      else selected.delete(tweetId);
      updateActionbar();
    });

    selectAllBtn.addEventListener("click", () => {
      listEl.querySelectorAll(".bookmark-select-checkbox").forEach((cb) => {
        cb.checked = true;
        selected.add(cb.dataset.tweetId);
      });
      updateActionbar();
    });

    selectNoneBtn.addEventListener("click", () => {
      listEl.querySelectorAll(".bookmark-select-checkbox").forEach((cb) => (cb.checked = false));
      selected.clear();
      updateActionbar();
    });

    classifyToggle.addEventListener("click", () => {
      const expanded = classifyPanel.classList.toggle("visible");
      classifyToggle.textContent = expanded ? "▾ 분류 일괄 적용" : "▸ 분류 일괄 적용";
    });

    tagConfirmBtn.addEventListener("click", async () => {
      const tags = tagInput.value
        .split(",")
        .map((t) => t.trim())
        .filter(Boolean);
      if (tags.length === 0 || selected.size === 0) return;

      const tweetIds = Array.from(selected);
      tagConfirmBtn.disabled = true;
      try {
        const res = await fetch("/api/bookmarks/batch/tags", {
          method: "PATCH",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ tweetIds, tagsToAdd: tags }),
        });
        if (!res.ok) throw new Error("일괄 태그 추가 실패: " + res.status);
        const result = await res.json();
        applyBatchResult(result, (card) => addTagPillsToCard(card, tags));
        showToast(`${result.updatedTweetIds.length}건에 태그를 추가했습니다.`);
        tagInput.value = "";
      } catch (err) {
        console.error(err);
        showToast("일괄 태그 추가에 실패했습니다.", "error");
      } finally {
        tagConfirmBtn.disabled = false;
      }
    });

    // 유형 지정: 즉시-반영 select 패턴. contentType만 보내고 place는
    // 아예 요청에 넣지 않는다 — 서버가 "요청에 없는 필드는 안 건드림" 규칙을 따르므로,
    // 다른 북마크들의 기존 place가 이 액션 때문에 지워지지 않는다.
    contentTypeSelect.addEventListener("change", async () => {
      const value = contentTypeSelect.value;
      if (!value || selected.size === 0) {
        contentTypeSelect.value = "";
        return;
      }
      const tweetIds = Array.from(selected);
      try {
        const res = await fetch("/api/bookmarks/batch/metadata", {
          method: "PATCH",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ tweetIds, contentType: value }),
        });
        if (!res.ok) throw new Error("일괄 유형 지정 실패: " + res.status);
        const result = await res.json();
        applyBatchResult(result, (card) => setCardContentType(card, value));
        showToast(`${result.updatedTweetIds.length}건에 유형을 지정했습니다.`);
      } catch (err) {
        console.error(err);
        showToast("일괄 유형 지정에 실패했습니다.", "error");
      } finally {
        contentTypeSelect.value = "";
      }
    });

    // 장소 지정: 자유 텍스트라 select처럼 onChange 즉시반영이 안 맞아 버튼으로 확정한다.
    // 빈 값으로 적용하면 서버 규칙상 place를 명시적으로 지운다(placeholder에 안내 문구 있음).
    // contentType은 요청에 넣지 않아 다른 북마크들의 기존 유형을 건드리지 않는다.
    placeApplyBtn.addEventListener("click", async () => {
      if (selected.size === 0) return;
      const value = placeInput.value.trim();
      const tweetIds = Array.from(selected);
      placeApplyBtn.disabled = true;
      try {
        const res = await fetch("/api/bookmarks/batch/metadata", {
          method: "PATCH",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ tweetIds, place: value }),
        });
        if (!res.ok) throw new Error("일괄 장소 지정 실패: " + res.status);
        const result = await res.json();
        applyBatchResult(result, (card) => setCardPlace(card, value === "" ? null : value));
        showToast(`${result.updatedTweetIds.length}건에 장소를 지정했습니다.`);
        placeInput.value = "";
      } catch (err) {
        console.error(err);
        showToast("일괄 장소 지정에 실패했습니다.", "error");
      } finally {
        placeApplyBtn.disabled = false;
      }
    });

    archiveBtn.addEventListener("click", async () => {
      if (selected.size === 0) return;
      const tweetIds = Array.from(selected);
      archiveBtn.disabled = true;
      try {
        const res = await fetch("/api/bookmarks/batch/archive", {
          method: "PATCH",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ tweetIds, archived: true }),
        });
        if (!res.ok) throw new Error("일괄 보관 실패: " + res.status);
        const result = await res.json();
        applyBatchResult(result, null, { alwaysRemove: true });
        showToast(`${result.updatedTweetIds.length}건을 보관했습니다.`);
      } catch (err) {
        console.error(err);
        showToast("일괄 보관에 실패했습니다.", "error");
      } finally {
        archiveBtn.disabled = false;
      }
    });

    bindMetadataEditors(document);
    showEmptyStateIfDone();
  }

  // ---- 필터 패널 (Content Type / Tag) — 피드 모드 전용 ----
  const filterToggleBtn = document.getElementById("filter-toggle-btn");
  if (filterToggleBtn) {
    const filterPanel = document.getElementById("filter-panel");
    if (homeContentType || homeTag) filterPanel.classList.add("visible");
    filterToggleBtn.addEventListener("click", () => {
      filterPanel.classList.toggle("visible");
    });

    const filterTagOptions = document.getElementById("filter-tag-options");
    fetch("/api/tags")
      .then((res) => (res.ok ? res.json() : []))
      .then((tags) => {
        tags.forEach((t) => {
          const opt = document.createElement("option");
          opt.value = t;
          filterTagOptions.appendChild(opt);
        });
      })
      .catch((err) => console.error(err));
  }

  // ---- AI 자동 분류 배너 + 모달 — 피드 모드 전용 ----
  const aiStartBtn = document.getElementById("ai-classify-start-btn");
  if (aiStartBtn) {
    const overlay = document.getElementById("ai-modal-overlay");
    const body = document.getElementById("ai-modal-body");
    const closeBtn = document.getElementById("ai-modal-close-btn");
    const applyBtn = document.getElementById("ai-modal-apply-btn");
    const CONTENT_TYPE_ORDER = ["INFO", "PLACE", "PRODUCT", "RESOURCE", "OTHER"];
    let latestProposals = [];
    let progressPollId = null;
    let progressTextEl = null;
    let progressFillEl = null;

    function openModal() {
      overlay.classList.add("visible");
    }
    function closeModal() {
      overlay.classList.remove("visible");
      stopProgressPolling();
    }

    function renderLoading() {
      applyBtn.style.display = "none";
      body.innerHTML = "";
      const wrap = document.createElement("div");
      wrap.className = "ai-modal-loading";
      const row = document.createElement("div");
      row.className = "ai-modal-loading-row";
      const spinner = document.createElement("div");
      spinner.className = "ai-modal-spinner";
      progressTextEl = document.createElement("span");
      progressTextEl.textContent = "북마크를 분석하고 있습니다...";
      row.appendChild(spinner);
      row.appendChild(progressTextEl);
      const track = document.createElement("div");
      track.className = "ai-modal-progress-track";
      progressFillEl = document.createElement("div");
      progressFillEl.className = "ai-modal-progress-fill";
      track.appendChild(progressFillEl);
      wrap.appendChild(row);
      wrap.appendChild(track);
      body.appendChild(wrap);
      startProgressPolling();
    }

    function applyProgress(total, completed) {
      if (!progressTextEl || !progressFillEl) return;
      if (total > 0) {
        progressTextEl.textContent = `${completed} / ${total}개 분석 중...`;
        progressFillEl.style.width = `${Math.min(100, (completed / total) * 100)}%`;
      } else {
        progressTextEl.textContent = "북마크를 분석하고 있습니다...";
        progressFillEl.style.width = "0%";
      }
    }

    function startProgressPolling() {
      stopProgressPolling();
      progressPollId = setInterval(async () => {
        try {
          const res = await fetch("/api/tagging/metadata-extract/progress");
          if (!res.ok) return;
          const { total, completed } = await res.json();
          applyProgress(total, completed);
        } catch (err) {
          console.error(err);
        }
      }, 500);
    }

    function stopProgressPolling() {
      if (progressPollId !== null) {
        clearInterval(progressPollId);
        progressPollId = null;
      }
    }

    // AI 제안 중 place는 아직 검증 단계라 이 모달·적용 흐름에서는 다루지 않는다
    // (contentType만 집계·적용). place는 카드별 "유형·장소 수정"에서 개별적으로 다룬다.
    function renderResult(result) {
      latestProposals = result.proposals || [];
      body.innerHTML = "";

      const summary = document.createElement("div");
      summary.className = "ai-modal-summary";
      summary.textContent = `${result.attempted}개의 북마크를 분석했습니다.`;
      body.appendChild(summary);

      const counts = {};
      latestProposals.forEach((p) => {
        if (!p.contentType) return;
        counts[p.contentType] = (counts[p.contentType] || 0) + 1;
      });

      const list = document.createElement("div");
      list.className = "ai-modal-breakdown";
      CONTENT_TYPE_ORDER.filter((ct) => counts[ct]).forEach((ct) => {
        const row = document.createElement("div");
        row.className = "ai-modal-breakdown-row";
        const label = document.createElement("span");
        label.textContent = CONTENT_TYPE_LABELS[ct] || ct;
        const count = document.createElement("span");
        count.textContent = `${counts[ct]}건`;
        row.appendChild(label);
        row.appendChild(count);
        list.appendChild(row);
      });
      body.appendChild(list);

      if (result.failed > 0) {
        const failedNote = document.createElement("div");
        failedNote.className = "ai-modal-failed-note";
        failedNote.textContent = `${result.failed}건은 분석에 실패했습니다.`;
        body.appendChild(failedNote);
      }

      if (latestProposals.length === 0) {
        const empty = document.createElement("div");
        empty.className = "ai-modal-loading";
        empty.textContent = "분류할 새 북마크가 없습니다.";
        body.appendChild(empty);
      }

      applyBtn.style.display = latestProposals.length > 0 ? "inline-block" : "none";
    }

    async function startClassification() {
      openModal();
      renderLoading();
      try {
        const res = await fetch("/api/tagging/metadata-extract", { method: "POST" });
        if (res.status === 402) {
          closeModal();
          showToast("AI 자동 분류는 라이선스 활성화가 필요합니다.", "error");
          return;
        }
        if (!res.ok) throw new Error("AI 분류 실패: " + res.status);
        const result = await res.json();
        stopProgressPolling();
        renderResult(result);
        showToast(`분류가 끝났습니다. ${result.succeeded}건 분석 완료.`);
      } catch (err) {
        console.error(err);
        closeModal();
        showToast("AI 자동 분류에 실패했습니다.", "error");
      }
    }

    aiStartBtn.addEventListener("click", startClassification);
    closeBtn.addEventListener("click", closeModal);
    overlay.addEventListener("click", (e) => {
      if (e.target === overlay) closeModal();
    });

    applyBtn.addEventListener("click", async () => {
      if (latestProposals.length === 0) return;
      const byContentType = {};
      latestProposals.forEach((p) => {
        if (!p.contentType) return;
        (byContentType[p.contentType] = byContentType[p.contentType] || []).push(p.tweetId);
      });

      applyBtn.disabled = true;
      try {
        let appliedTotal = 0;
        for (const [contentType, tweetIds] of Object.entries(byContentType)) {
          const res = await fetch("/api/bookmarks/batch/metadata", {
            method: "PATCH",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ tweetIds, contentType }),
          });
          if (!res.ok) throw new Error("일괄 유형 지정 실패: " + res.status);
          const result = await res.json();
          appliedTotal += result.updatedTweetIds.length;
        }
        showToast(`${appliedTotal}건에 유형을 적용했습니다.`);
        closeModal();
        location.reload();
      } catch (err) {
        console.error(err);
        showToast("적용에 실패했습니다.", "error");
      } finally {
        applyBtn.disabled = false;
      }
    });
  }
})();
