# BlackBox

트위터(X) 북마크를 로컬로 수집하고, Claude로 자동 태깅해서 정리하는 개인용 북마크 매니저입니다. Chrome 확장이 북마크 탭의 GraphQL 응답을 가로채 트윗을 수집하고, Spring Boot 백엔드가 이를 저장·서빙합니다.

내부 코드네임은 "Blackbox"이며, 확장/백엔드는 각각 독립적으로 개발되고 HTTP로만 통신합니다.

## 기술 스택

**Backend**
- Java 17
- Spring Boot 3.3.4 (`spring-boot-starter-web`, `spring-boot-starter-thymeleaf`, `spring-boot-starter-data-jpa`, `spring-boot-starter-validation`)
- MySQL (`mysql-connector-j`, 런타임 전용) — 스키마는 `ddl-auto: update`로 관리 (별도 마이그레이션 도구 없음)
- Lombok
- [`anthropic-java`](https://github.com/anthropics/anthropic-sdk-java) 2.34.0 — `claude-sonnet-5` 모델을 structured output으로 호출 (자유 태깅 생성, 태그 병합 제안)
- `spring-dotenv` — `backend/.env`를 자동 로드

**Extension**
- Manifest V3, 순수 JavaScript (빌드 도구/의존성 없음)
- `chrome.storage.local`, `chrome.downloads`, `chrome.scripting` 등 Chrome Extension API

**Frontend (서버 렌더링)**
- Thymeleaf 템플릿 + 페이지별 vanilla JS (번들러 없음, `backend/`에 `package.json` 없음)
- Chart.js (대시보드 페이지, CDN)
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
- 트위터 응답 구간과 우리 DB를 비교해, 트위터에서 삭제(북마크 해제)된 트윗을 감지해 자동으로 보관 처리 (`sync-check`)

### 열람/정리 (Backend, Thymeleaf UI)
- 피드 페이지: 최신순/오래된순 정렬, 무한 스크롤 페이징
- 태그 수동 편집, 폴더 생성/이름변경/삭제 및 북마크를 폴더에 배정 (폴더 하나에만 소속)
- 보관 처리 / 보관 해제, 보관함에 있는 항목만 영구 삭제 가능
- 본문 텍스트 LIKE 검색 (`/search`, 대소문자 무시)
- 태그별 목록 조회, 태그 목록/카운트 조회
- 대시보드: 전체/태깅 완료 건수, 월별 수집 추이, 태그 빈도 상위 20개 (Chart.js로 시각화)

### AI 태깅
- 미태깅 북마크에 대해 Claude로 한글 태그 2~5개 자동 생성 (`POST /api/tagging/run`), 최대 8개 동시 호출
- 태그 유사어 병합 제안 생성 (`POST /api/tags/merge-suggestions`) 후, 사람이 검토해서 적용 (`POST /api/tags/merge`, `/tags/merge` 페이지)

### 라이선싱
- Ed25519 서명 기반 오프라인 라이선스 검증 (서버 호출 없음), 검증된 라이선스는 `~/.blackbox/license.key`에 저장
- 현재 `POST /api/tagging/run`(AI 자동 태깅) 한 곳에만 라이선스 게이트가 걸려 있고, 다른 기능은 라이선스 없이도 사용 가능
- 라이선스 발급용 개인키/발급 로직(`LicenseGenerator`)은 코드베이스에 없고 판매자가 로컬에서만 실행하는 구조 (공개키만 앱에 내장)

## 진행 상황

**개인 사이드 프로젝트로 현재 진행 중이며, 완성된 상태가 아닙니다.**

- 테스트 코드 없음
- 검색은 단순 LIKE 매칭이며, 의미 기반 검색은 아직 없음
- 라이선스 결제 연동(Stripe 등)은 없고, 검증 로직만 구현되어 있음
- CI/CD, 배포 자동화 없음 — 로컬 실행만 가능

## 다음 방향

- 임베딩 기반 의미 검색(RAG, Vector DB 도입) — 아직 구현 전이며, 현재는 방향성만 있는 상태입니다.

## 실행 방법

### Backend
```bash
cd backend
# backend/.env 에 DB_USERNAME, DB_PASSWORD, ANTHROPIC_API_KEY 설정 필요 (spring-dotenv가 자동 로드)
./gradlew bootRun   # http://localhost:8080
```
로컬에 MySQL이 떠 있어야 하며, `bookmark_drawer` 데이터베이스가 필요합니다. 스키마는 `ddl-auto: update`로 자동 반영됩니다.

### Extension
1. `chrome://extensions` → 개발자 모드 활성화
2. "압축해제된 확장 프로그램을 로드합니다" → `extension/` 디렉터리 선택
3. `x.com`(또는 `twitter.com`)의 북마크 탭으로 이동, 페이지 새로고침 후 우측 상단 플로팅 버튼으로 수집 시작
