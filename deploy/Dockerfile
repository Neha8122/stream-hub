# One image recipe for every service: docker build --build-arg MODULE=home-service -f deploy/Dockerfile .
# Build the jars first (mvn package -DskipTests).
FROM eclipse-temurin:21-jre AS layers
ARG MODULE
WORKDIR /build
COPY ${MODULE}/target/${MODULE}-0.1.0-SNAPSHOT.jar app.jar
# Split the jar into layers (dependencies change rarely, our code often),
# so a rebuild after a code change reuses the big dependency layer.
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 10001 app
COPY --from=layers /build/extracted/dependencies/ ./
COPY --from=layers /build/extracted/spring-boot-loader/ ./
COPY --from=layers /build/extracted/snapshot-dependencies/ ./
COPY --from=layers /build/extracted/application/ ./
USER 10001
# Heap as a share of the container's memory limit, not the node's; the rest
# is for metaspace, threads, and (catalog, assistant) the ONNX model.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
