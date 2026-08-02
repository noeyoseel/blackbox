package com.bookmarkdrawer.tagging;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.bookmarkdrawer.bookmark.Bookmark;
import com.bookmarkdrawer.bookmark.BookmarkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Service
@RequiredArgsConstructor
public class TaggingService {

    private static final String SYSTEM_PROMPT = """
            너는 트위터 북마크를 자유 태깅하는 어시스턴트다.
            주어진 트윗 본문을 읽고, 이 트윗의 주제/관심사를 나타내는 한글 태그를 2~5개 생성해라.
            태그는 짧고 구체적이어야 한다 (예: "리액트 훅", "이직 준비", "밈", "투자 전략").
            카테고리를 미리 정해두지 말고, 트윗 내용에 맞는 자유로운 태그를 생성해라.
            """;

    // 한 개씩 순차 호출하면 1,400건 기준 API 왕복 지연만으로 수십 분이 걸린다.
    // I/O 대기가 대부분이라 몇 개를 동시에 보내도 CPU 부담은 거의 없다 — 다만 Anthropic
    // 쪽 레이트리밋에 걸리지 않도록 동시 호출 수는 적당히 제한한다.
    private static final int CONCURRENCY = 8;

    private final AnthropicClient anthropicClient;
    private final BookmarkRepository bookmarkRepository;

    public TaggingResult tagUntagged() {
        List<Bookmark> untagged = bookmarkRepository.findByTaggedFalse();
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENCY);
        try {
            List<CompletableFuture<Boolean>> futures = untagged.stream()
                    .map(bookmark -> CompletableFuture.supplyAsync(() -> tagOne(bookmark), executor))
                    .toList();

            long succeeded = futures.stream().map(CompletableFuture::join).filter(ok -> ok).count();
            long failed = untagged.size() - succeeded;

            return new TaggingResult(untagged.size(), (int) succeeded, (int) failed);
        } finally {
            executor.shutdown();
        }
    }

    private boolean tagOne(Bookmark bookmark) {
        try {
            Set<String> tags = generateTags(buildPromptText(bookmark));
            bookmark.applyTags(tags);
            bookmarkRepository.save(bookmark);
            return true;
        } catch (Exception e) {
            log.warn("태깅 실패 tweetId={}", bookmark.getTweetId(), e);
            return false;
        }
    }

    private String buildPromptText(Bookmark bookmark) {
        if (bookmark.getQuotedText() == null || bookmark.getQuotedText().isBlank()) {
            return bookmark.getText();
        }
        return bookmark.getText() + "\n\n[인용된 트윗]\n" + bookmark.getQuotedText();
    }

    private Set<String> generateTags(String tweetText) {
        StructuredMessageCreateParams<TagsResult> params = MessageCreateParams.builder()
                .model("claude-sonnet-5")
                .maxTokens(512L)
                .system(SYSTEM_PROMPT)
                .outputConfig(TagsResult.class)
                .addUserMessage(tweetText)
                .build();

        TagsResult result = anthropicClient.messages().create(params).content().stream()
                .flatMap(block -> block.text().stream())
                .findFirst()
                .map(textBlock -> textBlock.text())
                .orElseThrow(() -> new IllegalStateException("태깅 응답 없음"));

        return new HashSet<>(result.tags());
    }
}
