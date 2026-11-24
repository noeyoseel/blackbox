// 서비스 워커. 수집된 트윗을 로컬 백엔드(Spring Boot)로 전송한다.
// 백엔드가 꺼져 있을 경우를 대비해 chrome.storage.local에도 백업 저장한다.
const BACKEND_URL = "http://localhost:8080/api/bookmarks/batch";
const SYNC_CHECK_URL = "http://localhost:8080/api/bookmarks/sync-check";
const TAGGING_RUN_URL = "http://localhost:8080/api/tagging/run";

chrome.runtime.onMessage.addListener((message) => {
  if (message.type === "TWEETS_COLLECTED") {
    const tweets = message.tweets;

    fetch(BACKEND_URL, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ tweets }),
    })
      .then((res) => res.json())
      .then((result) => {
        console.log(
          `[bookmark-drawer] 백엔드 전송 완료 - 수신 ${result.received} / 저장 ${result.saved} / 중복 ${result.duplicated}`
        );
      })
      .catch((err) => {
        console.warn("[bookmark-drawer] 백엔드 전송 실패, 로컬에만 백업합니다", err);
        backupToLocalStorage(tweets);
      });
    return;
  }

  if (message.type === "COLLECTION_FINISHED") {
    // 수집 세션(자동 종료/수동 중지 모두)이 끝날 때마다 미분류 북마크 전체에 AI 태깅을
    // 걸어본다. 라이선스 미활성화 시 402가 오는데, 그 경우도 그냥 콘솔 경고로 넘어간다
    // (원래도 /api/tagging/run은 수동 트리거였고, 이 메시지가 그 트리거를 대신할 뿐이다).
    fetch(TAGGING_RUN_URL, { method: "POST" })
      .then((res) => {
        if (!res.ok) throw new Error(`status ${res.status}`);
        return res.json();
      })
      .then((result) => {
        console.log(
          `[bookmark-drawer] 자동 태깅 완료 - 대상 ${result.attempted} / 성공 ${result.succeeded} / 실패 ${result.failed}`
        );
      })
      .catch((err) => {
        console.warn("[bookmark-drawer] 자동 태깅 실패 (라이선스 미활성화일 수 있음)", err);
      });
    return;
  }

  if (message.type === "SYNC_CHECK") {
    fetch(SYNC_CHECK_URL, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        tweetIds: message.tweetIds,
        minOrder: message.minOrder,
        maxOrder: message.maxOrder,
      }),
    })
      .then((res) => res.json())
      .then((result) => {
        if (result.archivedCount > 0) {
          console.log(
            `[bookmark-drawer] 삭제 감지 - ${result.archivedCount}건을 보관함으로 이동했습니다`
          );
        }
      })
      .catch((err) => {
        console.warn("[bookmark-drawer] 삭제 동기화 확인 실패", err);
      });
  }
});

function backupToLocalStorage(tweets) {
  chrome.storage.local.get({ tweets: [] }, ({ tweets: existing }) => {
    const existingIds = new Set(existing.map((t) => t.id));
    const merged = existing.concat(tweets.filter((t) => !existingIds.has(t.id)));
    chrome.storage.local.set({ tweets: merged });
  });
}
