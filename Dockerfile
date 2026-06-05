# ===== build stage: Gradle 래퍼로 bootJar 생성 (로컬에 JDK/Gradle 불필요) =====
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

# 의존성 레이어 캐싱: 빌드 스크립트/래퍼만 먼저 복사해 의존성 선다운로드
COPY gradlew ./
COPY gradle ./gradle
COPY settings.gradle.kts build.gradle.kts ./
RUN chmod +x ./gradlew && ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true

# 소스 복사 후 테스트 제외하고 실행 가능한 jar 빌드 (테스트는 Testcontainers/Docker 필요)
COPY src ./src
RUN ./gradlew --no-daemon clean bootJar -x test

# ===== runtime stage: JRE만 포함한 경량 이미지 =====
FROM eclipse-temurin:21-jre
WORKDIR /app

# healthcheck용 curl + 비루트 사용자
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd -r -u 1001 appuser
USER appuser

COPY --from=build /app/build/libs/*-SNAPSHOT.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
