# 使用 Maven 构建阶段
FROM maven:3.8.6-openjdk-8 AS build
WORKDIR /app

# 复制 pom.xml 和源代码
COPY pom.xml .
COPY src ./src

# 构建项目（包含依赖）
RUN mvn clean package -DskipTests

# 运行阶段
FROM eclipse-temurin:8-jre
WORKDIR /app

# 从构建阶段复制 JAR 文件
COPY --from=build /app/target/bsc-monitor-1.0-SNAPSHOT-jar-with-dependencies.jar app.jar

# 复制配置文件（如果需要覆盖）
COPY src/main/resources/application.properties /app/application.properties

# 设置环境变量
ENV JAVA_OPTS="-Xmx512m -Xms256m"

# 暴露端口（如果需要）
# EXPOSE 8080

# 运行应用
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]

