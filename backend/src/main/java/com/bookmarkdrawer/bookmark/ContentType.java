package com.bookmarkdrawer.bookmark;

// 정리 모드에서 안정적으로 필터링하기 위한 콘텐츠 형식 축. 자유 태그(주제)와 달리
// 표기가 들쭉날쭉해지지 않도록 고정된 값만 허용한다. 실제 데이터로 분류해보고 값이
// 부적절하다고 확인되면 그때 조정한다 — 지금은 이 5개 외에 별도 매핑/의존을 만들지 않는다.
public enum ContentType {
    INFO("정보"),
    PLACE("장소"),
    PRODUCT("제품"),
    RESOURCE("자료"),
    OTHER("기타");

    private final String label;

    ContentType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
