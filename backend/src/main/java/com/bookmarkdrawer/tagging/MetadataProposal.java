package com.bookmarkdrawer.tagging;

import com.bookmarkdrawer.bookmark.ContentType;

// AI가 만든 제안일 뿐 아직 확정된 값이 아니다. Bookmark에는 반영하지 않고 그대로 응답에 담아
// 돌려주며, 실제 반영은 사용자가 검토한 뒤 기존 단건/배치 메타데이터 수정 API를 호출해서 한다.
public record MetadataProposal(String tweetId, ContentType contentType, String place) {
}
