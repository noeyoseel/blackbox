// 중복/거의 중복 후보 그룹 뷰 — /organize의 "동일 내용 후보"/"거의 중복 후보" 탭 전용.
// 다른 필터에서는 관련 요소가 없어 아래 가드에서 바로 종료된다.
(() => {
  const rescanBtn = document.getElementById("duplicate-rescan-btn");
  const groupsEl = document.getElementById("duplicate-groups");
  if (!rescanBtn && !groupsEl) return;

  if (rescanBtn) {
    rescanBtn.addEventListener("click", async () => {
      rescanBtn.disabled = true;
      rescanBtn.textContent = "찾는 중...";
      try {
        const res = await fetch("/api/duplicates/scan", { method: "POST" });
        if (!res.ok) throw new Error("스캔 실패: " + res.status);
        location.reload();
      } catch (err) {
        console.error(err);
        showToast("중복 스캔에 실패했습니다.", "error");
        rescanBtn.disabled = false;
        rescanBtn.textContent = "다시 찾기";
      }
    });
  }

  if (!groupsEl) return;

  groupsEl.querySelectorAll(".duplicate-group-block").forEach((block) => {
    const groupId = block.dataset.groupId;
    const archiveBtn = block.querySelector(".duplicate-archive-btn");
    const dismissBtn = block.querySelector(".duplicate-dismiss-btn");

    async function resolve(body) {
      const res = await fetch(`/api/duplicates/groups/${groupId}/resolve`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body),
      });
      if (!res.ok) throw new Error("처리 실패: " + res.status);
      return res.json();
    }

    archiveBtn.addEventListener("click", async () => {
      const tweetIds = Array.from(
        block.querySelectorAll(".duplicate-member-checkbox:checked")
      ).map((cb) => cb.value);
      if (tweetIds.length === 0) {
        showToast("보관할 항목을 선택하세요.", "error");
        return;
      }
      archiveBtn.disabled = true;
      try {
        await resolve({ action: "archive_selected", tweetIds });
        showToast(`${tweetIds.length}건을 보관했습니다.`);
        block.remove();
      } catch (err) {
        console.error(err);
        showToast("처리에 실패했습니다.", "error");
        archiveBtn.disabled = false;
      }
    });

    dismissBtn.addEventListener("click", async () => {
      dismissBtn.disabled = true;
      try {
        await resolve({ action: "dismiss" });
        showToast("이 그룹을 유지하기로 했습니다.");
        block.remove();
      } catch (err) {
        console.error(err);
        showToast("처리에 실패했습니다.", "error");
        dismissBtn.disabled = false;
      }
    });
  });
})();
