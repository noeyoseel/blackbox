package com.bookmarkdrawer.interestmap;

import java.util.List;

// Interest Map 전용 읽기 전용 미리보기 DTO. Bookmark 엔티티를 그대로 노출하지 않고
// 필요한 필드만 뽑아서 넘긴다 — 태그 수정/보관/삭제 버튼이 없는 경량 카드용이라
// bookmark-card 프래그먼트(항상 수정 버튼이 마크업에 박혀있음)를 재사용하지 않는다.
public record BookmarkPreview(String tweetId, String text, List<String> tags, String tweetUrl) {
}
