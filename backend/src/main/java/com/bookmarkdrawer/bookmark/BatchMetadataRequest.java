package com.bookmarkdrawer.bookmark;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

// contentType/place는 둘 다 선택적이다: 요청에 없으면(null) 그 필드는 건드리지 않는다.
// place만은 예외로, 빈 문자열("")을 보내면 명시적으로 비우라는 뜻으로 취급한다
// (BookmarkService.batchUpdateMetadata 참고) — "유형만 바꾸고 싶다"는 요청이 다른
// 북마크들의 기존 place를 실수로 지워버리지 않게 하기 위함.
public record BatchMetadataRequest(
        @NotEmpty List<String> tweetIds,
        ContentType contentType,
        String place
) {
}
