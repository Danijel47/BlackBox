FROM maven:3.9-eclipse-temurin-25 AS build
ENV JAVA_TOOL_OPTIONS="-XX:-UseBASE64Intrinsics"
WORKDIR /app

COPY pom.xml ./
RUN mvn -q -DskipTests dependency:go-offline

COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:25-jre
WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /app/target/*.jar /app/app.jar

EXPOSE 8100

ENTRYPOINT ["sh","-c","exec java $JAVA_OPTS -XX:-UseBASE64Intrinsics -jar /app/app.jar"]
