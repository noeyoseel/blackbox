package com.bookmarkdrawer.tagging;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.bookmarkdrawer.bookmark.Bookmark;
import com.bookmarkdrawer.bookmark.BookmarkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

@Slf4j
@Service
@RequiredArgsConstructor
public class MetadataExtractionService {

    private static final String SYSTEM_PROMPT = """
            너는 트위터 북마크를 분류하는 어시스턴트다.
            주어진 트윗 본문을 읽고 다음 두 가지를 판단해라.

            1. contentType: 아래 순서대로 판단해서, 조건에 맞는 첫 번째 타입을 골라라.
               뒤 순서 타입의 조건이 더 잘 맞아 보여도 앞 순서에서 이미 YES면 그 타입으로 확정한다.

               1) PLACE: 저자가 실제로 그 장소에 있었거나 방문을 권하는 서술이 있는가?
                  (예: "여기 갔는데", "~투어", "~코스", "그자리에서" 같은 현장성 신호)
                  이 조건에 해당하면, 본문에 구체적 제품명이 여러 개 등장하더라도(예: 여행 코스
                  중 특정 매장에서 살 물건들을 나열) PLACE를 고른다 — 제품 언급 여부로 뒤집지 마라.

               2) PRODUCT: 이름이 지목된, 구매·예약·시술 가능한 구체적 대상을 다루는가?
                  대상이 하나든("이 제품 추천") 여러 개를 나열/비교하든("화장품 10개 비교",
                  "시술 11개 비교") 모두 PRODUCT다 — 개수나 목록 형식은 판단 기준이 아니다.
                  다만 그 항목군 전체에 대한 방법론·선택기준 설명("화장품 고르는 방법",
                  "시술 후 관리법")은 PRODUCT가 아니라 INFO다.
                  정부지원금, 정책성 혜택, 자격요건을 충족해 신청하는 지원사업은 PRODUCT가 아니라
                  INFO다. 금융상품도 단순히 "상품"이라는 표현이 쓰였다는 이유만으로 PRODUCT로
                  분류하지 마라.

               3) RESOURCE: 실제로 재사용 가능한 자료 그 자체인가?
                  - 외부 링크가 있고 그 링크가 실제 자료/도구/문서/사이트로 연결되는 경우, 또는
                  - 본문 자체가 그대로 복사해서 쓸 수 있는 프롬프트 원문/실행 가능한 커맨드/
                    템플릿/코드/체크리스트/자료 목록인 경우.
                  "이건 총정리에 가까운 자료다", "정리해둘 만하다"처럼 자료를 자처하거나 참고
                  가치를 언급하는 표현이 있어도, 실제 목록이나 링크 없이 저자의 설명/의견만
                  있다면 RESOURCE가 아니라 INFO다. 번호 목록·단계별 구조라는 형식만으로
                  RESOURCE로 분류하지 마라. 핵심 판별 질문: "이 글을 읽어야 정보를 얻는가,
                  아니면 글 안의 자료를 그대로 가져다 쓸 수 있는가?" 전자면 INFO, 후자면
                  RESOURCE다.

               4) INFO: 저자 본인의 설명·경험·의견·분석이 중심인 글. 위 세 조건 중 어느 것에도
                  해당하지 않으면 INFO다 — INFO가 기본값이다.

               5) OTHER: 위 어디에도 명확히 속하지 않는 글.

            2. place: 글에서 명시적으로 언급된 구체적인 지명이 있다면 적어라 (예: "제주", "행궁동", "체코").
               place는 지리적 장소만 의미한다 — 도시, 지역, 동네, 국가, 관광지, 실제 방문 가능한
               장소만 해당한다. 브랜드명·매장명·업체명·서비스명·제품명은 place가 아니다
               (예: "다이소", "스타벅스", "올리브영"은 place가 아님). 브랜드/업체가 언급되더라도
               실제 지리적 장소가 함께 명시되지 않았다면 place는 null로 둔다.
               명시적인 지명이 없으면 반드시 null로 남겨라. 추측하지 마라.
            """;

