package com.bookmarkdrawer.duplicates;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

// tweetId를 평문 컬럼으로만 참조한다(JPA 연관관계 없음 — Bookmark와 같은 컨벤션).
@Entity
@Table(name = "duplicate_group")
@Getter
@NoArgsConstructor
public class DuplicateGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 10)
    private DuplicateType type;

    // 컬럼명 "signal"은 MySQL 예약어(저장 프로시저의 SIGNAL 문)라 테이블 생성이 실패한다.
    // Java 필드/getter 이름은 signal 그대로 두고 DB 컬럼명만 바꾼다.
    @Enumerated(EnumType.STRING)
    @Column(name = "match_signal", nullable = false, length = 20)
    private DuplicateSignal signal;

    @Column(name = "score", nullable = false)
    private double score;

    // 정렬된 멤버 tweetId 목록의 해시. 재스캔 때 같은 그룹을 다시 찾아내서 dismissed 상태를
    // 유지하기 위한 idempotency 키다(DuplicateDetectionService.computeGroupKey 참고).
    @Column(name = "group_key", nullable = false, unique = true, length = 64)
    private String groupKey;

    @Column(name = "dismissed", nullable = false)
    private boolean dismissed = false;

    // duplicate_group과 duplicate_group_member가 둘 다 이번에 처음 생성되는 테이블이라,
    // 외래키 제약을 그대로 두면 Hibernate가 자식 테이블을 만들 때 부모 테이블이 아직 완전히
    // 준비되기 전이라 "Failed to open the referenced table" 오류가 난다. FK 없이 인덱스만
    // 둔다(다른 테이블 간 관계를 안 쓰는 기존 컨벤션과도 맞음).
    @ElementCollection
    @CollectionTable(name = "duplicate_group_member",
            joinColumns = @JoinColumn(name = "group_id"),
            foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT),
            indexes = @Index(name = "idx_duplicate_group_member_group_id", columnList = "group_id"))
    @Column(name = "tweet_id")
    private Set<String> memberTweetIds = new HashSet<>();

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public DuplicateGroup(DuplicateType type, DuplicateSignal signal, double score,
                           String groupKey, Set<String> memberTweetIds) {
        this.type = type;
        this.signal = signal;
        this.score = score;
        this.groupKey = groupKey;
        this.memberTweetIds = new HashSet<>(memberTweetIds);
        this.createdAt = LocalDateTime.now();
    }

    public void dismiss() {
        this.dismissed = true;
    }
}
