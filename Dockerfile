# syntax=docker/dockerfile:1.7
# cotcha-net 백엔드 이미지. docker compose up 으로 PostgreSQL·웹 화면과 함께 띄운다 (docker-compose.yml)

# ---- 빌드 ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src

# 의존성을 먼저 받아 두면 소스만 바뀌었을 때 이 층을 다시 쓴다
# 공용 코드 그래프 엔진은 GitHub Packages 토큰 없이 받도록 태그의 소스를 가져와 composite build 로 묶는다
ARG CORE_CODE_AGENT_REF=v0.8.0
ADD https://github.com/yeomyaloo/cotcha-trail.git#${CORE_CODE_AGENT_REF} /cotcha-trail

COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN chmod +x gradlew && ./gradlew --no-daemon -q -PcoreCodeAgentDir=/cotcha-trail dependencies > /dev/null

COPY src src
RUN ./gradlew --no-daemon -q -PcoreCodeAgentDir=/cotcha-trail bootJar -x test

# ---- 실행 ----
FROM eclipse-temurin:21-jre
WORKDIR /app

RUN groupadd --system app && useradd --system --gid app --home /app app \
    && mkdir -p /data/workspace && chown app:app /data/workspace
COPY --from=build /src/build/libs/code-agent-0.0.1-SNAPSHOT.jar app.jar

USER app
ENV CODEAGENT_WORKSPACE=/data/workspace
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
