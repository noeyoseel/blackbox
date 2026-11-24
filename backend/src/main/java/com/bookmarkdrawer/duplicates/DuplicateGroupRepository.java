package com.bookmarkdrawer.duplicates;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DuplicateGroupRepository extends JpaRepository<DuplicateGroup, Long> {

    Optional<DuplicateGroup> findByGroupKey(String groupKey);

    List<DuplicateGroup> findByDismissedFalse();

    List<DuplicateGroup> findByTypeAndDismissedFalseOrderByScoreDesc(DuplicateType type);

    // 영구 삭제되는 tweetId를 멤버로 가진 그룹 전부(pending·dismissed 구분 없음). 서로 다른 북마크를
    // 동시에 지우면서 같은 그룹을 고치는 경우 충돌하지 않도록 그룹 행을 잠근다 — 뒤 요청은 앞 요청이
    // 커밋한 뒤의 그룹 상태(이미 지워졌으면 결과에서 빠짐)를 읽는다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT g FROM DuplicateGroup g WHERE :tweetId MEMBER OF g.memberTweetIds")
    List<DuplicateGroup> findByMemberTweetIdForUpdate(@Param("tweetId") String tweetId);
}
