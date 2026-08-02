// 서비스 워커. 수집된 트윗을 로컬 백엔드(Spring Boot)로 전송한다.
// 백엔드가 꺼져 있을 경우를 대비해 chrome.storage.local에도 백업 저장한다.
const BACKEND_URL = "http://localhost:8080/api/bookmarks/batch";
const SYNC_CHECK_URL = "http://localhost:8080/api/bookmarks/sync-check";

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
