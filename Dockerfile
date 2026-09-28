# Docker container used to build the Clojure app
FROM clojure:temurin-21-tools-deps-bookworm-slim AS builder

WORKDIR /usr/src/app

COPY deps.edn /usr/src/app/deps.edn
COPY build.clj /usr/src/app/build.clj
COPY src/ /usr/src/app/src

RUN clj -T:build uber


# Build the docker container we will use in the lambda
FROM eclipse-temurin:21-jre-noble

RUN mkdir /opt/app

COPY --from=builder /usr/src/app/target/rightmove-alerter.jar /opt/app/app.jar

ENTRYPOINT [ "java", "-cp", "/opt/app/app.jar", "com.amazonaws.services.lambda.runtime.api.client.AWSLambda" ]

CMD ["rightmove_alerter.handler::handleRequest"]
