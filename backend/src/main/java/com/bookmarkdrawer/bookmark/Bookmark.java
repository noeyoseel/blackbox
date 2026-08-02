package com.bookmarkdrawer.bookmark;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

@Entity
@Table(name = "bookmark")
@Getter
@NoArgsConstructor
public class Bookmark {

    // 트위터 legacy created_at 형식: "Wed Oct 10 20:19:24 +0000 2018"
    private static final DateTimeFormatter TWEET_DATE_FORMAT =
            DateTimeFormatter.ofPattern("EEE MMM dd HH:mm:ss Z yyyy", Locale.ENGLISH);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter KST_DISPLAY_FORMAT =
            DateTimeFormatter.ofPattern("yyyy년 M월 d일 HH:mm", Locale.KOREAN);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tweet_id", nullable = false, unique = true, length = 32)
    private String tweetId;

    @Column(name = "text", nullable = false, columnDefinition = "TEXT")
    private String text;

    @Column(name = "quoted_text", columnDefinition = "TEXT")
    private String quotedText;

    @Column(name = "quoted_tweet_id", length = 32)
    private String quotedTweetId;

    @Column(name = "quoted_author_name", length = 128)
    private String quotedAuthorName;

    @Column(name = "quoted_author_handle", length = 64)
    private String quotedAuthorHandle;

    @Column(name = "quoted_author_avatar", length = 512)
    private String quotedAuthorAvatar;

    @Column(name = "author_id", length = 32)
    private String authorId;

    @Column(name = "author_name", length = 128)
    private String authorName;

    @Column(name = "author_handle", length = 64)
    private String authorHandle;

    @Column(name = "author_avatar", length = 512)
    private String authorAvatar;

    @Column(name = "tweet_created_at", length = 64)
    private String tweetCreatedAt;

    @Column(name = "collected_at", nullable = false)
    private LocalDateTime collectedAt;

    // 트위터 북마크 응답의 sortIndex. 클수록 최근에 북마크한 글이며,
    // 피드를 "가장 최근에 저장한 글이 맨 위" 순서로 보여주는 데 쓴다.
    @Column(name = "bookmark_order")
    private Long bookmarkOrder;

    @Column(name = "tagged", nullable = false)
    private boolean tagged = false;

    @Column(name = "archived", nullable = false)
    private boolean archived = false;

    // 글 하나는 폴더 하나에만 속한다(태그는 다중 라벨, 폴더는 단일 버킷). 실제 연관관계 대신
    // id/name을 그대로 저장해서 JSON 직렬화·지연로딩 이슈 없이 단순하게 다룬다.
    @Column(name = "folder_id")
    private Long folderId;

    @Column(name = "folder_name", length = 64)
    private String folderName;

    @ElementCollection
    @CollectionTable(name = "bookmark_tag", joinColumns = @JoinColumn(name = "bookmark_id"))
    @Column(name = "tag")
    private Set<String> tags = new HashSet<>();

    @ElementCollection
    @CollectionTable(name = "bookmark_media", joinColumns = @JoinColumn(name = "bookmark_id"))
    @OrderColumn(name = "media_order")
    private List<BookmarkMedia> media = new ArrayList<>();

    public Bookmark(String tweetId, String text, String quotedText, String quotedTweetId,
                     String quotedAuthorName, String quotedAuthorHandle, String quotedAuthorAvatar,
                     String authorId, String authorName, String authorHandle, String authorAvatar,
                     String tweetCreatedAt, Long bookmarkOrder, List<BookmarkMedia> media) {
        this.tweetId = tweetId;
        this.text = text;
        this.quotedText = quotedText;
        this.quotedTweetId = quotedTweetId;
        this.quotedAuthorName = quotedAuthorName;
        this.quotedAuthorHandle = quotedAuthorHandle;
        this.quotedAuthorAvatar = quotedAuthorAvatar;
        this.authorId = authorId;
        this.authorName = authorName;
        this.authorHandle = authorHandle;
        this.authorAvatar = authorAvatar;
        this.tweetCreatedAt = tweetCreatedAt;
        this.bookmarkOrder = bookmarkOrder;
        // Hibernate가 merge 시 이 리스트에 clear()를 호출하므로 반드시 가변 리스트여야 한다.
        this.media = media != null ? new ArrayList<>(media) : new ArrayList<>();
        this.collectedAt = LocalDateTime.now();
    }

