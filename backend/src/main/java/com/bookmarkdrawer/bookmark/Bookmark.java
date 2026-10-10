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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Entity
@Table(name = "bookmark", indexes = {
        // 정리 모드 필터들의 공통 ORDER BY와 기존 피드 정렬(findFeedPage류)이 함께 쓴다.
        @Index(name = "idx_bookmark_archived_order", columnList = "archived, bookmark_order, collected_at")
})
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

    // 태그(자유 주제 키워드)만으로는 "맛집/카페/디저트"처럼 표기가 들쭉날쭉해서 안정적으로
    // 필터링하기 어렵다. contentType/place는 AI가 콘텐츠에서 뽑아낼(다음 단계) 구조화된
    // 필터 축이다. 기존 북마크를 일괄로 채우지 않으므로 둘 다 nullable.
    @Enumerated(EnumType.STRING)
    @Column(name = "content_type", length = 20)
    private ContentType contentType;

    @Column(name = "place", length = 255)
    private String place;

    @ElementCollection
    @CollectionTable(name = "bookmark_tag", joinColumns = @JoinColumn(name = "bookmark_id"),
            indexes = @Index(name = "idx_bookmark_tag_bookmark_id", columnList = "bookmark_id"))
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

    public void applyMetadata(ContentType contentType, String place) {
        this.contentType = contentType;
        this.place = (place == null || place.isBlank()) ? null : place.trim();
    }

    // 트위터 API가 본문에 HTML 엔티티(&lt; &gt; &amp; 등)를 이스케이프해서 내려주는 경우가 있다
    // (예: 사용자가 실제로 "<제목>"이라고 썼으면 응답엔 "&lt;제목&gt;"로 옴). 저장은 그 원문
    // 그대로 하고, 읽을 때 여기서 한 번만 디코딩한다 — Thymeleaf th:text/JS textContent가 이미
    // 안전하게 한 번 이스케이프해 주므로, 저장 단계에서 미리 디코딩해두면 화면에 "&lt;제목&gt;"이
    // 그대로 텍스트로 보이는 이중 이스케이프 없이 원래 문자(<제목>)로 보인다.
    public String getText() {
        return decodeHtmlEntities(text);
    }

    public String getQuotedText() {
        return decodeHtmlEntities(quotedText);
    }

    private static String decodeHtmlEntities(String s) {
        if (s == null || s.indexOf('&') < 0) return s;
        return s.replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&amp;", "&");
    }

    // 본문 안의 URL을 클릭 가능한 링크로 바꾼 안전한 HTML. 인용 트윗 미리보기(quotedText)는
    // 이미 그 자체가 <a class="quote-card">로 감싸여 있어서 여기서 또 링크를 넣으면 앵커 중첩이
    // 되므로 대상에서 뺐다 — 본문(text)만 링크화한다. Thymeleaf th:utext와 클라이언트
    // innerHTML 양쪽에서 그대로 쓸 수 있도록, URL이 아닌 부분은 전부 이스케이프해서 반환한다.
    private static final Pattern URL_PATTERN = Pattern.compile("https?://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+");

    public String getTextHtml() {
        return linkify(getText());
    }

    private static String linkify(String text) {
        if (text == null) return "";
        Matcher m = URL_PATTERN.matcher(text);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        while (m.find()) {
            int start = m.start();
            int end = m.end();
            // URL 뒤에 바로 붙은 문장부호는 URL의 일부가 아닐 확률이 높아 링크에서 뺀다.
            while (end > start && ".,)]!?;:\"'".indexOf(text.charAt(end - 1)) >= 0) {
                end--;
            }
            if (end <= start) continue;
            sb.append(escapeHtml(text.substring(last, start)));
            String escapedUrl = escapeHtml(text.substring(start, end));
            sb.append("<a href=\"").append(escapedUrl)
                    .append("\" target=\"_blank\" rel=\"noopener\">")
                    .append(escapedUrl).append("</a>");
            last = end;
        }
        sb.append(escapeHtml(text.substring(last)));
        return sb.toString();
    }

    private static String escapeHtml(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
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
