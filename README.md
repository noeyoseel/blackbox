# blackbox

트위터(X) 북마크를 수집하고, Claude로 태깅·분류하고, 임베딩 기반 관심사 지도(Interest Map)로 탐색하는 개인용 북마크 매니저입니다. Chrome 확장이 북마크 탭의 GraphQL 응답을 가로채 트윗을 수집하고, Spring Boot 백엔드가 이를 저장·서빙합니다. 확장과 백엔드는 각각 독립적으로 개발되고 HTTP로만 통신합니다.

> 프로젝트의 공식 이름은 **blackbox**입니다. 빌드·코드 식별자(Gradle 프로젝트명 `bookmark-drawer`, 패키지 `com.bookmarkdrawer`, 기본 DB 이름 `bookmark_drawer`)는 초기 이름이 남은 것으로 기능과 무관합니다.

## 기술 스택

**Backend**
- Java 17
- Spring Boot 3.3.4 (`spring-boot-starter-web`, `spring-boot-starter-thymeleaf`, `spring-boot-starter-data-jpa`, `spring-boot-starter-validation`)
- MySQL (`mysql-connector-j`, 런타임 전용) — 스키마는 `ddl-auto: update`로 관리 (별도 마이그레이션 도구 없음)
- Lombok
- [`anthropic-java`](https://github.com/anthropics/anthropic-sdk-java) 2.34.0 — `claude-sonnet-5` 모델을 structured output으로 호출 (자유 태깅 생성, 태그 병합 제안, 콘텐츠 유형·장소 제안)
- `spring-dotenv` — `backend/.env`를 자동 로드

**Extension**
- Manifest V3, 순수 JavaScript (빌드 도구/의존성 없음)
- `chrome.storage.local`, `chrome.downloads`, `chrome.scripting` 등 Chrome Extension API

**Frontend (서버 렌더링)**
- Thymeleaf 템플릿 + 페이지별 vanilla JS (번들러 없음, `backend/`에 `package.json` 없음)
- Pretendard 웹폰트 (CDN)

**테스트**
- 접근 인증(`config/` 패키지)에 대한 단위 테스트만 `backend/src/test`에 있습니다(`./gradlew test`). 그 외 기능에는 자동 테스트가 없습니다.

## 현재 구현된 기능

### 수집 (Extension → Backend)
- `x.com`/`twitter.com`의 북마크 탭에서 `fetch`/`XMLHttpRequest`를 후킹해 Twitter의 북마크 GraphQL 응답을 가로채 파싱 (`page-hook.js` → `content.js`)
- 긴 트윗(280자 초과), 리트윗, 인용 트윗의 본문/미디어를 각각의 실제 위치에서 복원
- 플로팅 버튼으로 자동 스크롤 수집 시작/중지, 연속 무응답 감지로 종료 판단
- 수집한 트윗을 `POST /api/bookmarks/batch`로 백엔드에 전송, 백엔드가 꺼져 있으면 `chrome.storage.local`에 백업 (팝업에서 개수 확인 / JSON 내보내기 / 초기화 가능)
- 같은 트윗을 다시 수집해도 tweetId 기준으로 중복 저장하지 않고, 더 완전한 데이터(더 긴 본문, 새로 채워진 작성자/미디어 정보)로만 갱신
- 수집 응답이 덮는 순서 구간 안에서 DB에는 있지만 응답에는 없는 북마크(트위터에서 북마크 해제된 것으로 추정)를 보관 처리하도록 구현되어 있습니다 (`sync-check`). 코드 기준의 서술이며 실제 동작은 별도로 검증하지 않았습니다.
- 수집 세션이 끝나면 확장이 자동으로 AI 태깅(`POST /api/tagging/run`)을 호출

### 열람/정리 (Backend, Thymeleaf UI)
- 피드 페이지: 최신순/오래된순 정렬, 콘텐츠 유형·태그 필터, 무한 스크롤 페이징
- 정리 모드: 태그·보관·메타데이터를 여러 북마크에 일괄 적용
- 태그 수동 편집, 태그별 목록 조회, 태그 목록/카운트 조회
- 보관 처리 / 보관 해제 (`/archive`), 보관함에 있는 항목만 영구 삭제 가능
- 본문·태그 텍스트 LIKE 검색 (`/search`) — **키워드 매칭이며 의미(벡터) 검색이 아닙니다**
- 중복 북마크 감지와 그룹별 정리 (`/api/duplicates`, 피드 페이지에서 사용)

### AI 태깅·분류
- 미태깅 북마크에 대해 Claude로 한글 태그 2~5개 자동 생성 (`POST /api/tagging/run`), 최대 8개 동시 호출
- 태그 유사어 병합 제안 생성 (`POST /api/tags/merge-suggestions`) 후, 사람이 검토해서 적용 (`POST /api/tags/merge`, `/tags/merge` 페이지). 표기만 다른 태그는 정규화 후보로 별도 제시
- 콘텐츠 유형(정보·장소·제품·자료·기타)과 장소 제안 (`POST /api/tagging/metadata-extract`) — 제안만 하며 저장은 사용자가 확인한 뒤에 이루어집니다. 피드 페이지의 "AI로 북마크 정리하기"에서 콘텐츠 유형만 일괄 적용할 수 있고, 장소는 카드별로 직접 수정

### Interest Map (`/`, `/interest-map`)
- **태깅된 북마크만**을 대상으로 임베딩·클러스터링·2차원 투영(UMAP)을 **사전에 계산**해 둔 결과 파일을 읽어 지도로 보여 주고, 클러스터 선택·이름 수정·소속 북마크 탐색을 제공합니다.
- 앱이 임베딩이나 클러스터링을 실시간으로 수행하지 않으며, 새로 수집·태깅한 북마크는 결과 파일을 다시 만들기 전까지 **지도에 반영되지 않습니다(자동 갱신 없음)**. 아직 태깅되지 않은 북마크도 지도에 포함되지 않습니다.
- 결과 파일은 이 저장소에 포함되어 있지 않습니다. 파일이 없으면 "아직 생성된 clustering 결과가 없습니다" 상태로 표시됩니다.
- 결과 디렉터리는 `blackbox.interest-map.cache-dir`(기본값 `.embedding-cache`)로, 기능 전체는 `blackbox.interest-map.enabled=false`로 끌 수 있습니다.
- 결과 파일을 생성하는 실험 코드는 이 저장소에 포함되어 있지 않습니다.

### 접근 인증 (단일 사용자 HTTP Basic)
- 모든 요청(페이지, 정적 리소스, API)에 인증을 요구하는 서블릿 필터(`config/AuthFilter`)가 기본으로 켜져 있습니다. 필터는 `REQUEST`와 `ERROR` 디스패치에 등록되어(`config/AuthFilterConfig`), 컨테이너가 필터 앞에서 만든 오류도 인증 없이는 앱의 오류 응답으로 나가지 않습니다. 사용자는 한 명뿐이며 계정 관리 기능은 없습니다. (Tomcat이 서블릿 처리 이전에 직접 답하는 `OPTIONS *`나 파싱할 수 없는 요청의 최소 응답에는 앱의 내용이 없습니다.)
- 설정은 환경변수로만 합니다: `BLACKBOX_AUTH_USER`, `BLACKBOX_AUTH_PASSWORD_HASH`(PBKDF2-HMAC-SHA256 해시, 평문 비밀번호는 저장하지 않음), `BLACKBOX_AUTH_ALLOWED_ORIGINS`(허용할 접속 origin, 쉼표 구분, `https://도메인` 형식이며 기본 포트는 생략). 사용자명·해시가 없거나 형식이 틀리면 모든 요청을 503으로 거부합니다(fail-closed).
- 해시는 `./gradlew classes` 후 `java -cp build/classes/java/main com.bookmarkdrawer.config.PasswordHash` 로 만들고, 비밀번호는 인자가 아니라 표준 입력으로 넣습니다. 24자 이상의 무작위 문자열을 쓰는 것을 전제로 합니다.
- 상태 변경 요청(POST/PUT/PATCH/DELETE)은 `Origin`이 허용 목록과 일치해야 하고, `Sec-Fetch-Site`가 `same-origin`/`none`이 아니면 거부합니다(보조 신호, 거부에만 사용). `Origin`이 없는 요청은 허용하되 자격 증명은 여전히 요구합니다.
- 검증에 성공한 `Authorization` 헤더는 HMAC 다이제스트만 작은 메모리 캐시(최대 16개, 마지막 사용 후 12시간·생성 후 24시간 만료, 서버 재시작 시 초기화)에 보관해 이후 요청은 비밀번호를 다시 계산하지 않습니다. 원본 헤더와 평문 비밀번호는 저장하지 않으며, 캐시에 적중한 요청도 출처 검증을 먼저 거칩니다.
- 캐시에 없는 헤더의 검증(PBKDF2)은 동시 슬롯 수로 제한되고, 대기열이 차면 기다리지 않고 503을 돌려줍니다. 인증 실패 요청이 폭주해도 이미 로그인한 사용자(캐시 적중)는 영향받지 않지만, 폭주 중의 **새 로그인은 일시적으로 503을 받아 재시도해야 할 수 있습니다.** 클라이언트별 구분은 프록시가 덮어쓴다고 확인된 IP 헤더를 `BLACKBOX_AUTH_CLIENT_IP_HEADER`로 지정했을 때만 하며, 지정하지 않으면 모든 요청을 한 덩어리로 봅니다.
- **로컬 개발 전용:** `backend/.env`에 `BLACKBOX_AUTH_ENABLED=false`를 두면 인증을 끕니다(Chrome 확장이 `localhost:8080`을 자격 증명 없이 호출하기 때문). 배포 환경(플랫폼이 주입하는 환경변수로 감지)에서는 이 설정이 무시됩니다.
- **한계:** HTTP Basic에는 서버 측 로그아웃이 없습니다. 브라우저가 창/세션 동안 자격 증명을 기억하며, 비밀번호를 바꾸려면 해시 변수를 교체하고 서버를 재시작해야 합니다(재시작 후 이전 비밀번호는 즉시 거부). 다단계 인증은 없습니다.

### 라이선싱
- Ed25519 서명 기반 오프라인 라이선스 검증 (서버 호출 없음), 검증된 라이선스는 `~/.blackbox/license.key`에 저장
- 현재는 개인 사용 단계라 AI 기능에 라이선스 게이트가 걸려 있지 않습니다. 검증 로직과 `/api/license` API는 구현되어 있습니다.
- 발급 도구(`LicenseGenerator`, `./gradlew generateLicense`)는 판매자가 로컬에서만 실행하며 개인키는 저장소에 없습니다. 앱에는 **데모용 공개키**만 내장되어 있습니다.

## 구현 현황

| 구분 | 상태 |
|---|---|
| 수집, 태깅, 태그 병합, 콘텐츠 유형·장소 제안, 중복 감지, 일괄 정리 | 구현됨 |
| Interest Map | 구현됨 (사전 계산된 결과 파일 표시, 자동 갱신 없음) |
| 의미(벡터) 검색, Vector DB | 미구현 |
| 라이선스 결제 연동, 라이선스 게이트 적용 | 미구현 |
| 자동 테스트 | 접근 인증 단위 테스트만 있음, 그 외 없음 |
| CI/CD | 없음 |

개인 사이드 프로젝트로 진행 중이며 완성된 상태가 아닙니다.

## 다음 방향

- 임베딩 기반 의미 검색 — 아직 구현 전이며 방향성만 있는 상태입니다.

## 실행 방법

### Backend
```bash
cd backend
# backend/.env 에 DB_PASSWORD, ANTHROPIC_API_KEY 설정 필요 (spring-dotenv가 자동 로드)
# DB_USERNAME 은 선택이며, 지정하지 않으면 application.yml의 기본값을 사용
# 로컬 개발에서는 BLACKBOX_AUTH_ENABLED=false 도 .env 에 넣어야 합니다(없으면 인증 필터가 모든 요청을 거부)
./gradlew bootRun   # http://localhost:8080
```
로컬에 MySQL이 떠 있어야 하며, `bookmark_drawer` 데이터베이스가 필요합니다. 스키마는 `ddl-auto: update`로 자동 반영됩니다. 서버 포트는 `PORT` 환경변수로 바꿀 수 있습니다(기본 8080).

### Extension
1. `chrome://extensions` → 개발자 모드 활성화
2. "압축해제된 확장 프로그램을 로드합니다" → `extension/` 디렉터리 선택
3. `x.com`(또는 `twitter.com`)의 북마크 탭으로 이동, 페이지 새로고침 후 우측 상단 플로팅 버튼으로 수집 시작
