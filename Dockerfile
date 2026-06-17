# Dockerfile은 인라인(#) 주석 미지원 — 주석은 반드시 자기 줄에! (COPY/EXPOSE 뒤 #는 인자로 먹힘)

# ---- build stage: jar 빌드 ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY . .
# 테스트는 CI 게이트에서 이미 돌렸으므로 skip. 실행 jar을 고정 이름으로 복사(COPY 글롭 회피).
RUN chmod +x mvnw && ./mvnw -B -ntp -DskipTests package && cp target/*.jar app.jar

# ---- run stage: Semeru JRE에서 실행 ----
FROM ibm-semeru-runtimes:open-21-jre
WORKDIR /app
COPY --from=build /app/app.jar app.jar
EXPOSE 8080
# Java 21 + Hadoop(parquet) SecurityManager 호환
ENTRYPOINT ["java", "-Djava.security.manager=allow", "-jar", "/app/app.jar"]
