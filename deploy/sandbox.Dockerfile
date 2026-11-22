FROM alpine:3.22 AS limits
COPY sandbox-limits.c sandbox-entrypoint.sh /src/
RUN apk add --no-cache --virtual .build build-base linux-headers \
    && mkdir /out \
    && cc -std=c11 -Os -Wall -Wextra -Werror -static /src/sandbox-limits.c -o /out/codeduel-sandbox-limits \
    && cp /src/sandbox-entrypoint.sh /out/codeduel-sandbox \
    && apk del .build

FROM eclipse-temurin:21-jdk-noble@sha256:a09e64f4976df9c4f2029c6abe64fc26f069a342ec9a7f147dce7b1933ebda16
COPY --from=limits --chmod=0555 /out/ /usr/local/bin/
USER 65534:65534
ENTRYPOINT ["/usr/bin/timeout", "--signal=KILL", "7s", "/usr/local/bin/codeduel-sandbox"]
