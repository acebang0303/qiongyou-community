# ★ P2-5：后端镜像（多阶段构建）
#   构建阶段用 maven 打包，运行阶段只留 JRE + jar，镜像更小
#   项目 target 为 Java 8，故构建/运行镜像都用 JDK/JRE 8

# ---------- 构建阶段 ----------
FROM maven:3.9-eclipse-temurin-8 AS build
WORKDIR /build

# 先只拷 pom 拉依赖：源码变化时这一层可复用，不必重新下载
COPY pom.xml .
# pom 里的 maven-resources-plugin 会引用 ../sql/init.sql（供测试建库用）。
# 构建上下文只有 backend/，镜像里没有 ../sql 目录，先建个空的 /sql，避免资源插件在 process-test-resources 阶段报错。
RUN mkdir -p /sql && mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q -DskipTests package

# ---------- 运行阶段 ----------
FROM eclipse-temurin:8-jre
WORKDIR /app
COPY --from=build /build/target/qiongyou-backend-1.0.0.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
