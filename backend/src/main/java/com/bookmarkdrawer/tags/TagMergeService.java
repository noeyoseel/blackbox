package com.bookmarkdrawer.tags;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.bookmarkdrawer.bookmark.Bookmark;
import com.bookmarkdrawer.bookmark.BookmarkRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class TagMergeService {

    private static final String SYSTEM_PROMPT = """
            너는 트위터 북마크 태그 목록을 정리하는 어시스턴트다.
            아래는 사용자가 자유롭게 생성한 태그 목록이다. 같은 의미인데 표현만 다른 태그들
            (예: "리액트", "리액트 훅", "React" 는 같은 의미)을 그룹으로 묶어서 병합안을 제안해라.

            규칙:
            - 의미가 명확히 같거나 한쪽이 다른 쪽의 상위/하위 개념이 아니라 사실상 동의어인 경우에만 묶어라.
            - 애매하면 묶지 마라. 과도한 병합보다 누락이 낫다.
            - 각 그룹은 반드시 원본 태그 중 2개 이상을 포함해야 한다 (1개짜리 그룹은 만들지 마라).
            - canonicalTag는 그룹 중 가장 명확하고 간결한 한글 표현으로 새로 정해라 (원본 태그 중 하나를 그대로 써도 된다).
            - 관련 없는 태그는 그룹에 포함하지 마라. 모든 태그를 억지로 그룹화할 필요 없다.
            """;

    private final AnthropicClient anthropicClient;
    private final BookmarkRepository bookmarkRepository;

    public List<TagMergeGroup> suggestMerges() {
        List<String> allTags = bookmarkRepository.findAllDistinctTags();
        if (allTags.size() < 2) {
            return List.of();
        }

        StructuredMessageCreateParams<TagMergeSuggestions> params = MessageCreateParams.builder()
                .model("claude-sonnet-5")
                .maxTokens(4096L)
                .system(SYSTEM_PROMPT)
                .outputConfig(TagMergeSuggestions.class)
                .addUserMessage(String.join("\n", allTags))
                .build();

        TagMergeSuggestions result = anthropicClient.messages().create(params).content().stream()
                .flatMap(block -> block.text().stream())
                .findFirst()
                .map(textBlock -> textBlock.text())
                .orElseThrow(() -> new IllegalStateException("병합 제안 응답 없음"));

        return result.groups().stream()
                .filter(g -> g.tags() != null && g.tags().size() >= 2)
                .toList();
    }

    @Transactional
    public TagMergeResult applyMerge(String canonicalTag, List<String> tagsToMerge) {
        Set<String> sourceTags = new LinkedHashSet<>(tagsToMerge);
        sourceTags.remove(canonicalTag);
        if (sourceTags.isEmpty()) {
            return new TagMergeResult(canonicalTag, 0);
        }

        // 보관된 북마크까지 포함해서 통합한다. 활성 북마크만 병합하면 보관함에 옛 표기가
        // 남아 있다가 복원하는 순간 되살아나서, 방금 정리한 태그 목록이 다시 갈라진다.
        Set<Bookmark> affected = new LinkedHashSet<>();
        for (String tag : sourceTags) {
            affected.addAll(bookmarkRepository.findByTagIncludingArchived(tag));
        }

        for (Bookmark bookmark : affected) {
            Set<String> tags = new LinkedHashSet<>(bookmark.getTags());
            boolean changed = tags.removeAll(sourceTags);
            if (changed) {
                tags.add(canonicalTag);
                bookmark.applyTags(tags);
            }
        }
        bookmarkRepository.saveAll(affected);

        return new TagMergeResult(canonicalTag, affected.size());
    }
}
