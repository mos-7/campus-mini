# ======================================================================
# 云托管「绑定 GitHub 仓库」用的 Dockerfile —— 构建上下文是【仓库根目录】
# ======================================================================
# 为什么根目录也要有一个：
#   云托管的 GitHub 绑定默认从仓库根目录找 Dockerfile。
#   而本仓库结构是 server/ + miniprogram/，真正的 Dockerfile 在 server/ 下，
#   所以根目录放这一个，把 COPY 路径指到 server/。
#
# server/Dockerfile 仍然保留，供本地 `docker build ./server` 用。
# 两者内容等价，只有 COPY 的路径不同 —— 改了一个记得同步另一个。

# ---------- 构建阶段 ----------
FROM gradle:8.10-jdk17 AS build
WORKDIR /app

# 先只拷构建脚本，利用 Docker 层缓存：改代码不会重新下载依赖
COPY server/settings.gradle server/build.gradle ./
RUN gradle dependencies --no-daemon -q || true

COPY server/src ./src
RUN gradle bootJar --no-daemon -x test

# ---------- 运行阶段 ----------
FROM eclipse-temurin:17-jre
WORKDIR /app

# 时区：容器默认 UTC，不设课表日期会错一天
ENV TZ=Asia/Shanghai
# locale：不设的话 temurin 镜像默认字符集可能是 ASCII，读含中文的脚本会乱码
ENV LANG=C.UTF-8
ENV LC_ALL=C.UTF-8
ENV PORT=8080

COPY --from=build /app/build/libs/app.jar /app/app.jar

EXPOSE 8080

ENTRYPOINT ["java", \
  "-XX:MaxRAMPercentage=75", \
  "-Dfile.encoding=UTF-8", \
  "-Dsun.jnu.encoding=UTF-8", \
  "-Duser.timezone=Asia/Shanghai", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "/app/app.jar"]
