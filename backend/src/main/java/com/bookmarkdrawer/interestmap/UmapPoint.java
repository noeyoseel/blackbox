package com.bookmarkdrawer.interestmap;

// .embedding-cache/umap-1415.json에 미리 계산돼 저장된 2D 좌표 + cluster 배정을 그대로 읽어온
// 값이다. x/y는 이 요청에서 계산하는 게 아니라 일회성 preprocessing 결과를 읽기만 한다.
// cluster가 null이면 noise(미분류)다.
public record UmapPoint(String tweetId, double x, double y, Integer cluster, String text, String tweetUrl) {
}