    // TaggingService와 동일한 이유로 동시 호출 수를 제한한다: I/O 대기가 대부분이라
    // 몇 개를 동시에 보내도 CPU 부담은 적지만, Anthropic 레이트리밋은 피해야 한다.
    private static final int CONCURRENCY = 8;

    private final AnthropicClient anthropicClient;
    private final BookmarkRepository bookmarkRepository;

    // /api/tagging/metadata-extract는 전체가 끝날 때까지 응답하지 않는 단일 블로킹 호출이라,
    // 화면에서 진행률을 보여주려면 별도로 폴링할 상태가 필요하다. 개인용 단일 사용자 도구라
    // 세션/요청 단위로 안 만들고 서비스 필드로만 들고 있는다.
    private final AtomicInteger progressTotal = new AtomicInteger(0);
    private final AtomicInteger progressCompleted = new AtomicInteger(0);

    public MetadataExtractionProgress getProgress() {
        return new MetadataExtractionProgress(progressTotal.get(), progressCompleted.get());
    }

    // Bookmark에는 반영하지 않는다: AI 제안은 그대로 응답에 담아 돌려주고, 확정은 사용자가
    // 검토한 뒤 기존 단건/배치 메타데이터 수정 API(PATCH /bookmarks/{tweetId}/metadata,
    // PATCH /bookmarks/batch/metadata)를 호출해서 한다.
    public MetadataExtractionResult extractUnclassified() {
        return extract(bookmarkRepository.findByContentTypeIsNull());
    }

    // 품질 검증용: 전체가 아니라 앞에서 limit개만 돌려본다.
    public MetadataExtractionResult extractSample(int limit) {
        List<Bookmark> unclassified = bookmarkRepository.findByContentTypeIsNull();
        return extract(unclassified.stream().limit(limit).toList());
    }

    // taxonomy 경계 검증 전용: 지정한 tweetId만 콕 집어 재분류해본다. 검증이 끝나면 지운다.
    public MetadataExtractionResult extractForTweetIds(List<String> tweetIds) {
        List<Bookmark> targets = tweetIds.stream()
                .map(bookmarkRepository::findByTweetId)
                .flatMap(java.util.Optional::stream)
                .toList();
        return extract(targets);
    }

