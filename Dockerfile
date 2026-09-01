FROM eclipse-temurin:21-jdk-jammy AS builder

WORKDIR /workspace

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw --batch-mode dependency:go-offline

COPY src/ src/
RUN ./mvnw --batch-mode clean package -DskipTests

FROM eclipse-temurin:21-jre-jammy

RUN groupadd --gid 10001 spring \
    && useradd --uid 10001 --gid spring --system spring \
    && mkdir -p /tmp \
    && chown 10001:10001 /tmp

WORKDIR /app

ENV JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=/tmp"

COPY --from=builder --chown=10001:10001 \
    /workspace/target/payment-service-*.jar app.jar

VOLUME ["/tmp"]

USER 10001:10001

EXPOSE 8085

ENTRYPOINT ["java", "-jar", "app.jar"]
