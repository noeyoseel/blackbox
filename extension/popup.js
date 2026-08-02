chrome.storage.local.get({ tweets: [] }, ({ tweets }) => {
  document.getElementById("count").textContent = `수집된 트윗: ${tweets.length}건`;
});

document.getElementById("export").addEventListener("click", () => {
  chrome.storage.local.get({ tweets: [] }, ({ tweets }) => {
    const blob = new Blob([JSON.stringify(tweets, null, 2)], {
      type: "application/json",
    });
    const url = URL.createObjectURL(blob);
    chrome.downloads.download({
      url,
      filename: `bookmarks-${Date.now()}.json`,
    });
  });
});

document.getElementById("clear").addEventListener("click", () => {
  chrome.storage.local.set({ tweets: [] }, () => {
    document.getElementById("count").textContent = "수집된 트윗: 0건";
  });
});
