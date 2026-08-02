// 각 북마크 카드의 태그를 수동으로 편집/저장하는 로직.
// 무한 스크롤로 새 카드가 추가될 때도 재사용할 수 있도록 함수로 분리.
function bindTagEditors(root) {
  root.querySelectorAll(".tags-row").forEach((row) => {
    const tweetId = row.dataset.tweetId;
    const tagList = row.querySelector(".tag-list");
    const toggleBtn = row.querySelector(".tag-edit-toggle");
    const editRow = row.querySelector(".tag-edit-row");
    const input = row.querySelector(".tag-edit-input");
    const saveBtn = row.querySelector(".tag-save-btn");

    toggleBtn.addEventListener("click", () => {
      const currentTags = Array.from(tagList.querySelectorAll(".tag-pill")).map(
        (el) => el.textContent
      );
      input.value = currentTags.join(", ");
      const showing = editRow.style.display !== "none";
      editRow.style.display = showing ? "none" : "flex";
      if (!showing) input.focus();
    });

    saveBtn.addEventListener("click", async () => {
      const tags = input.value
        .split(",")
        .map((t) => t.trim())
        .filter(Boolean);

      saveBtn.disabled = true;
      saveBtn.textContent = "저장 중...";
      try {
        const res = await fetch(
          `/api/bookmarks/${encodeURIComponent(tweetId)}/tags`,
          {
            method: "PATCH",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ tags }),
          }
        );
        if (!res.ok) throw new Error("저장 실패: " + res.status);
        const data = await res.json();

        // .tag-list에는 태그 필 외에 폴더 배지도 같이 들어있어서, 태그 필만 갈아끼운다.
        tagList.querySelectorAll(".tag-pill").forEach((el) => el.remove());
        const folderBadge = tagList.querySelector(".folder-badge");
        data.tags.forEach((tag) => {
          const link = document.createElement("a");
          link.className = "tag-pill";
          link.textContent = tag;
          link.href = `/tags/${encodeURIComponent(tag)}`;
          tagList.insertBefore(link, folderBadge);
        });
        editRow.style.display = "none";
      } catch (err) {
        console.error(err);
        showToast("태그 저장에 실패했습니다.", "error");
      } finally {
        saveBtn.disabled = false;
        saveBtn.textContent = "저장";
      }
    });
  });
}

document.addEventListener("DOMContentLoaded", () => bindTagEditors(document));
