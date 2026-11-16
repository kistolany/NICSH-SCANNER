# One image with all three backend apps; the APP env var picks which one runs (see ../docker-compose.yml).
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.gradle \
    chmod +x gradlew && ./gradlew bootJar -x test --no-daemon -q \
       -Dorg.gradle.internal.repository.max.retries=10 \
       -Dorg.gradle.internal.http.connectionTimeout=120000 \
       -Dorg.gradle.internal.http.socketTimeout=120000 \
    && mkdir /out \
    && for m in auth-server cmt-api web-bff; do cp "$(ls $m/build/libs/*.jar | grep -v plain)" /out/$m.jar; done

FROM eclipse-temurin:21-jre
WORKDIR /app
ENV TZ=Asia/Phnom_Penh
COPY --from=build /out/ ./
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/$APP.jar"]
