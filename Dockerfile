FROM openjdk:20-jdk AS build-stage
WORKDIR /bot

COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .
RUN ./mvnw dependency:go-offline

COPY ./src ./src
RUN ./mvnw package -DskipTests

FROM openjdk:20-jdk AS run-stage
COPY --from=build-stage /bot/target/TelegramHaircut*.jar ./bot.jar
CMD ["java", "-jar", "bot.jar"]
