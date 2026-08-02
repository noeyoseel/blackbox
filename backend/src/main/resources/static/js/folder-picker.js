// 카드 하단의 폴더 지정 UI. 드롭다운에서 폴더를 고르면 즉시 저장되고,
// "+ 새 폴더"를 고르면 이름을 물어본 뒤 새로 만들어서 바로 지정한다.
function bindFolderPickers(root) {
  root.querySelectorAll(".tags-row").forEach((row) => {
    const tweetId = row.dataset.tweetId;
    const toggleBtn = row.querySelector(".folder-toggle");
    const pickerRow = row.querySelector(".folder-picker-row");
    const select = row.querySelector(".folder-select");
    if (!toggleBtn || !pickerRow || !select) return;

    let loaded = false;

    async function loadOptions() {
      select.innerHTML = "";
      const noneOpt = document.createElement("option");
      noneOpt.value = "";
      noneOpt.textContent = "폴더 없음";
      select.appendChild(noneOpt);

      try {
        const res = await fetch("/api/folders");
        if (!res.ok) throw new Error("폴더 목록 조회 실패: " + res.status);
        const folders = await res.json();
        folders.forEach((f) => {
          const opt = document.createElement("option");
          opt.value = String(f.id);
          opt.textContent = f.name;
          select.appendChild(opt);
        });
      } catch (err) {
        console.error(err);
      }

      const newOpt = document.createElement("option");
      newOpt.value = "__new__";
      newOpt.textContent = "+ 새 폴더";
      select.appendChild(newOpt);

      select.value = row.dataset.folderId || "";
      loaded = true;
    }

    toggleBtn.addEventListener("click", async () => {
      const showing = pickerRow.style.display === "flex";
      if (showing) {
        pickerRow.style.display = "none";
        return;
      }
      if (!loaded) await loadOptions();
      pickerRow.style.display = "flex";
    });

    select.addEventListener("change", async () => {
      let folderId = select.value;

      if (folderId === "__new__") {
        const name = await showPrompt("새 폴더 이름을 입력하세요");
        if (!name || !name.trim()) {
          select.value = row.dataset.folderId || "";
          return;
        }
        try {
          const createRes = await fetch("/api/folders", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ name: name.trim() }),
          });
          if (!createRes.ok) throw new Error("폴더 생성 실패: " + createRes.status);
          const folder = await createRes.json();
          folderId = String(folder.id);
          loaded = false;
        } catch (err) {
          console.error(err);
          showToast("폴더 생성에 실패했습니다.", "error");
          select.value = row.dataset.folderId || "";
          return;
        }
      }

      try {
        const res = await fetch(
          `/api/bookmarks/${encodeURIComponent(tweetId)}/folder`,
          {
            method: "PATCH",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ folderId: folderId ? Number(folderId) : null }),
          }
        );
        if (!res.ok) throw new Error("폴더 지정 실패: " + res.status);
        row.dataset.folderId = folderId || "";
        pickerRow.style.display = "none";
        // 배지 표시가 최신 상태를 반영하도록 카드 새로고침 대신 페이지를 새로고침한다.
        location.reload();
      } catch (err) {
        console.error(err);
        showToast("폴더 지정에 실패했습니다.", "error");
      }
    });
  });
}

document.addEventListener("DOMContentLoaded", () => bindFolderPickers(document));
