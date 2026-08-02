#!/usr/bin/env bash
# .env 파일을 읽어서 환경변수로 export한 뒤 Spring Boot를 실행한다.
set -a
source "$(dirname "$0")/.env"
set +a
"$(dirname "$0")/gradlew" bootRun
