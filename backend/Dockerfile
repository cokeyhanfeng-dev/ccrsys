# 后端镜像:多阶段构建(JDK17 + Maven 构建,JDK17 JRE 运行)
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml ./
COPY ccr-common/pom.xml ccr-common/
COPY ccr-admin/pom.xml ccr-admin/
RUN mvn -B dependency:go-offline -q || true
COPY . .
RUN mvn -B -DskipTests package

# 注意:本环境无法访问 docker.io 拉取 eclipse-temurin:17-jre,运行阶段复用本地已有的 maven 镜像(内含 JDK 17)。
# 有网环境可改回 eclipse-temurin:17-jre 以减小镜像体积。
FROM maven:3.9-eclipse-temurin-17
WORKDIR /app
COPY --from=build /app/ccr-admin/target/ccr-admin.jar app.jar
EXPOSE 8080
ENV TZ=Asia/Shanghai
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
