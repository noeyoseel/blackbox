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
- 없음. `spring-boot-starter-test` 의존성은 있지만 `backend/src/test` 자체가 존재하지 않습니다.

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
| 자동 테스트, CI/CD | 없음 |

개인 사이드 프로젝트로 진행 중이며 완성된 상태가 아닙니다.

## 다음 방향

- 임베딩 기반 의미 검색 — 아직 구현 전이며 방향성만 있는 상태입니다.

## 실행 방법

### Backend
```bash
cd backend
# backend/.env 에 DB_PASSWORD, ANTHROPIC_API_KEY 설정 필요 (spring-dotenv가 자동 로드)
# DB_USERNAME 은 선택이며, 지정하지 않으면 application.yml의 기본값을 사용
./gradlew bootRun   # http://localhost:8080
```
로컬에 MySQL이 떠 있어야 하며, `bookmark_drawer` 데이터베이스가 필요합니다. 스키마는 `ddl-auto: update`로 자동 반영됩니다. 서버 포트는 `PORT` 환경변수로 바꿀 수 있습니다(기본 8080).

### Extension
1. `chrome://extensions` → 개발자 모드 활성화
2. "압축해제된 확장 프로그램을 로드합니다" → `extension/` 디렉터리 선택
3. `x.com`(또는 `twitter.com`)의 북마크 탭으로 이동, 페이지 새로고침 후 우측 상단 플로팅 버튼으로 수집 시작
