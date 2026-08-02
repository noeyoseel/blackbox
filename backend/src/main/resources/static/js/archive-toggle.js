// 카드 하단의 보관/복원 버튼. 누르면 API 호출 후, 목록 화면에서는 카드가
// 더 이상 보이지 않을 대상이므로 DOM에서 제거하고(보관함에서는 반대로 복원 시 제거).
function bindArchiveButtons(root) {
  root.querySelectorAll(".archive-toggle").forEach((btn) => {
    const row = btn.closest(".tags-row");
    const tweetId = row.dataset.tweetId;

    btn.addEventListener("click", async () => {
      const archived = btn.dataset.archived === "true";
      const nextArchived = !archived;

      btn.disabled = true;
      try {
        const res = await fetch(
          `/api/bookmarks/${encodeURIComponent(tweetId)}/archive`,
          {
            method: "PATCH",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ archived: nextArchived }),
          }
        );
        if (!res.ok) throw new Error("보관 처리 실패: " + res.status);

        const card = btn.closest(".bookmark-item");
        if (card) card.remove();
      } catch (err) {
        console.error(err);
        showToast("보관 처리에 실패했습니다.", "error");
        btn.disabled = false;
      }
    });
  });
}

document.addEventListener("DOMContentLoaded", () => bindArchiveButtons(document));
