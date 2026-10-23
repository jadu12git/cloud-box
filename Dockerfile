FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline
COPY src src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN groupadd --system cloudbox && useradd --system --gid cloudbox cloudbox && mkdir /data && chown cloudbox:cloudbox /data
COPY --from=build /app/target/cloudbox-1.0.0.jar /app/cloudbox.jar
USER cloudbox
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/cloudbox.jar"]