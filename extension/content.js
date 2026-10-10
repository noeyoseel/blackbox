// ISOLATED world content script. 플로팅 버튼 UI + 자동 스크롤 트리거 담당.
(() => {
  const seenTweetIds = new Set();
  let collecting = false;
  let noNewDataStreak = 0;
  const MAX_NO_NEW_DATA_STREAK = 8;
  // 페이지 로드 후 처음 잡히는 응답은 "가장 최근 구간"이라 위쪽 경계가 없다(=지금 이 시점
  // 기준 최상단). 그 이후 응답들은 스크롤을 타고 내려가며 이어지는 구간이라 위/아래 경계가 다 있다.
  let isFirstBatch = true;

  // TweetWithVisibilityResults 래퍼(민감 콘텐츠 등)는 실제 트윗 필드를
  // result.tweet 아래에 담아서 내려준다. 항상 이 함수를 거쳐서 접근한다.
  function unwrapTweet(result) {
    return result?.tweet ?? result;
  }

  // 트윗 본문을 최대한 잘리지 않은 형태로 복원한다.
  // - 280자 넘는 장문 트윗: legacy.full_text가 잘려있고 전체 본문은
  //   note_tweet.note_tweet_results.result.text에 따로 들어있다.
  // - 리트윗: legacy.full_text가 "RT @user: ..." 형태로 140자에서 잘려있고,
  //   전체 본문은 retweeted_status_result 안의 원본 트윗에 들어있다.
  function resolveText(rawResult) {
    const result = unwrapTweet(rawResult);
    const legacy = result?.legacy;
    if (!legacy) return "";

    const retweeted = unwrapTweet(legacy.retweeted_status_result?.result);
    if (retweeted?.legacy) {
      return "RT " + resolveText(retweeted);
    }

    const noteText = result?.note_tweet?.note_tweet_results?.result?.text;
    return noteText || legacy.full_text || "";
  }

  // 트윗에 첨부된 이미지/영상 썸네일 URL을 순서대로 가져온다.
  // 인용 트윗을 달았을 때, 인용된 트윗의 이미지가 원본 트윗 쪽 extended_entities에도
  // 그대로 들어있는 경우가 있어서 — 인용된 트윗의 media_key와 겹치는 항목은 제외한다
  // (그래야 인용 이미지가 본문 이미지처럼 보이는 문제가 안 생긴다).
  function resolveMedia(rawResult) {
    const result = unwrapTweet(rawResult);
    const legacy = result?.legacy;
    const mediaList =
      legacy?.extended_entities?.media ?? legacy?.entities?.media ?? [];

    const quotedRaw = result?.quoted_status_result?.result;
    const quotedLegacy = unwrapTweet(quotedRaw)?.legacy;
    const quotedMediaKeys = new Set(
      (quotedLegacy?.extended_entities?.media ?? []).map((m) => m.media_key)
    );

    return mediaList
      .filter((m) => !quotedMediaKeys.has(m.media_key))
      .map((m) => ({
        url: m.media_url_https ?? null,
        type: m.type ?? "photo",
      }))
      .filter((m) => m.url);
  }

  // 트위터 인용 카드처럼 렌더링하기 위해 본문뿐 아니라 인용된 트윗의
  // 작성자 이름/아이디/프로필 사진까지 함께 가져온다.
  function resolveQuoted(rawResult) {
    const result = unwrapTweet(rawResult);
    const quotedRaw = result?.quoted_status_result?.result;
    if (!quotedRaw) return null;
    const quoted = unwrapTweet(quotedRaw);
    const legacy = quoted?.legacy;
    if (!legacy) return null;
    const user = quoted?.core?.user_results?.result;
    return {
      text: resolveText(quotedRaw),
      tweetId: quoted.rest_id ?? null,
      authorName: user?.core?.name ?? null,
      authorHandle: user?.core?.screen_name ?? null,
      authorAvatar: user?.avatar?.image_url ?? null,
    };
  }

  function extractTweetsFromResponse(data) {
    const tweets = [];
    try {
      const instructions =
        data?.data?.bookmark_timeline_v2?.timeline?.instructions ?? [];
      for (const instruction of instructions) {
        const entries = instruction.entries ?? [];
        for (const entry of entries) {
          const rawResult = entry.content?.itemContent?.tweet_results?.result;
          if (!rawResult) continue;
          const result = unwrapTweet(rawResult);
          const legacy = result?.legacy;
          const restId = result?.rest_id;
          if (!restId || !legacy) continue;
          const quoted = resolveQuoted(rawResult);
          const author = result?.core?.user_results?.result;
          tweets.push({
            id: restId,
            text: resolveText(rawResult),
            quotedText: quoted?.text ?? null,
            quotedTweetId: quoted?.tweetId ?? null,
            quotedAuthorName: quoted?.authorName ?? null,
            quotedAuthorHandle: quoted?.authorHandle ?? null,
            quotedAuthorAvatar: quoted?.authorAvatar ?? null,
            createdAt: legacy.created_at,
            authorId: legacy.user_id_str,
            authorName: author?.core?.name ?? null,
            authorHandle: author?.core?.screen_name ?? null,
            authorAvatar: author?.avatar?.image_url ?? null,
            media: resolveMedia(rawResult),
            // 트위터가 응답에 내려주는 정렬 키. 값이 클수록 최근에 북마크한 글이라
            // 우리 피드도 이 순서를 그대로 보존해서 "최근 저장한 글이 맨 위" 정렬에 쓴다.
            bookmarkOrder: entry.sortIndex ?? null,
          });
        }
      }
    } catch (err) {
      console.warn("[bookmark-drawer] 파싱 중 오류", err);
    }
    return tweets;
  }

  window.addEventListener("message", (event) => {
    if (event.source !== window) return;
    const msg = event.data;
    if (!msg || msg.source !== "twitter-bookmark-drawer") return;
    if (msg.type !== "BOOKMARKS_RESPONSE") return;

    const tweets = extractTweetsFromResponse(msg.data);
    const newTweets = tweets.filter((t) => !seenTweetIds.has(t.id));
    newTweets.forEach((t) => seenTweetIds.add(t.id));

    if (newTweets.length > 0) {
      noNewDataStreak = 0;
      console.log(
        `[bookmark-drawer] 새 트윗 ${newTweets.length}건 수집 (누적 ${seenTweetIds.size}건)`,
        newTweets
      );
      chrome.runtime.sendMessage({
        type: "TWEETS_COLLECTED",
        tweets: newTweets,
      });
    } else {
      noNewDataStreak += 1;
    }

    // 이번 응답에 실제로 담겨온 구간만큼 삭제 여부를 확인한다. 이 응답 안에 없는데
    // blackbox에는 있는(같은 구간 안의) 글은 트위터에서 지워진 것으로 보고 보관 처리한다.
    // 수집 버튼과 무관하게, 북마크 탭에서 오는 모든 응답에 대해 항상 수행한다.
    const orders = tweets.map((t) => t.bookmarkOrder).filter((o) => o != null);
    if (orders.length > 0) {
      const minOrder = orders.reduce((a, b) => (BigInt(a) < BigInt(b) ? a : b));
      const maxOrder = isFirstBatch
        ? null
        : orders.reduce((a, b) => (BigInt(a) > BigInt(b) ? a : b));
      chrome.runtime.sendMessage({
        type: "SYNC_CHECK",
        tweetIds: tweets.map((t) => t.id),
        minOrder,
        maxOrder,
      });
    }
    isFirstBatch = false;
  });

  // 자동 종료든 수동 중지든, 수집 세션이 끝나는 시점은 여기 하나로 모은다 — 이 시점에
  // 백엔드로 "이번 세션 수집 끝났다"는 신호를 보내 미분류 북마크에 AI 자동 태깅을 걸 수 있게 한다.
  function stopCollecting() {
    collecting = false;
    updateButton();
    chrome.runtime.sendMessage({ type: "COLLECTION_FINISHED" });
  }

  async function autoScrollLoop() {
    while (collecting) {
      window.scrollTo(0, document.body.scrollHeight);
      await new Promise((r) => setTimeout(r, 1800));

      // scrollTo로 이미 최대치까지 스크롤한 직후라 scrollY/scrollHeight 비교로는
      // "바닥 도달"을 신뢰성 있게 판단할 수 없다 (항상 참에 가깝게 나옴).
      // 대신 실제 API 응답에서 새 트윗이 연속으로 안 들어오는지(noNewDataStreak)로만 종료를 판단한다.
      if (noNewDataStreak >= MAX_NO_NEW_DATA_STREAK) {
        console.log(
          `[bookmark-drawer] 수집 종료 (연속 무응답: ${noNewDataStreak})`
        );
        stopCollecting();
      }
    }
  }

  function updateButton() {
    const btn = document.getElementById("bookmark-drawer-btn");
    if (!btn) return;
    btn.textContent = collecting
      ? `수집 중지 (${seenTweetIds.size}건)`
      : `수집 시작 (누적 ${seenTweetIds.size}건)`;
  }

  function injectButton() {
    if (document.getElementById("bookmark-drawer-btn")) return;
    const btn = document.createElement("button");
    btn.id = "bookmark-drawer-btn";
    btn.textContent = "수집 시작";
    Object.assign(btn.style, {
      position: "fixed",
      top: "80px",
      right: "20px",
      zIndex: 9999,
      padding: "10px 18px",
      background: "#F5F2EA",
      color: "#2C2A24",
      border: "0.5px solid #E8E4D9",
      borderRadius: "4px",
      fontFamily: "'Pretendard', -apple-system, sans-serif",
      fontSize: "13px",
      fontWeight: "600",
      letterSpacing: "0.02em",
      cursor: "pointer",
      boxShadow: "0 2px 8px rgba(44,42,36,0.12)",
    });
    btn.addEventListener("click", () => {
      if (collecting) {
        console.log(`[bookmark-drawer] 수집 수동 중단 (누적 ${seenTweetIds.size}건)`);
        stopCollecting();
        return;
      }
      collecting = true;
      noNewDataStreak = 0;
      updateButton();
      autoScrollLoop();
    });
    document.body.appendChild(btn);
  }

  // document_start에서 실행되므로 메시지 리스너는 위에서 즉시 등록하되(응답을
  // 놓치지 않기 위함), document.body가 필요한 버튼 주입만 DOM 준비 후로 미룬다.
  function start() {
    injectButton();
    setInterval(updateButton, 1000);
  }
  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", start);
  } else {
    start();
  }
})();
