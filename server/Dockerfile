# 微信云托管 / 任意容器平台通用
#
# 用 gradle 官方镜像构建，所以仓库里不需要 gradle wrapper（二进制文件）。
# 本地构建也可以直接用这个 Dockerfile：
#   docker build -t campus-api ./server

# ---------- 构建阶段 ----------
FROM gradle:8.10-jdk17 AS build

# ★ 必须切成 root。gradle 官方镜像默认 USER gradle，而 WORKDIR 建出的目录
#   属主是 root，Gradle 写 /app/build 会权限不足、构建 7 秒即失败，
#   而且日志里只说 "did not complete successfully"，看不出真正原因。
USER root
WORKDIR /app

# 先只拷构建脚本，利用 Docker 层缓存：改代码不会重新下载依赖。
# ★ 不加 `|| true` —— 它会把依赖解析失败也吞掉，反而更难查。
COPY settings.gradle build.gradle ./
RUN gradle dependencies --no-daemon

COPY src ./src
RUN gradle bootJar --no-daemon

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
#
# ★ JVM 参数是【为最小规格容器调的】。根目录那个 Dockerfile 里有一份完整说明，
#   改这里记得同步那边 —— 这两份文件内容必须保持一致，只有 COPY 路径不同。
#
# 摘要：0.5 GiB 容器里堆取 50%（256 MiB），非堆（Metaspace ≤128m + CodeCache
#       + 线程栈 + JVM 自身）约 200 MiB，合计 ≈ 456 MiB，留在 512 MiB 以内。
#       原来的 75% → 384 MiB 堆，非堆一加就 564 MiB，会被 OOM Kill。
ENTRYPOINT ["java", \
  "-XX:MaxRAMPercentage=50", \
  "-XX:InitialRAMPercentage=25", \
  "-XX:MaxMetaspaceSize=128m", \
  "-Xss512k", \
  "-XX:+UseSerialGC", \
  "-XX:+ExitOnOutOfMemoryError", \
  "-Dfile.encoding=UTF-8", \
  "-Dsun.jnu.encoding=UTF-8", \
  "-Duser.timezone=Asia/Shanghai", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "/app/app.jar"]
