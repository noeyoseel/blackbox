package com.bookmarkdrawer.duplicates;

// 그룹을 묶은 근거. QUOTE_LINK는 의도적으로 점수가 가장 낮다(DuplicateDetectionService 참고) —
// 원본과 인용 글 모두 저장할 가치가 있을 수 있어서, 강한 중복 판정 기준으로 쓰지 않는다.
public enum DuplicateSignal {
    TEXT_EXACT("본문 일치", "정규화한 본문이 동일합니다."),
    TEXT_SIMILAR("본문 유사", "본문이 서로 매우 비슷합니다."),
    MEDIA_MATCH("동일 미디어", "같은 이미지/영상을 포함하고 있습니다."),
    QUOTE_LINK("인용 관계", "한쪽이 다른 쪽을 인용한 글입니다 — 원본과 인용 모두 저장할 가치가 있을 수 있습니다.");

    private final String label;
    private final String description;

    DuplicateSignal(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public String getLabel() {
        return label;
    }

    public String getDescription() {
        return description;
    }
}