    public void applyTags(Set<String> newTags) {
        this.tags = newTags;
        this.tagged = true;
    }

    public void setArchived(boolean archived) {
        this.archived = archived;
    }

    public void assignFolder(Long folderId, String folderName) {
        this.folderId = folderId;
        this.folderName = folderId != null ? folderName : null;
    }

    // 화면에 표시할, 한국 표준시로 변환된 작성 시각. 파싱 실패 시 원본 문자열을 그대로 보여준다.
    public String getDisplayCreatedAt() {
        if (tweetCreatedAt == null || tweetCreatedAt.isBlank()) return tweetCreatedAt;
        try {
            OffsetDateTime dateTime = OffsetDateTime.parse(tweetCreatedAt, TWEET_DATE_FORMAT);
            return dateTime.atZoneSameInstant(KST).format(KST_DISPLAY_FORMAT);
        } catch (Exception e) {
            return tweetCreatedAt;
        }
    }

    public boolean refreshTextIfLonger(String newText, String newQuotedText, String newQuotedTweetId,
                                        String newQuotedAuthorName, String newQuotedAuthorHandle,
                                        String newQuotedAuthorAvatar, Long newBookmarkOrder,
                                        String newAuthorName, String newAuthorHandle, String newAuthorAvatar,
                                        List<BookmarkMedia> newMedia) {
        boolean changed = false;
        if (newText != null && newText.length() > this.text.length()) {
            this.text = newText;
            changed = true;
        }
        if (this.bookmarkOrder == null && newBookmarkOrder != null) {
            this.bookmarkOrder = newBookmarkOrder;
            changed = true;
        }
        if (newMedia != null && !mediaUrlsEqual(this.media, newMedia)) {
            // 항상 최신 값으로 덮어쓴다 — 인용 트윗 이미지가 잘못 섞여 들어온 과거 데이터도
            // 재수집하면 고쳐진 값으로 교체되도록 하기 위함(비어있을 때만 채우면 안 고쳐짐).
            this.media = new ArrayList<>(newMedia);
            changed = true;
        }
        if (this.authorName == null && newAuthorName != null) {
            // 원문 작성자 정보(이름/핸들/아바타)가 이번에 새로 추가된 필드라 비어있는 경우 백필
            this.authorName = newAuthorName;
            this.authorHandle = newAuthorHandle;
            this.authorAvatar = newAuthorAvatar;
            changed = true;
        }
        if (newQuotedText != null && !newQuotedText.equals(this.quotedText)) {
            this.quotedText = newQuotedText;
            this.quotedTweetId = newQuotedTweetId;
            this.quotedAuthorName = newQuotedAuthorName;
            this.quotedAuthorHandle = newQuotedAuthorHandle;
            this.quotedAuthorAvatar = newQuotedAuthorAvatar;
            changed = true;
        } else if (newQuotedText != null && this.quotedAuthorName == null && newQuotedAuthorName != null) {
            // 본문은 이미 맞지만 작성자 정보(이름/핸들/아바타)가 이번에 새로 추가된 필드라 비어있는 경우 백필
            this.quotedTweetId = newQuotedTweetId;
            this.quotedAuthorName = newQuotedAuthorName;
            this.quotedAuthorHandle = newQuotedAuthorHandle;
            this.quotedAuthorAvatar = newQuotedAuthorAvatar;
            changed = true;
        }
        return changed;
    }

    private boolean mediaUrlsEqual(List<BookmarkMedia> a, List<BookmarkMedia> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!Objects.equals(a.get(i).getUrl(), b.get(i).getUrl())) return false;
        }
        return true;
    }
}
