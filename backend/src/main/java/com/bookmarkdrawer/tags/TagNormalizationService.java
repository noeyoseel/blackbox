package com.bookmarkdrawer.tags;

import com.bookmarkdrawer.bookmark.BookmarkRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// 표기만 다른 같은 태그를 찾아낸다. AI를 쓰지 않고 문자열 정규화만으로 판정하므로
// 결과가 항상 같고 비용이 들지 않는다.
//
// 의도적으로 "의미가 비슷한" 태그는 묶지 않는다:
//   "맛집추천" ↔ "맛집 추천"  → 정규화 결과가 같음   → 후보 O
//   "맛집"     ↔ "맛집 추천"  → 정규화 결과가 다름   → 후보 X
// 후자처럼 의미 판단이 필요한 병합은 사람이 검토하는 AI 제안(TagMergeService)의 몫이다.
@Service
@RequiredArgsConstructor
public class TagNormalizationService {

    private final BookmarkRepository bookmarkRepository;

    // 공백·가운뎃점·하이픈·언더스코어를 지우고 소문자화한다. 여기까지만 한다 —
    // 조사 제거나 어간 추출까지 들어가면 서로 다른 개념이 합쳐질 위험이 생긴다.
    static String normalize(String tag) {
        return tag.replaceAll("[\\s·\\-_]", "").toLowerCase(Locale.ROOT);
    }

    public List<TagMergeGroup> findVariantGroups() {
        // 병합이 보관 북마크까지 대상으로 하므로 후보 탐지도 archived를 포함해서 센다.
        // 정렬이 COUNT DESC라 각 그룹의 첫 원소가 곧 최다 빈도 표기가 된다.
        List<Object[]> rows = bookmarkRepository.countAllTagsIncludingArchived();

        Map<String, List<String>> byNormalized = new LinkedHashMap<>();
        for (Object[] row : rows) {
            String tag = (String) row[0];
            byNormalized.computeIfAbsent(normalize(tag), k -> new ArrayList<>()).add(tag);
        }

        List<TagMergeGroup> groups = new ArrayList<>();
        for (List<String> variants : byNormalized.values()) {
            if (variants.size() < 2) continue;
            // canonicalTag는 최다 빈도 표기를 기본값으로 제안할 뿐이고,
            // 최종 선택과 실행은 화면에서 사용자가 한다(자동 병합 없음).
            groups.add(new TagMergeGroup(variants.get(0), variants));
        }
        return groups;
    }
}
