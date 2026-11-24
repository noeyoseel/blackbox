package com.bookmarkdrawer.bookmark;

// 정리 모드 필터. 전부 archived=false를 전제로 하고, tagged 플래그가 아니라
// tags 컬렉션이 실제로 비어있는지로 "태그 없음"을 판단한다(tagged는 "태깅 플로우를
// 한 번이라도 거쳤는지"를 뜻할 뿐, 사용자가 태그를 지워서 비운 경우도 tagged=true로 남기 때문).
public enum OrganizeFilter {
    CANDIDATES,
    NO_TAG,
    NO_CONTENT_TYPE;

    public static OrganizeFilter from(String value) {
        if (value == null || value.isBlank()) return CANDIDATES;
        return switch (value) {
            case "no_tag" -> NO_TAG;
            case "no_content_type" -> NO_CONTENT_TYPE;
            default -> CANDIDATES;
        };
    }
}
