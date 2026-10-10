package com.bookmarkdrawer.tags;

import com.bookmarkdrawer.bookmark.Bookmark;
import com.bookmarkdrawer.bookmark.BookmarkRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class TagBrowserService {

    // 기본 화면에 노출할 최소 사용 횟수. 태그가 자유 생성이라 한 번만 쓰인 태그가 대부분인데,
    // 그것까지 전부 그리면 목록에서 아무것도 찾을 수 없다. 개수 상한(예: 상위 300개)이 아니라
    // 빈도 임계값을 쓰는 이유는 데이터가 늘어도 "3회 이상"의 의미가 그대로 유지되기 때문이다.
    public static final long DEFAULT_MIN_COUNT = 3;

    // 전체 보기: 1회 이상 = 사실상 전부.
    public static final long SHOW_ALL_MIN_COUNT = 1;

    private final BookmarkRepository bookmarkRepository;

    public List<TagCount> listTags(long minCount) {
        return bookmarkRepository.countTagsWithMinimum(minCount).stream()
                .map(row -> new TagCount((String) row[0], (Long) row[1]))
                .toList();
    }

    public long countDistinctTags() {
        return bookmarkRepository.countTagsWithMinimum(SHOW_ALL_MIN_COUNT).size();
    }

    public List<Bookmark> bookmarksForTag(String tag) {
        return bookmarkRepository.findByTag(tag);
    }
}
