// 보관함 전용 "영구 삭제" 버튼. 실제 DB에서 완전히 지우므로 확인 모달을 거친다.
document.addEventListener("DOMContentLoaded", () => {
  // 마지막 카드까지 지워지면 서버 렌더링 때와 같은 빈 화면 안내로 바꾼다(archive.html의 .empty와 동일).
  function showEmptyStateIfNoCards() {
    const list = document.querySelector(".bookmark-list.archive-view");
    if (!list || list.querySelector(".bookmark-item")) return;
    const empty = document.createElement("div");
    empty.className = "empty";
    empty.textContent = "보관된 북마크가 없습니다.";
    list.replaceWith(empty);
  }

  function removeCard(btn) {
    const card = btn.closest(".bookmark-item");
    if (card) card.remove();
    showEmptyStateIfNoCards();
  }

  document.querySelectorAll(".permanent-delete-btn").forEach((btn) => {
    const row = btn.closest(".tags-row");
    const tweetId = row.dataset.tweetId;

    btn.addEventListener("click", async () => {
      const ok = await showConfirm(
        "이 북마크를 영구 삭제할까요? 이 작업은 되돌릴 수 없습니다."
      );
      if (!ok) return;

      btn.disabled = true;
      try {
        const res = await fetch(`/api/bookmarks/${encodeURIComponent(tweetId)}`, {
          method: "DELETE",
        });
        if (res.status === 404) {
          // 다른 탭 등에서 이미 지워졌다 — 서버에 없으므로 화면에서도 정리한다.
          showToast("이미 삭제된 북마크입니다. 목록에서 정리했습니다.");
          removeCard(btn);
          return;
        }
        if (res.status === 409) {
          // 그 사이 보관이 해제됐다 — 지우지 않았으므로 카드를 남기고 새로고침을 안내한다.
          showToast("보관 상태가 아니어서 영구 삭제하지 않았습니다. 새로고침 후 확인해 주세요.", "error");
          btn.disabled = false;
          return;
        }
        if (!res.ok) throw new Error("영구 삭제 실패: " + res.status);
        showToast("영구 삭제했습니다.");
        removeCard(btn);
      } catch (err) {
        console.error(err);
        showToast("영구 삭제에 실패했습니다.", "error");
        btn.disabled = false;
      }
    });
  });
});
