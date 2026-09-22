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

# ★ 必须切成 root。
#   gradle 官方镜像默认 USER gradle，而 WORKDIR 建出的目录属主是 root，
#   于是 Gradle 写 /app/build 时权限不足、秒退（构建日志里只有
#   "gradle bootJar did not complete successfully"，看不到真正原因）。
USER root
WORKDIR /app

# 先只拷构建脚本，利用 Docker 层缓存：改代码不会重新下载依赖。
# ★ 不加 `|| true` —— 那会把解析失败也吞掉，反而更难查。
COPY server/settings.gradle server/build.gradle ./
RUN gradle dependencies --no-daemon

COPY server/src ./src
RUN gradle bootJar --no-daemon

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

# ----------------------------------------------------------------------
# ★ JVM 参数是【为最小规格容器调的】，改之前先看这里
# ----------------------------------------------------------------------
# 目标：让服务在 0.25 核 0.5G（21.4 元/月，云托管最小规格）上跑得住。
# 关键不是"堆够不够大"，而是【堆 + 非堆】别超过容器内存上限 —— 超了会被
# OOM Kill，表现为容器反复重启、callContainer 一直失败、日志里看不到 Java 异常。
#
#   0.5 GiB 容器里：
#     堆      MaxRAMPercentage=50 → 256 MiB
#     非堆    Metaspace(≤128) + CodeCache + 线程栈 + JVM 自身 ≈ 200 MiB
#     合计    ≈ 456 MiB  <  512 MiB   ✅ 留了余量
#
# 原来写的 75% → 384 MiB 堆，加上非堆 ≈ 564 MiB > 512 MiB，会被杀掉。
# 这个改动同时也让 0.5 核 1G / 1 核 2G 更稳（堆按比例缩放，永远留一半给非堆）。
#
# 各项理由：
#   MaxRAMPercentage=50          按容器内存限额算堆，留一半给非堆
#   InitialRAMPercentage=25      初始堆小 → 启动时少分配少清零，启动更快
#   MaxMetaspaceSize=128m        封顶，防止类加载异常时无上限膨胀
#   Xss512k                      线程栈减半（默认 1MB），Tomcat 25 线程就省 12MB
#   +UseSerialGC                 容器只有 1 个可用 CPU（0.25/0.5 核），SerialGC
#                                比 G1 少一堆并发标记线程和卡表开销，小堆上更快更省。
#                                ★ 如果以后把规格升到 2 核 4G 以上，这行可以删掉
#   +ExitOnOutOfMemoryError      OOM 了就直接退出让平台拉起新实例，
#                                比僵在那里半死不活好排查得多
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
