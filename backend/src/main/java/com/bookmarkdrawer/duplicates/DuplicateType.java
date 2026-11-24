package com.bookmarkdrawer.duplicates;

// 화면 표기는 "완전 중복"이 아니라 getLabel()의 완곡한 표현을 쓴다 — 정규화 과정을 거치기
// 때문에 의미적으로 100% 동일함을 보장하지는 않는다. 내부적으로는 EXACT/NEAR 구분을 유지한다.
public enum DuplicateType {
    EXACT("동일 내용 후보"),
    NEAR("거의 중복 후보");

    private final String label;

    DuplicateType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
