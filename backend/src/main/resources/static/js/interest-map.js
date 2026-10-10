(() => {
  const featuredWrap = document.getElementById("interest-map-featured");
  const allList = document.getElementById("interest-map-cluster-list");
  const selectedSection = document.getElementById("interest-map-selected-section");
  const selectedHeading = document.getElementById("interest-map-selected-heading");
  const noiseToggle = document.getElementById("interest-map-noise-toggle");
  const noiseList = document.getElementById("interest-map-noise-list");
  if (!selectedSection) return;

  function escapeHtml(s) {
    return (s || "").replace(/[&<>"']/g, (c) => ({
      "&": "&amp;",
      "<": "&lt;",
      ">": "&gt;",
      '"': "&quot;",
      "'": "&#39;",
    }[c]));
  }

  function cssVar(name) {
    return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
  }

  // 선택/noise 목록 둘 다 기존 bookmark-card 마크업(bookmark-card-render.js)을 그대로 써서
  // 태그 수정/보관 같은 기존 기능을 그대로 이어붙인다 — 여기서 새로 만들지 않는다.
  // 이 화면은 "훑어보며 필요한 것만 처리"하는 공간이라, 아직 연결하지 않은 유형·장소 수정
  // 버튼만 숨긴다(다른 페이지의 정리 모드 전용 기능이라 여기서는 바인딩할 함수가 없다).
  //
  // 목록은 API에서 한 번에 다 받아오지만(194건짜리 cluster도 그 정도는 가벼움), DOM에는
  // 한 번에 다 그리지 않는다 — 첫 화면은 MEMBERS_PAGE_SIZE개만 그리고 "더 보기"로 나머지를
  // 이미 받아둔 배열에서 잘라 붙인다. 새 API/서버 페이지네이션을 추가하지 않고, cluster를
  // 바꿀 때도 재요청 없이 그대로 재사용할 수 있는 가장 단순한 방식이라 이걸 택했다.
  const MEMBERS_PAGE_SIZE = 20;

  function renderMembers(container, bookmarks) {
    container.innerHTML = "";
    if (!bookmarks || bookmarks.length === 0) {
      container.innerHTML = '<div class="empty">표시할 북마크가 없습니다.</div>';
      return;
    }
    const list = document.createElement("div");
    list.className = "bookmark-list";
    container.appendChild(list);

    let shown = 0;
    let moreBtn = null;

    function appendChunk() {
      bookmarks.slice(shown, shown + MEMBERS_PAGE_SIZE).forEach((b) => {
        const card = buildBookmarkCard(b);
        const metadataToggle = card.querySelector(".metadata-edit-toggle");
        if (metadataToggle) metadataToggle.style.display = "none";
        list.appendChild(card);
        bindTagEditors(card);
        bindArchiveButtons(card);
      });
      shown = Math.min(shown + MEMBERS_PAGE_SIZE, bookmarks.length);
    }

    appendChunk();
    if (shown < bookmarks.length) {
      moreBtn = document.createElement("button");
      moreBtn.type = "button";
      moreBtn.className = "interest-map-load-more-btn home-control-btn";
      moreBtn.textContent = `더 보기 (${bookmarks.length - shown}건 남음)`;
      moreBtn.addEventListener("click", () => {
        appendChunk();
        if (shown < bookmarks.length) {
          moreBtn.textContent = `더 보기 (${bookmarks.length - shown}건 남음)`;
        } else {
          moreBtn.remove();
        }
      });
      container.appendChild(moreBtn);
    }
  }

  // ===== 2D Interest Map (UMAP, 서버에서 미리 계산된 좌표를 읽기만 함) =====
  const canvas = document.getElementById("interest-map-canvas");
  const tooltip = document.getElementById("interest-map-tooltip");
  const clearSelectionBtn = document.getElementById("interest-map-clear-selection");
  const mapState = { points: [], selectedCluster: null, hoverIndex: -1 };
  // 서버가 이미 크기순으로 정렬해 둔 상위 5개 cluster 번호. 새로 계산하지 않고 그대로 읽기만
  // 한다 — 152개 전부에 색을 주지 않기 위한 구분선이다.
  const topClusters = (window.INTEREST_MAP_TOP_CLUSTERS || []).map(Number);

  // 상위 5개 안에 속하면 순위에 맞는 --cluster-color-N 변수명을, 아니면 null을 돌려준다.
  function clusterColorVar(clusterNumber) {
    const idx = topClusters.indexOf(Number(clusterNumber));
    return idx >= 0 ? `--cluster-color-${idx + 1}` : null;
  }

  function initMap() {
    if (!canvas) return;
    const ctx = canvas.getContext("2d");

    fetch("/api/interest-map/umap")
      .then((res) => {
        if (!res.ok) throw new Error("UMAP 불러오기 실패: " + res.status);
        return res.json();
      })
      .then((points) => {
        mapState.points = layoutPoints(points, canvas.width, canvas.height);
        drawMap(ctx);
      })
      .catch((err) => console.error(err));

    canvas.addEventListener("mousemove", (e) => onCanvasHover(e, ctx));
    canvas.addEventListener("mouseleave", () => {
      mapState.hoverIndex = -1;
      if (tooltip) tooltip.style.display = "none";
      drawMap(ctx);
    });
    canvas.addEventListener("click", (e) => onCanvasClick(e));

    if (clearSelectionBtn) {
      clearSelectionBtn.addEventListener("click", () => deselectCluster());
    }
  }

  // 데이터의 x/y 범위를 캔버스 픽셀 좌표로 선형 변환해서 각 점에 붙여둔다(마우스 판정/그리기용).
  function layoutPoints(points, width, height) {
    if (points.length === 0) return [];
    const padding = 16;
    const xs = points.map((p) => p.x);
    const ys = points.map((p) => p.y);
    const minX = Math.min(...xs);
    const maxX = Math.max(...xs);
    const minY = Math.min(...ys);
    const maxY = Math.max(...ys);
    const spanX = maxX - minX || 1;
    const spanY = maxY - minY || 1;

    return points.map((p) => ({
      ...p,
      px: padding + ((p.x - minX) / spanX) * (width - padding * 2),
      py: padding + ((p.y - minY) / spanY) * (height - padding * 2),
    }));
  }

  function drawMap(ctx) {
    const { width, height } = ctx.canvas;
    ctx.clearRect(0, 0, width, height);

    const accent = cssVar("--accent");
    const muted2 = cssVar("--muted-2");
    const hasSelection = mapState.selectedCluster !== null;

    for (const p of mapState.points) {
      const isNoise = p.cluster === null || p.cluster === undefined;
      const isSelected = hasSelection && p.cluster === mapState.selectedCluster;
      const colorVar = !isNoise ? clusterColorVar(p.cluster) : null;

      let radius = 2.2;
      let fillStyle = null;
      let strokeStyle = null;

      if (hasSelection) {
        // cluster를 선택한 동안에는 "선택됐다/아니다" 두 상태만 보여준다 — 이때는 상위 5개
        // 색도 잠시 접어둔다(선택 신호가 다른 신호와 섞이지 않도록).
        if (isSelected) {
          radius = 3.2;
          fillStyle = colorVar ? cssVar(colorVar) : accent;
        } else {
          radius = 2;
          fillStyle = hexToRgba(muted2, 0.12);
        }
      } else if (isNoise) {
        // 기본 상태에서 noise는 채우지 않고 테두리만 그려 cluster 점들과 구분한다.
        strokeStyle = hexToRgba(muted2, 0.45);
      } else if (colorVar) {
        // 상위 5개 cluster만 구분 가능한 색으로 — 152개 전부에 색을 주지 않는다.
        radius = 2.6;
        fillStyle = hexToRgba(cssVar(colorVar), 0.8);
      } else {
        // 나머지 147개 cluster는 전부 같은 muted 톤 하나로 뭉뚱그린다.
        fillStyle = hexToRgba(muted2, 0.5);
      }

      ctx.beginPath();
      ctx.arc(p.px, p.py, radius, 0, Math.PI * 2);
      if (fillStyle) {
        ctx.fillStyle = fillStyle;
        ctx.fill();
      }
      if (strokeStyle) {
        ctx.strokeStyle = strokeStyle;
        ctx.lineWidth = 1;
        ctx.stroke();
      }
    }

    // hover 중인 점은 항상 맨 위에 살짝 더 크게 그려서 눈에 띄게 한다.
    if (mapState.hoverIndex >= 0) {
      const p = mapState.points[mapState.hoverIndex];
      const isNoise = p.cluster === null || p.cluster === undefined;
      const colorVar = !isNoise ? clusterColorVar(p.cluster) : null;
      ctx.beginPath();
      ctx.arc(p.px, p.py, 4, 0, Math.PI * 2);
      ctx.fillStyle = colorVar ? cssVar(colorVar) : accent;
      ctx.fill();
    }
  }

  function hexToRgba(hex, alpha) {
    const h = hex.replace("#", "");
    if (h.length !== 6) return hex;
    const r = parseInt(h.slice(0, 2), 16);
    const g = parseInt(h.slice(2, 4), 16);
    const b = parseInt(h.slice(4, 6), 16);
    return `rgba(${r}, ${g}, ${b}, ${alpha})`;
  }

  function findNearestPoint(mouseX, mouseY) {
    let nearestIdx = -1;
    let nearestDist = 6; // 픽셀 판정 반경
    for (let i = 0; i < mapState.points.length; i++) {
      const p = mapState.points[i];
      const dx = p.px - mouseX;
      const dy = p.py - mouseY;
      const dist = Math.sqrt(dx * dx + dy * dy);
      if (dist < nearestDist) {
        nearestDist = dist;
        nearestIdx = i;
      }
    }
    return nearestIdx;
  }

  function canvasMousePos(e) {
    const rect = canvas.getBoundingClientRect();
    const scaleX = canvas.width / rect.width;
    const scaleY = canvas.height / rect.height;
    return { x: (e.clientX - rect.left) * scaleX, y: (e.clientY - rect.top) * scaleY };
  }

  function onCanvasHover(e, ctx) {
    const { x, y } = canvasMousePos(e);
    const idx = findNearestPoint(x, y);
    if (idx === mapState.hoverIndex) return;
    mapState.hoverIndex = idx;
    drawMap(ctx);

    if (idx < 0 || !tooltip) {
      if (tooltip) tooltip.style.display = "none";
      return;
    }
    const p = mapState.points[idx];
    const clusterName = (window.INTEREST_MAP_CLUSTER_NAMES || {})[p.cluster];
    const clusterLabel = p.cluster === null || p.cluster === undefined
      ? "Noise / 미분류"
      : (clusterName ? `${clusterName} · #${p.cluster}` : `Cluster #${p.cluster}`);
    tooltip.innerHTML = `${escapeHtml(p.text)}<span class="cluster-label">${clusterLabel}</span><span class="hint-label">클릭하면 원문으로 이동</span>`;
    tooltip.style.display = "block";
    const wrapRect = canvas.parentElement.getBoundingClientRect();
    tooltip.style.left = Math.max(0, Math.min(e.clientX - wrapRect.left + 12, wrapRect.width - 270)) + "px";
    tooltip.style.top = e.clientY - wrapRect.top + 12 + "px";
  }

  function onCanvasClick(e) {
    const { x, y } = canvasMousePos(e);
    const idx = findNearestPoint(x, y);
    if (idx < 0) return;
    const p = mapState.points[idx];
    window.open(p.tweetUrl, "_blank", "noopener");
  }

  function highlightMapCluster(number) {
    mapState.selectedCluster = Number(number);
    const ctx = canvas ? canvas.getContext("2d") : null;
    if (ctx) drawMap(ctx);
    if (clearSelectionBtn) clearSelectionBtn.style.display = "";
  }

  function clearMapHighlight() {
    mapState.selectedCluster = null;
    const ctx = canvas ? canvas.getContext("2d") : null;
    if (ctx) drawMap(ctx);
    if (clearSelectionBtn) clearSelectionBtn.style.display = "none";
  }

  // ===== 주요 관심사 카드 / 전체 cluster 목록 / map 강조 / 선택한 관심사 상세를 하나로 연결 =====

  function markActiveCluster(number) {
    document.querySelectorAll("[data-cluster].active").forEach((el) => el.classList.remove("active"));
    document.querySelectorAll(`[data-cluster="${number}"]`).forEach((el) => el.classList.add("active"));
  }

  function clearActiveCluster() {
    document.querySelectorAll("[data-cluster].active").forEach((el) => el.classList.remove("active"));
  }

  // cluster 이름 인라인 수정 — clustering 결과(구성/번호)는 안 건드리고
  // cluster-labels-1415.json의 name 필드만 고친다. 저장 성공 시 지금 보이는 헤딩뿐 아니라
  // 주요 관심사 카드/전체 목록의 같은 cluster 항목, map hover 툴팁용 이름 맵까지 같이 갱신한다
  // (페이지 새로고침 없이 화면 전체가 일관되게 보이도록). 다만 지도 위 legend(상위 5개 표시줄)는
  // "이름 · 건수"가 하나의 텍스트로 합쳐져 있어 여기서는 갱신하지 않는다 — 다음 새로고침 때 반영된다.
  function applyClusterNameEverywhere(number, newName) {
    document.querySelectorAll(`[data-cluster="${number}"]`).forEach((el) => {
      el.dataset.clusterName = newName;
      const titleEl = el.querySelector(".interest-map-featured-title")
        || el.querySelector(".interest-map-cluster-item-head > span:first-child");
      if (titleEl) titleEl.textContent = newName;
    });
    if (window.INTEREST_MAP_CLUSTER_NAMES) {
      window.INTEREST_MAP_CLUSTER_NAMES[number] = newName;
    }
  }

  function buildSelectedHeading(number, name, sizeText) {
    const heading = document.createElement("div");
    heading.className = "interest-map-detail-heading";

    function renderView(currentName) {
      heading.innerHTML = "";
      const title = document.createElement("span");
      title.className = "interest-map-detail-title";
      title.textContent = currentName
        ? `${currentName} (#${number}) · ${sizeText}`
        : `Cluster #${number} · ${sizeText}`;
      heading.appendChild(title);

      const editBtn = document.createElement("button");
      editBtn.type = "button";
      editBtn.className = "interest-map-name-edit-btn";
      editBtn.textContent = "이름 수정";
      editBtn.addEventListener("click", () => renderEdit(currentName));
      heading.appendChild(editBtn);
    }

    function renderEdit(currentName) {
      heading.innerHTML = "";
      const row = document.createElement("div");
      row.className = "interest-map-name-edit-row";

      const input = document.createElement("input");
      input.type = "text";
      input.className = "interest-map-name-input";
      input.value = currentName || "";
      input.maxLength = 40;

      const saveBtn = document.createElement("button");
      saveBtn.type = "button";
      saveBtn.className = "tag-save-btn";
      saveBtn.textContent = "저장";

      const cancelBtn = document.createElement("button");
      cancelBtn.type = "button";
      cancelBtn.className = "interest-map-name-edit-btn";
      cancelBtn.textContent = "취소";

      saveBtn.addEventListener("click", async () => {
        const newName = input.value.trim();
        if (!newName) {
          showToast("이름을 입력하세요.", "error");
          return;
        }
        saveBtn.disabled = true;
        try {
          const res = await fetch(`/api/interest-map/clusters/${encodeURIComponent(number)}/name`, {
            method: "PATCH",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ name: newName }),
          });
          if (!res.ok) throw new Error("이름 저장 실패: " + res.status);
          const data = await res.json();
          applyClusterNameEverywhere(number, data.name);
          renderView(data.name);
          showToast("이름을 저장했습니다.");
        } catch (err) {
          console.error(err);
          showToast("이름 저장에 실패했습니다.", "error");
        } finally {
          saveBtn.disabled = false;
        }
      });
      cancelBtn.addEventListener("click", () => renderView(currentName));

      row.appendChild(input);
      row.appendChild(saveBtn);
      row.appendChild(cancelBtn);
      heading.appendChild(row);
      input.focus();
    }

    renderView(name);
    return heading;
  }

  async function selectCluster(number, triggerEl) {
    markActiveCluster(number);
    // 클릭한 카드/리스트 항목에 이미 실려있는 data 속성을 그대로 쓴다 — 렌더된 텍스트를
    // 다시 파싱하지 않는다(표시 형식이 바뀌어도 여기가 깨지지 않도록).
    const clusterName = triggerEl && triggerEl.dataset.clusterName;
    const clusterSize = triggerEl && triggerEl.dataset.clusterSize;

    // "선택한 관심사" 섹션 맨 위로 스크롤한다 — 이후 다시 스크롤하지 않는다. 예전엔 목록 로딩이
    // 끝난 뒤 클릭했던 항목(전체 목록 안, 화면 아래쪽)으로 다시 스크롤을 시켜서 결과적으로
    // "맨 아래 글"이 보이는 문제가 있었다.
    if (selectedHeading) selectedHeading.scrollIntoView({ behavior: "smooth", block: "start" });
    selectedSection.innerHTML = '<div class="empty">불러오는 중…</div>';
    highlightMapCluster(number);

    try {
      const res = await fetch(`/api/interest-map/clusters/${encodeURIComponent(number)}`);
      if (!res.ok) throw new Error("불러오기 실패: " + res.status);
      const bookmarks = await res.json();

      const sizeText = (clusterSize || bookmarks.length) + "건";
      const heading = buildSelectedHeading(number, clusterName, sizeText);

      selectedSection.innerHTML = "";
      selectedSection.appendChild(heading);
      const body = document.createElement("div");
      selectedSection.appendChild(body);
      renderMembers(body, bookmarks);
    } catch (err) {
      console.error(err);
      selectedSection.innerHTML = '<div class="empty">불러오는 중 오류가 발생했습니다.</div>';
    }
  }

  function deselectCluster() {
    clearActiveCluster();
    selectedSection.innerHTML = '<div class="empty">지도에서 점을 살펴보거나, 위 카드나 아래 전체 목록에서 관심사를 선택하면 '
      + '관련 북마크가 여기 나타납니다.</div>';
    clearMapHighlight();
  }

  function bindClusterTriggers(root) {
    if (!root) return;
    root.addEventListener("click", (e) => {
      const btn = e.target.closest("[data-cluster]");
      if (!btn) return;
      if (btn.classList.contains("active")) {
        deselectCluster(); // 이미 선택된 걸 다시 클릭하면 선택 해제(전체 다시 표시)
        return;
      }
      selectCluster(btn.dataset.cluster, btn);
    });
  }

  bindClusterTriggers(featuredWrap);
  bindClusterTriggers(allList);

  if (noiseToggle && noiseList) {
    let loaded = false;
    noiseToggle.addEventListener("click", async () => {
      const isHidden = noiseList.style.display === "none";
      noiseList.style.display = isHidden ? "" : "none";
      if (!isHidden || loaded) return;

      noiseList.innerHTML = '<div class="empty">불러오는 중…</div>';
      try {
        const res = await fetch("/api/interest-map/noise");
        if (!res.ok) throw new Error("불러오기 실패: " + res.status);
        const bookmarks = await res.json();
        renderMembers(noiseList, bookmarks);
        loaded = true;
      } catch (err) {
        console.error(err);
        noiseList.innerHTML = '<div class="empty">불러오는 중 오류가 발생했습니다.</div>';
      }
    });
  }

  initMap();
})();