    // 전체 배치 실행용: 완료되는 즉시(스레드마다) onResult로 넘긴다 — 호출자가 파일에 바로 써서
    // 중간에 프로세스가 죽어도 이미 끝난 건은 보존되게 하기 위함이다. alreadyDone에 있는
    // tweetId는 대상에서 아예 뺀다(재실행 시 중복 생성 방지). onResult는 여러 스레드에서
    // 동시에 불릴 수 있으니 호출자가 알아서 스레드 안전하게 처리해야 한다.
    public void extractUnclassifiedStreaming(Set<String> alreadyDone, Consumer<MetadataBatchOutcome> onResult) {
        List<Bookmark> targets = bookmarkRepository.findByContentTypeIsNull().stream()
                .filter(b -> !alreadyDone.contains(b.getTweetId()))
                .toList();
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENCY);
        try {
            List<CompletableFuture<Void>> futures = targets.stream()
                    .map(bookmark -> CompletableFuture.runAsync(() -> {
                        AttemptResult result = proposeOneWithAttempts(bookmark);
                        onResult.accept(new MetadataBatchOutcome(bookmark.getTweetId(), result.proposal(), result.attemptsUsed()));
                    }, executor))
                    .toList();
            futures.forEach(CompletableFuture::join);
        } finally {
            executor.shutdown();
        }
    }

    private MetadataExtractionResult extract(List<Bookmark> targets) {
        progressTotal.set(targets.size());
        progressCompleted.set(0);
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENCY);
        try {
            List<CompletableFuture<MetadataProposal>> futures = targets.stream()
                    .map(bookmark -> CompletableFuture.supplyAsync(() -> {
                        MetadataProposal proposal = proposeOne(bookmark);
                        progressCompleted.incrementAndGet();
                        return proposal;
                    }, executor))
                    .toList();

            List<MetadataProposal> proposals = futures.stream()
                    .map(CompletableFuture::join)
                    .filter(Objects::nonNull)
                    .toList();
            int failed = targets.size() - proposals.size();

            return new MetadataExtractionResult(targets.size(), proposals.size(), failed, proposals);
        } finally {
            executor.shutdown();
        }
    }

    // place 값이 실제 지명이 아니라 깨진 응답(모델 응답 포맷이 흐트러지며 다른 텍스트가
    // 새어나오는 경우)이거나 환각(본문에 없는 지명)인 사례가 검증 중 여러 번 관찰됐다.
    // contentType까지 통째로 버리기엔 아깝고, place만 걸러내는 게 "추측하지 마라"는
    // 원래 지침의 연장선이라 여기서 사후 검증한다.
    private static final int MAX_PLACE_LENGTH = 20;

    private boolean isPlausiblePlace(String place, String sourceText) {
        if (place == null || place.isBlank()) return true;
        if (place.length() > MAX_PLACE_LENGTH) return false;
        return sourceText.toLowerCase().contains(place.toLowerCase());
    }

    // 구조화 출력이 드물게 빈 응답으로 오는 경우가 검증 중 관찰됐다(동일 입력을 여러 번
    // 돌려보면 성공하는 경우가 많았다). 완전히 실패 처리하기 전에 한 번 더 시도한다.
    private static final int MAX_ATTEMPTS = 2;

    private MetadataProposal proposeOne(Bookmark bookmark) {
        return proposeOneWithAttempts(bookmark).proposal();
    }

    private record AttemptResult(MetadataProposal proposal, int attemptsUsed) {}

    private AttemptResult proposeOneWithAttempts(Bookmark bookmark) {
        String promptText = buildPromptText(bookmark);
        Exception lastError = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                ContentMetadataResult result = extractMetadata(promptText);
                String place = result.place();
                if (!isPlausiblePlace(place, promptText)) {
                    log.warn("place 값이 신뢰할 수 없어 버림 tweetId={} place={}", bookmark.getTweetId(), place);
                    place = null;
                }
                return new AttemptResult(new MetadataProposal(bookmark.getTweetId(), result.contentType(), place), attempt);
            } catch (Exception e) {
                lastError = e;
                log.warn("메타데이터 추출 실패(시도 {}/{}) tweetId={}", attempt, MAX_ATTEMPTS, bookmark.getTweetId(), e);
            }
        }
        log.warn("메타데이터 추출 최종 실패 tweetId={}", bookmark.getTweetId(), lastError);
        return new AttemptResult(null, MAX_ATTEMPTS);
    }

    private String buildPromptText(Bookmark bookmark) {
        if (bookmark.getQuotedText() == null || bookmark.getQuotedText().isBlank()) {
            return bookmark.getText();
        }
        return bookmark.getText() + "\n\n[인용된 트윗]\n" + bookmark.getQuotedText();
    }

    private ContentMetadataResult extractMetadata(String tweetText) {
        StructuredMessageCreateParams<ContentMetadataResult> params = MessageCreateParams.builder()
                .model("claude-sonnet-5")
                .maxTokens(256L)
                .system(SYSTEM_PROMPT)
                .outputConfig(ContentMetadataResult.class)
                .addUserMessage(tweetText)
                .build();

        return anthropicClient.messages().create(params).content().stream()
                .flatMap(block -> block.text().stream())
                .findFirst()
                .map(textBlock -> textBlock.text())
                .orElseThrow(() -> new IllegalStateException("메타데이터 추출 응답 없음"));
    }
}
