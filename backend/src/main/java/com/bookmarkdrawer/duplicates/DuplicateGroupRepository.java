package com.bookmarkdrawer.duplicates;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DuplicateGroupRepository extends JpaRepository<DuplicateGroup, Long> {

    Optional<DuplicateGroup> findByGroupKey(String groupKey);

    List<DuplicateGroup> findByDismissedFalse();

    List<DuplicateGroup> findByTypeAndDismissedFalseOrderByScoreDesc(DuplicateType type);
}
