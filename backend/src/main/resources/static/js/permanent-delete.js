// 보관함 전용 "영구 삭제" 버튼. 실제 DB에서 완전히 지우므로 확인 모달을 거친다.
document.addEventListener("DOMContentLoaded", () => {
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
        if (!res.ok) throw new Error("영구 삭제 실패: " + res.status);
        showToast("영구 삭제했습니다.");
        const card = btn.closest(".bookmark-item");
        if (card) card.remove();
      } catch (err) {
        console.error(err);
        showToast("영구 삭제에 실패했습니다.", "error");
        btn.disabled = false;
      }
    });
  });
});
