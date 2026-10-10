package com.bookmarkdrawer.duplicates;

import com.bookmarkdrawer.bookmark.Bookmark;

import java.util.List;
import java.util.Map;
import java.util.Set;

public record DuplicateGroupView(
        Long id,
        DuplicateType type,
        DuplicateSignal signal,
        double score,
        List<Bookmark> members,
        // 체크박스 기본 선택 상태(보관 후보) — 데이터가 가장 완전한 멤버를 제외한 나머지.
        // QUOTE_LINK가 대표 신호인 그룹은 비워둔다(원본+인용 모두 유지가 정답일 수 있어서
        // 삭제를 기본으로 유도하지 않는다).
        Set<String> defaultArchiveTweetIds,
        // tweetId -> 정규화된 본문. 왜 이 멤버들이 묶였는지 사용자가 직접 눈으로 확인할 수
        // 있도록 원문이 아니라 실제 비교에 쓰인 정규화 결과를 그대로 노출한다.
        Map<String, String> normalizedTextByTweetId
) {
}
