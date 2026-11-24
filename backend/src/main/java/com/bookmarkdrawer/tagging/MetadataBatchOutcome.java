package com.bookmarkdrawer.tagging;

// 전체 배치 실행 콜백 전용. proposal이 null이면 재시도까지 다 실패한 것이다.
public record MetadataBatchOutcome(String tweetId, MetadataProposal proposal, int attemptsUsed) {
}
