package com.bookmarkdrawer.interestmap;

import java.util.List;

// number는 실험 결과 파일의 cluster 번호를 그대로 쓴다. name은 별도로 생성해 둔
// .embedding-cache/cluster-labels-1415.json을 읽기만 한 값(clustering 결과 자체는 안 바꿈) —
// 파일이 없으면 null이고, 화면에서는 그때 "Cluster #N"으로 대체 표시한다.
public record ClusterSummary(int number, int size, String name, List<BookmarkPreview> representatives) {
}
