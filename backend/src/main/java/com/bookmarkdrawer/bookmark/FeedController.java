package com.bookmarkdrawer.bookmark;

import com.bookmarkdrawer.duplicates.DuplicateDetectionService;
import com.bookmarkdrawer.duplicates.DuplicateGroupView;
import com.bookmarkdrawer.duplicates.DuplicateType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

// 북마크 피드("/feed"). 평소엔 일반 피드를 보여주지만 mode=organize로 오면 (구 /organize
// 페이지였던) 정리 모드 화면을 같은 URL·같은 템플릿(feed.html) 안에서 렌더링한다 — "정리는
// 별도 페이지가 아니라 피드의 한 상태"라는 IA 원칙 때문에 컨트롤러도 하나로 합쳤다.
// 홈("/")은 InterestMapController가 담당한다 — "저장하면 의미적으로 모이고, 필요할 때
// 관심사별로 탐색한다"는 제품 원칙에 따라 Interest Map이 첫 화면이고, 개별 북마크를 훑어보며
// 정리하는 이 피드는 그 다음 단계(nav의 "피드" 링크, 또는 cluster 상세에서 원문으로)다.
@Controller
@RequiredArgsConstructor
public class FeedController {

    private static final int PAGE_SIZE = 20;

    private final BookmarkService bookmarkService;
    private final DuplicateDetectionService duplicateDetectionService;

    @GetMapping("/feed")
    public String home(@RequestParam(defaultValue = "newest") String sort,
                        @RequestParam(defaultValue = "feed") String mode,
                        @RequestParam(defaultValue = "candidates") String filter,
                        @RequestParam(required = false) ContentType contentType,
                        @RequestParam(required = false) String tag,
                        Model model) {
        model.addAttribute("mode", mode);
        model.addAttribute("sort", sort);
        model.addAttribute("filter", filter);
        model.addAttribute("contentType", contentType);
        model.addAttribute("tag", tag);
        model.addAttribute("unclassifiedCount", bookmarkService.countUnclassified());

        if ("organize".equals(mode)) {
            return organize(filter, sort, model);
        }

        model.addAttribute("isDuplicateView", false);
        FeedPage feedPage = bookmarkService.feed(0, PAGE_SIZE, sort, contentType, tag);
        model.addAttribute("bookmarks", feedPage.bookmarks());
        model.addAttribute("hasMore", feedPage.hasMore());
        model.addAttribute("pageSize", PAGE_SIZE);
        return "feed";
    }

    private String organize(String filter, String sort, Model model) {
        if ("duplicate_exact".equals(filter) || "duplicate_near".equals(filter)) {
            DuplicateType type = "duplicate_exact".equals(filter) ? DuplicateType.EXACT : DuplicateType.NEAR;
            List<DuplicateGroupView> groups = duplicateDetectionService.listGroups(type);
            model.addAttribute("isDuplicateView", true);
            model.addAttribute("duplicateGroups", groups);
            model.addAttribute("filter", filter);
            return "feed";
        }

        OrganizeFilter resolvedFilter = OrganizeFilter.from(filter);
        OrganizePage organizePage = bookmarkService.organize(resolvedFilter, 0, PAGE_SIZE, sort);
        model.addAttribute("isDuplicateView", false);
        model.addAttribute("bookmarks", organizePage.bookmarks());
        model.addAttribute("hasMore", organizePage.hasMore());
        model.addAttribute("totalCandidates", organizePage.totalCandidates());
        model.addAttribute("pageSize", PAGE_SIZE);
        model.addAttribute("filter", resolvedFilter.name().toLowerCase());
        return "feed";
    }
}
