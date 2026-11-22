FROM docker:29-cli AS docker-cli
FROM eclipse-temurin:21-jre-alpine
COPY --from=docker-cli /usr/local/bin/docker /usr/local/bin/docker
COPY codeduel-judge/build/install/codeduel-judge/ /opt/codeduel/
ENV JAVA_OPTS="-Xms16m -Xmx192m"
ENTRYPOINT ["/opt/codeduel/bin/codeduel-judge"]
