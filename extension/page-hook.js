// MAIN world에 주입되는 스크립트. 페이지 자체의 fetch를 감싸서
// 북마크 GraphQL 응답을 가로채고, window.postMessage로 content script에 전달한다.
(() => {
  const BOOKMARKS_URL_PATTERN = /\/graphql\/[^/]+\/Bookmarks/;

  const originalFetch = window.fetch;
  window.fetch = async function (...args) {
    const response = await originalFetch.apply(this, args);

    const url = typeof args[0] === "string" ? args[0] : args[0]?.url;
    if (url && url.includes("/graphql/")) {
      console.log("[bookmark-drawer][debug] fetch graphql 호출 감지:", url);
    }
    if (url && BOOKMARKS_URL_PATTERN.test(url)) {
      response
        .clone()
        .json()
        .then((data) => {
          window.postMessage(
            { source: "twitter-bookmark-drawer", type: "BOOKMARKS_RESPONSE", url, data },
            "*"
          );
        })
        .catch((err) => {
          console.warn("[bookmark-drawer] JSON 파싱 실패", err);
        });
    }

    return response;
  };

  // 트위터가 XHR로 요청을 보낼 수도 있으므로 XMLHttpRequest도 함께 후킹한다.
  const OriginalXHR = window.XMLHttpRequest;
  const originalOpen = OriginalXHR.prototype.open;
  const originalSend = OriginalXHR.prototype.send;

  OriginalXHR.prototype.open = function (method, url, ...rest) {
    this.__bookmarkDrawerUrl = url;
    return originalOpen.call(this, method, url, ...rest);
  };

  OriginalXHR.prototype.send = function (...args) {
    if (this.__bookmarkDrawerUrl && this.__bookmarkDrawerUrl.includes("/graphql/")) {
      console.log("[bookmark-drawer][debug] XHR graphql 호출 감지:", this.__bookmarkDrawerUrl);
    }
    if (this.__bookmarkDrawerUrl && BOOKMARKS_URL_PATTERN.test(this.__bookmarkDrawerUrl)) {
      this.addEventListener("load", function () {
        try {
          const data = JSON.parse(this.responseText);
          window.postMessage(
            {
              source: "twitter-bookmark-drawer",
              type: "BOOKMARKS_RESPONSE",
              url: this.__bookmarkDrawerUrl,
              data,
            },
            "*"
          );
        } catch (err) {
          console.warn("[bookmark-drawer] XHR JSON 파싱 실패", err);
        }
      });
    }
    return originalSend.apply(this, args);
  };

  console.log("[bookmark-drawer] page-hook 주입 완료, fetch + XHR 가로채기 활성화");
})();
