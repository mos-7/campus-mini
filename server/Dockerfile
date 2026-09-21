# 微信云托管 / 任意容器平台通用
#
# 用 gradle 官方镜像构建，所以仓库里不需要 gradle wrapper（二进制文件）。
# 本地构建也可以直接用这个 Dockerfile：
#   docker build -t campus-api ./server

# ---------- 构建阶段 ----------
FROM gradle:8.10-jdk17 AS build
WORKDIR /app

# 先只拷构建脚本，利用 Docker 层缓存：改代码不会重新下载依赖
COPY settings.gradle build.gradle ./
RUN gradle dependencies --no-daemon -q || true

COPY src ./src
RUN gradle bootJar --no-daemon -x test

# ---------- 运行阶段 ----------
FROM eclipse-temurin:17-jre
WORKDIR /app

# 时区：云托管容器默认 UTC，不设课表日期会错一天
ENV TZ=Asia/Shanghai
# locale：不设的话 temurin 镜像默认字符集可能是 ASCII，
# 读 schema.sql 里的中文会乱码（application.yml 里也显式设了 UTF-8，双保险）
ENV LANG=C.UTF-8
ENV LC_ALL=C.UTF-8
ENV PORT=8080

COPY --from=build /app/build/libs/app.jar /app/app.jar

EXPOSE 8080

# 云托管会在实例起来后探活；graceful shutdown 让同步线程有机会收尾
ENTRYPOINT ["java", \
  "-XX:MaxRAMPercentage=75", \
  "-Dfile.encoding=UTF-8", \
  "-Dsun.jnu.encoding=UTF-8", \
  "-Duser.timezone=Asia/Shanghai", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "/app/app.jar"]
