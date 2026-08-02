// 폴더 목록/상세 페이지의 "수정"/"삭제" 버튼.
document.addEventListener("DOMContentLoaded", () => {
  document.querySelectorAll(".folder-rename-btn").forEach((btn) => {
    btn.addEventListener("click", async () => {
      const id = btn.dataset.folderId;
      const currentName = btn.dataset.folderName;
      const name = await showPrompt("새 폴더 이름을 입력하세요", currentName);
      if (!name || !name.trim() || name.trim() === currentName) return;

      try {
        const res = await fetch(`/api/folders/${id}`, {
          method: "PATCH",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ name: name.trim() }),
        });
        if (!res.ok) throw new Error("폴더 수정 실패: " + res.status);
        showToast("폴더 이름을 변경했습니다.");
        location.reload();
      } catch (err) {
        console.error(err);
        showToast("폴더 수정에 실패했습니다.", "error");
      }
    });
  });

  document.querySelectorAll(".folder-delete-btn").forEach((btn) => {
    btn.addEventListener("click", async () => {
      const id = btn.dataset.folderId;
      const ok = await showConfirm(
        "이 폴더를 삭제할까요? 폴더 안의 글은 삭제되지 않고, 폴더 지정만 해제됩니다."
      );
      if (!ok) return;

      try {
        const res = await fetch(`/api/folders/${id}`, { method: "DELETE" });
        if (!res.ok) throw new Error("폴더 삭제 실패: " + res.status);
        showToast("폴더를 삭제했습니다.");
        const redirect = btn.dataset.redirect;
        setTimeout(() => {
          if (redirect) {
            location.href = redirect;
          } else {
            location.reload();
          }
        }, 400);
      } catch (err) {
        console.error(err);
        showToast("폴더 삭제에 실패했습니다.", "error");
      }
    });
  });
});
