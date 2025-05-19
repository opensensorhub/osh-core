FROM gradle:8 AS builder

WORKDIR /home/gradle

COPY . .

RUN ./gradlew build

FROM openjdk:25-slim

RUN apt update && apt install unzip && rm -rf /var/cache/apt/archives /var/lib/apt/lists/*

COPY --from=builder /home/gradle/build/distributions/osh-core-osgi-2.0-beta2.zip /root/osh.zip

WORKDIR /root

RUN unzip osh.zip

WORKDIR /root/osh-core-osgi-2.0-beta2

CMD ["sh", "launch.sh"]

EXPOSE 8181