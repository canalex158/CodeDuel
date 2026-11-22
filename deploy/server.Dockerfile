FROM eclipse-temurin:21-jre-alpine
WORKDIR /opt/codeduel
COPY --chmod=0444 codeduel-server/build/libs/codeduel-server.jar server.jar
USER 65534:65534
ENTRYPOINT ["java","-Xms32m","-Xmx256m","-jar","/opt/codeduel/server.jar"]
