package com.bookmarkdrawer.interestmap;

import com.bookmarkdrawer.bookmark.Bookmark;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

// blackbox의 홈("/"). "저장하면 → 알아서 의미적으로 모이고 → 필요할 때 관심사별로
// 탐색한다"는 제품 원칙에 따라 clustering 결과(.embedding-cache/clustering-1415.json·.txt)를
// 첫 화면으로 보여준다. 기존 /interest-map URL도 같은 핸들러로 계속 동작한다(중복 페이지를
// 만들지 않기 위해 매핑만 두 개, 화면은 하나). embedding 재생성/clustering 재실행/DB 변경은
// 여기서 하지 않는다 — 이미 계산된 결과 파일을 읽기만 한다.
@Controller
@RequiredArgsConstructor
public class InterestMapController {

    private final InterestMapService interestMapService;

    @GetMapping({"/", "/interest-map"})
    public String page(Model model) {
        InterestMapOverview overview = interestMapService.loadOverview();
        model.addAttribute("overview", overview);
        model.addAttribute("available", overview.clusterCount() > 0);
        model.addAttribute("umapAvailable", interestMapService.hasUmapData());
        // 2D map에서 상위 5개 cluster만 구분 색을 쓴다 — overview.clusters()가 이미 크기순으로
        // 정렬돼 있으니 새로 계산하지 않고 앞 5개 번호만 그대로 뽑아 JS에 넘긴다.
        model.addAttribute("topClusterNumbers", overview.clusters().stream()
                .limit(5)
                .map(ClusterSummary::number)
                .toList());
        // UMAP hover tooltip에 cluster 이름을 같이 보여주기 위한 번호->이름 맵. overview에
        // 이미 로드된 값을 그대로 재사용한다(파일을 다시 읽지 않음).
        Map<Integer, String> clusterNames = new LinkedHashMap<>();
        for (ClusterSummary c : overview.clusters()) {
            if (c.name() != null) {
                clusterNames.put(c.number(), c.name());
            }
        }
        model.addAttribute("clusterNames", clusterNames);
        return "interest-map";
    }

    @GetMapping("/api/interest-map/clusters/{number}")
    @ResponseBody
    public List<Bookmark> clusterMembers(@PathVariable int number) {
        return interestMapService.loadClusterMembers(number);
    }

    @GetMapping("/api/interest-map/noise")
    @ResponseBody
    public List<Bookmark> noise() {
        return interestMapService.loadNoise();
    }

    @GetMapping("/api/interest-map/umap")
    @ResponseBody
    public List<UmapPoint> umap() {
        return interestMapService.loadUmapPoints();
    }

    // cluster 이름만 수정한다 — 병합/분리/재분류는 아님. clustering 결과는 그대로 두고
    // cluster-labels-1415.json의 name 필드만 갱신한다.
    @PatchMapping("/api/interest-map/clusters/{number}/name")
    @ResponseBody
    public Map<String, String> updateClusterName(@PathVariable int number,
                                                   @Valid @RequestBody UpdateClusterNameRequest request) {
        try {
            String updated = interestMapService.updateClusterName(number, request.name());
            return Map.of("name", updated);
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }
}
