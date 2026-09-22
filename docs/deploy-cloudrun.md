# 部署到微信云托管（免域名、免备案）

## 一、为什么是这条路

你选了「原生小程序 + Spring Boot 3」，但手上没有服务器和备案域名。这两件事本来是冲突的
—— 小程序的 `wx.request` 只能请求 **HTTPS + 已 ICP 备案 + 在 mp 后台配置过** 的域名。

微信云托管把这个问题消掉了。官方文档原文：

> 小程序/公众号可使用 `CallContainer`，无需域名，因此**不需要在 mp 管理后台配置服务器域名**，
> 从而一开始就无需申请域名，也**无需备案**。
> —— [微信云托管开发常识](https://developers.weixin.qq.com/miniprogram/dev/wxcloudservice/wxcloudrun/src/guide/debug/know.html)

所以：Spring Boot 照写，Docker 镜像一推，小程序用 `wx.cloud.callContainer` 调，
**不用买服务器、不用备案、不用配域名白名单**。

## 二、前置条件

- 一个**小程序 AppID**（还没注册就去 [mp.weixin.qq.com](https://mp.weixin.qq.com) 注册）
- 微信开发者工具已登录该 AppID
- 小程序后台已开通「云托管」（云开发 → 云托管）

> ⚠️ **个人主体**可以开通云托管，但要注意小程序**类目**：这类校园信息查询工具，
> 报「工具 - 效率」或「工具 - 信息查询」。报「教育」类目大概率要资质。
> 另外个人主体**开不了微信支付**，所以参考截图里那个"投喂"打赏做不了。
> 这两条请到 mp 后台实际确认一遍。

## 三、创建环境和服务

1. 云托管控制台 → **新建环境**（如 `campus-prod`）
2. 环境里 → **新建服务**
   - 服务名：`campus-api`  ← **必须和 `miniprogram/utils/api.js` 里的 `CLOUD_SERVICE` 一致**
   - 端口：`8080`  ← 必须和 `Dockerfile` 的 `EXPOSE` 及 `application.yml` 的 `PORT` 一致
3. 记下**环境 ID**（形如 `campus-1g2h3i4j5k6l7m`）→ 填到 `api.js` 的 `CLOUD_ENV`

## 四、创建 MySQL

云托管控制台 → **MySQL** → 新建实例。建好后拿到内网地址。

> 云托管**不能**自己部署数据库容器（官方限制），必须用它提供的 MySQL 服务。

把 `server/src/main/resources/schema.sql` 里的建表语句在 MySQL 里执行一遍
（或者让 Spring Boot 用 `spring.sql.init` 跑 —— 见下面第六节）。

## 五、生成两个密钥

**⚠️ 这两个值绝不能提交到代码库。**

PowerShell（Windows）：

```powershell
# ★ 注意：PowerShell 5.1 是 .NET Framework，【没有】[Convert]::ToHexString
#   （那是 .NET 5+ 才加的）。硬用会静默得到空字符串 —— 踩过。
$rng = New-Object System.Security.Cryptography.RNGCryptoServiceProvider

# 32 字节 AES 主密钥（Base64）
$b = New-Object byte[] 32
$rng.GetBytes($b)
"CAMPUS_MASTER_KEY=" + [Convert]::ToBase64String($b)

# JWT 签名密钥（64 位十六进制）
$j = New-Object byte[] 32
$rng.GetBytes($j)
"CAMPUS_JWT_SECRET=" + (-join ($j | ForEach-Object { $_.ToString('x2') }))
```

Linux / macOS：

```bash
echo "CAMPUS_MASTER_KEY=$(openssl rand -base64 32)"
echo "CAMPUS_JWT_SECRET=$(openssl rand -hex 32)"
```

## 六、配置环境变量

云托管 → 服务设置 → **环境变量**：

| 变量 | 值 | 说明 |
| --- | --- | --- |
| `CAMPUS_MASTER_KEY` | 上面生成的 | **不配就会用开发用固定密钥，等于没加密** |
| `CAMPUS_JWT_SECRET` | 上面生成的 | 同上 |
| `PORT` | `8080` | |
| `SPRING_DATASOURCE_URL` | `jdbc:mysql://<内网地址>:3306/campus?...` | |
| `SPRING_DATASOURCE_USERNAME` | 你的库用户 | |
| `SPRING_DATASOURCE_PASSWORD` | 你的库密码 | |
| `SPRING_DATASOURCE_DRIVER_CLASS_NAME` | `com.mysql.cj.jdbc.Driver` | 覆盖默认的 H2 |
| `SPRING_SQL_INIT_MODE` | `always`（首次建表后改 `never`） | 见下 |

### 平台连接也走环境变量

各平台的地址/密钥属于**部署私有配置**，不要写进代码或配置文件，
否则会跟着 `server.zip` 一起烘进镜像（`.dockerignore` 已经把
`src/main/resources/application-local.yml` 排除掉了，但环境变量才是正路）：

| 变量 | 值 |
| --- | --- |
| `CAMPUS_MOBILEJW_ENABLED` | `true` |
| `CAMPUS_MOBILEJW_BASE_URL` | 你学校的教务系统 API 地址 |
| `CAMPUS_MOBILEJW_PWD_KEY` | 厂商的 16 字符 AES 密钥 |

> ### ★ 环境变量命名规则（踩过，很隐蔽）
>
> Spring Boot 把配置属性映射到环境变量时，规则是：
> **点换成下划线，连字符`-`直接删掉，然后全大写。**
>
> | 配置属性 | 对应的环境变量 | 能用吗 |
> | --- | --- | --- |
> | `spring.datasource.url` | `SPRING_DATASOURCE_URL` | ✅ |
> | `campus.mobilejw.base.url` | `CAMPUS_MOBILEJW_BASE_URL` | ✅ |
> | `campus.mobilejw.base-url` | `CAMPUS_MOBILEJW_BASEURL`（**没有下划线**） | 只有这个能用 |
> | ~~`CAMPUS_MOBILEJW_BASE_URL`~~ 配 `base-url` | 绑不上，静默为空 | ❌ |
>
> **所以本项目的属性名一律用点分（`base.url`、`pwd.key`）而不是连字符**，
> 这样环境变量就是任何人都会写的 `CAMPUS_MOBILEJW_BASE_URL`。
>
> 这个坑的恶劣之处在于**它不报错**：变量明明填了，属性就是空的，
> 表现成「平台显示暂未开放」，能在控制台里翻半天。
> 用 `[启动自检]` 那几行日志可以一秒确认（见下一节）。

⚠️ **生产必改的三个开关**：

```
CAMPUS_WECHAT_MOCK_ENABLED=false     # 否则任何人都能传 openid 冒充登录！
CAMPUS_CHAOXING_ENABLED=false        # 抓包联调通过后再改 true
CAMPUS_DEBUG_ENDPOINTS=false         # 它会返回平台原始响应，含个人信息
```

> **注意 `CAMPUS_DEMO_DATA` 要保持 `true`**（这是唯一一个开着比关着好的开关）。
> 它让**没绑定账号的用户**（包括微信审核员）能看到一份带「示例数据」横幅的示例课表。
> 关掉的话新用户看到空白页，审核被驳回的概率明显上升 —— 见
> [`publish-miniprogram.md`](publish-miniprogram.md) 陷阱 1。

`CAMPUS_WECHAT_MOCK_ENABLED=true` 在生产是**严重安全漏洞** ——
别人只要 POST 一个 `{"openid":"随便谁的"}` 就能拿到那个人的 token。务必关掉。

首次部署时 `SPRING_SQL_INIT_MODE=always` 让 Spring Boot 建表（`schema.sql` 是幂等的），
确认建好后改成 `never`。

## 七、部署

三种方式，推荐第一种：

### 方式 A：代码包部署（最快）

```powershell
cd D:\syq\campus-mini\server
# 用临时目录暂存再打包 —— 直接压 src 会把 application-local.yml
# （含学校地址和厂商密钥）也装进去，那是个坑。
$stage = "$env:TEMP\campus-stage"
Remove-Item $stage -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $stage | Out-Null
Copy-Item build.gradle, settings.gradle, Dockerfile, .dockerignore -Destination $stage
Copy-Item src -Destination $stage -Recurse
Remove-Item "$stage\src\test" -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item "$stage\src\main\resources\application-local.yml" -Force -ErrorAction SilentlyContinue
Compress-Archive -Path "$stage\*" -DestinationPath ..\server.zip -Force
```

云托管控制台 → 服务 → **新建版本** → 上传 `server.zip` → 部署。

云托管会读 `Dockerfile` 用 `gradle:8.10-jdk17` 镜像构建。

### 方式 B：关联代码仓库（推 Git 自动构建）★ 推荐

把仓库连到云托管，之后 `git push` 即自动部署 —— 不用每次手动打包。

⚠️ **一个必须注意的点**：云托管的 GitHub 绑定**默认从仓库根目录找 Dockerfile**。
而本仓库的 Dockerfile 在 `server/` 下，所以根目录**额外放了一个** `Dockerfile`
（`COPY server/src ...`），配合根目录的 `.dockerignore`，让默认配置直接可用。

> 两个 Dockerfile 内容等价，只有 COPY 路径不同（根目录那个带 `server/` 前缀）。
> **改了一个记得同步另一个。**

配好后在「部署发布」页：
- 选择方式：**绑定 GitHub 仓库**
- 代码仓库 / 分支：选你的仓库和 `main`
- **端口：`8080`** ← ⚠️ 默认是 `80`，必须改（要和容器监听端口一致）
- 点「发布」

### 方式 C：本地构建镜像推送

需要本机装 Docker：

```powershell
cd D:\syq\campus-mini\server
docker build -t campus-api .
```

## 八、★ 把最小副本设为 1

云托管 → 服务设置 → **实例副本数最小值 = 1**。

**这一步不能省。** 原因有两个，都在官方文档里写明：

1. 「设置最小副本为 0 时…半小时无请求服务将缩容到 0」
   → 缩容会**杀掉后台线程**，你的同步任务会中途消失
2. 「自动扩缩容只根据 HTTP 请求流量判断，如果服务不接受外部请求，只是在自行跑定时任务，
   会被『误判』为没有使用，触发缩容。此种场景，请手动设定实例副本数最小值为 1，保持常驻」

代价是持续产生费用（按量计费，学生党用量很小，通常几块钱一个月）。
我们的 `SyncService` 也做了兜底：状态落库 + 启动时清理僵尸任务，
所以就算被缩容也不会永远卡在 RUNNING —— 但用户体验会变差。

## 九、小程序端切换上线模式

改 `miniprogram/utils/api.js`：

```js
const USE_CLOUD = true;
const CLOUD_ENV = 'campus-1g2h3i4j5k6l7m';   // 你的环境 ID
const CLOUD_SERVICE = 'campus-api';           // 你的服务名
```

`callContainer` 必须显式带 `X-WX-SERVICE` 头（云托管靠它路由），
`api.js` 里已经带了。

## 十、验证

按顺序，每步都要看到预期结果：

1. 云托管控制台 → 服务 → **服务日志**，确认启动没有报错
2. 浏览器访问默认公网域名 `/api/health`
   → 应返回 `{"code":0,"message":"ok","data":{"status":"UP",...}}`
   > ⚠️ 默认公网域名**仅供测试**，性能有限，官方明确「请勿用于正式生产环境」。
   > 小程序走 `callContainer`，不经过它。
3. 微信开发者工具打开小程序 → 首页应显示演示课表
4. 进「我的」→ 看到「服务绑定中心 1 / 2 已连接」（manual 已自动绑定）
5. **在服务日志里打印一次请求头**，核对云托管注入 openid 的**确切名字**
   → 如果不是 `x-wx-openid`，改 `CAMPUS_WECHAT_OPENID_HEADER` 环境变量，不用改代码

## 十一、六个坑（都在官方文档里）

| 限制 | 对我们的影响 |
| --- | --- |
| `CallContainer` 超时 **≤ 15s** | 爬平台必须异步 —— `SyncService` 已经这么做了 |
| 请求大小限制 **100K** | 别把课表 JSON 整坨塞进请求体；同步是后端自己发起的，没问题 |
| 不支持 **TCP/UDP/MQTT** | 只能 HTTP。超星也是 HTTP，没事 |
| **不支持一个服务开多个端口** | 单端口 8080 |
| 容器**无持久化存储** | 别往容器写文件，用 MySQL / 对象存储 |
| **不支持 Docker Compose** | 一个 Dockerfile 一个服务 |

另外：**云托管的公网出口 IP 是动态的**，且官方「对公网域名不具备安全防护能力」。
如果超星对这个 IP 段风控，见 [`chaoxing.md`](chaoxing.md) 第六节。

## 十二、成本（备案通过、上线之后怎么算）

> **结论：上线本身不改变计费方式。** 云托管从创建环境那天起就是按量付费、按秒结算，
> 备案和小程序发布都不会切换计费口径。上线改变的只是**用量**（从你一个人变成 N 个人），
> 以及 **3 个月免费额度可能已经过期** 这件事。
>
> 真正要盯的是：实例规格、MySQL 算力、实例副本数上限、免费额度到期日。下面逐个说。

### 12.1 三项主要费用怎么产生

| 计费项 | 刊例价 | 什么时候产生 | 什么时候不产生 |
| --- | --- | --- | --- |
| 容器 CPU | 0.055 元/（核·小时） | 实例数 ≥ 1，按秒累加 | 实例缩到 0 |
| 容器内存 | 0.032 元/（GiB·小时） | 同上 | 同上 |
| MySQL 算力 | 0.342 元/（CCU·小时） | 任何一次读写或运算 | 开「自动暂停」，连续 10 分钟无操作即暂停 |
| MySQL 存储 | 0.00485 元/（GB·小时） | 开通即算（空库也占 ~28MB） | 销毁实例 |
| 公网流量 | 0.8 元/GB | 服务出网（我们出网访问教务系统） | **只走 callContainer 不产生** |
| 构建时长 | 0.05 元/分钟 | 用「代码库拉取」方式发布 | 用「镜像拉取」方式发布 |

**两条关键结论：**

1. **小程序走 `callContainer` 不产生流量费** —— 这是云托管最大的优势。
   唯一的流量开销是后端出网去学校教务系统（地址见环境变量 `CAMPUS_MOBILEJW_BASE_URL`，
   **本仓库是公开的，别把真实地址写进文档或代码**），按每次同步几百 KB 估算，一个月 1~2 元，可以忽略。
2. **MySQL 算力是最容易失控的一项。** 1 CCU ≈ 1 核 2GB 算力，24 小时不停就是
   `0.342 × 720 ≈ 246 元/月`，**直接超过容器费**。它只能靠「自动暂停」压下来，
   而常驻后端 + 连接池有可能让数据库始终"看起来有负载"。
   → **上线后第一个要观察的数字，就是费用中心里的"数据库算力"日用量。**

### 12.2 月度费用估算

按 730 小时/月、刊例价、不含任何优惠估算：

| 配置 | 容器 | MySQL 算力 | 存储+流量 | 合计 |
| --- | --- | --- | --- | --- |
| 最省：0.25 核 0.5G + 数据库能自动暂停 | 21 元 | ~5 元 | ~5 元 | **~26 元/月** |
| 典型：0.5 核 1G + 数据库空闲 0.25CCU | 43 元 | 62 元 | ~5 元 | **~110 元/月** |
| 不利：1 核 2G + 数据库整天 1CCU | 86 元 | 246 元 | ~5 元 | **~337 元/月** |

单实例常驻（24 小时）的规格对照：

| 规格 | 元/小时 | 元/月 |
| --- | --- | --- |
| 0.25 核 0.5G | 0.02975 | 21.4 |
| 0.5 核 1G | 0.0595 | 42.8 |
| 1 核 1G | 0.087 | 62.6 |
| 1 核 2G | 0.119 | 85.7 |
| 2 核 4G | 0.238 | 171.4 |

> 由于最小副本数是 **1**（第八节，不能改成 0），容器这一项是 24 小时全额计费的，
> 没有"闲置不计费"的空间。省钱只能从「降规格」和「压 MySQL 算力」两头下手。

### 12.3 免费额度：从「开通环境」起算，不是从上线起算

首个环境赠送的免费额度**有效期 3 个月**（自开通环境起算）：

- CPU 720 核·小时 + 内存 1440 GB·小时 → 0.5 核 1G 常驻只能撑约 **2 个月**
- MySQL 算力 720 个·小时 → 1CCU 常驻只够 **1 个月**
- 另有：构建时长 600 分钟、公网流量 5GB、数据库存储 720 GB·小时

**本项目卡备案卡了很久，免费额度大概率已经用完或即将用完。**
上线前先去「云托管控制台 → 费用中心 → 充值与账单」把剩余额度看清 ——
这决定了上线当天是不是立刻开始掏钱。

### 12.4 ★ 上线前必须做的五件事

1. **查免费额度剩余**（费用中心 → 充值与账单）
2. **把实例规格改成 0.25 核 0.5G**（服务 → 版本配置）—— 云托管最小规格，21.4 元/月。
   实测内存占用 214 MB，512 MB 容器装得下（见 13.2 节）。**先按这个跑，
   出现容器反复重启再退到 0.5 核 1G**
3. **把「实例最大副本数」调低** —— 默认上限 50 个，万一被刷就是每天上千元。
   个人自用直接设成 **1**：费用精确封顶在单实例月费，且单实例本来就能处理并发请求
4. **确认 MySQL「自动暂停」是开着的**，上线后头几天盯着数据库算力日用量
5. **账户里留余额** —— 欠费直接停服，用户看到的是"服务不可用"

### 12.5 一个必须先确认的口径问题

微信目前有两套计费口径并存，你的环境属于哪一套要先确认：

- **云托管按量计费**（本文档的算法）：免费额度 3 个月，之后按刊例价实扣
- **云开发的「免费云环境」**（2025-02-19 起）：环境在**小程序发布上线后第 15 天到期**，
  之后要转 19.9 元/月的基础套餐

**怎么分辨**：登录 https://cloud.weixin.qq.com ，如果控制台里显示「免费套餐 / 资源点余额」，
就是第二套；如果只有服务、版本、MySQL 这些页签、费用按 CPU/内存/CCU 列，就是第一套。

> 这是唯一一个会让"上线"真正改变账单的地方 —— 值得花两分钟确认。

---

## 十三、怎么最省（学生党版）

### 13.1 三条路的真实年成本

| 方案 | 年成本 | 一次性门槛 | 代价 |
| --- | --- | --- | --- |
| **A. 云托管 + 实例锁死 1 + 0.25 核 0.5G** | **≈ 315 元/年**（26 元/月） | 无 | 无。费用可精确预测，**推荐先走这条** |
| **B. 云托管 + 允许缩容到 0** | **≈ 175 元/年**（15 元/月） | 无 | 每天首次打开小程序要等冷启动，撞 `callContainer` 的 15s 超时 |
| **C. 腾讯云轻量 + 已备案域名** | **≈ 130 元/年** | 域名备案 15~20 工作日 | 要自己运维；但能扔掉 MySQL，也没有 15s 超时限制 |

**A 的构成**（730 小时/月）：容器 21 元 + MySQL 算力 ≈ 0 + 存储与出网流量 ≈ 5 元。

> 建议 **A 和 C 并行**：先用 A 上线（不阻塞备案通过后的审核提交），
> 同时买域名提交域名备案，备案下来再切 C。切换本身只改小程序端一个开关。

**A 已经是"拧到底"的结果**，往下只剩两条路：接受冷启动（B），或换供应商 + 办域名备案（C）。
再没有别的技巧了 —— 3 个月免费额度只送首个环境，且大概率已经用完。

### 13.2 实测：0.25 核 0.5G 能不能跑？（结论：能，而且余量充足）

这一节是**实测**，不是估算。方法：用本机 JDK 17.0.16 跑 `app.jar`，
参数与 `Dockerfile` 完全一致，并用 `-XX:MaxRAM=512m` 让 JVM 按 512MB 容器来算堆，
再用 `-XX:NativeMemoryTracking=summary` 读真实占用。

```
Total: reserved=741638KB, committed=219334KB     ← 实际提交 214 MB
  Java Heap      131200 KB = 128 MB   （上限 256 MB）
  Metaspace       35264 KB =  35 MB
  Code cache      17651 KB =  17 MB
  Symbol           8970 KB =   9 MB
  Thread           2045 KB =   2 MB
  GC / Compiler / Arena / Module / Internal / Other  ≈ 2 MB
```

（数字是在压过 `/api/adapters`、`/api/auth/login`、`/api/schedule/today`、
`/api/courses` 之后读的，不是刚启动时的空载值。）

**结论：**

| 场景 | 占用 |
| --- | --- |
| 当前实测（堆用到 128 MB） | **214 MB** |
| 最坏情况（堆涨到上限 256 MB） | **≈ 342 MB** |
| 0.5 GiB 容器上限 | 512 MB |

**0.25 核 0.5G（21.4 元/月）跑得下，余量还很足。**

> ⚠️ 我上一轮说"0.25 核 0.5G 会被 OOM Kill"，那个判断是错的 —— 我当时按
> Metaspace 要 80~120 MB 估的，那是**带 JPA/Hibernate 的项目**的量级。
> 本项目只有 `starter-web` + `starter-jdbc`，没用 JPA，Metaspace 实测只吃 35 MB。
> 这就是为什么要实测：`build.gradle` 里少一个依赖，内存账就完全不同。

**为什么 `MaxRAMPercentage` 仍要从 75 降到 50：** 75% 会给出 384 MB 堆，
加上 86 MB 非堆 ≈ 470 MB，虽然勉强塞得进 512 MB 但没有余量了 ——
GC 峰值、JIT 编译、突发请求都可能把进程顶出去。50% 是"够用且安全"的位置。

**代价（0.25 核这一档真正要接受的东西）：**

- CPU 只有 1/4 核，**启动慢**。本机多核实测约 3.5 秒启动，0.25 核估计要 15~30 秒。
  但**只在部署新版本时发生**（最小副本 = 1 时实例不会缩容），所以对用户基本无感
- 响应变慢。课表查询是"查库 + 拼 JSON"的活儿，几毫秒的 CPU 足够；
  真正的重活（AES 解密 + 抓教务系统 + 解析）在 `SyncService` 的后台线程里异步跑，
  用户看的是进度条，不是白屏
- **万一还是被 OOM Kill**，症状是容器反复重启、`callContainer` 一直失败。
  判断方法：看云托管「服务日志」有没有反复出现 Spring Boot 启动横幅；
  真遇到了就退到 0.5 核 1G（42.8 元/月），JVM 参数**不用改**（按比例缩放，堆自动变 512 MB）

### 13.3 方案 B：把副本最小值设成 0，省掉 70% 的容器费

云托管「连续半小时无请求就缩容到 0」。缩容后不产生任何容器费用。

- 按每天实际被「粘住」约 8 小时算（有请求就拉起，之后 30 分钟无请求才缩容）：
  `(0.5 × 0.055 + 1 × 0.032) × 8 × 30 ≈ 14.3 元/月` → **约 180 元/年**
- **代价**：实例被销毁后，下一个请求要等冷启动。Spring Boot 3 在 0.5 核上冷启动十几秒到几十秒，
  而 `callContainer` 硬超时 15 秒 → **每天第一个打开小程序的用户会失败一次**。
- 缓解办法：小程序首页加载时先发一个空请求「点火」并显示骨架屏，用户真正点「同步」时实例已经起来了。
  但每天首次冷启动那一下，体验损失躲不掉。

还有一层风险：缩容会**杀掉 `SyncService` 的后台线程**（见第八节）。
代码里有兜底（`SyncTaskStore.failStale` + 启动时清理僵尸任务），
但用户会看到"同步中断，请重试"。**如果在意体验，就留在方案 A。**

### 13.4 方案 C：换腾讯云轻量 —— 最便宜，但要自己干活

年成本构成（新用户活动价）：

| 项 | 价格 |
| --- | --- |
| 轻量应用服务器 2 核 2G（3~4M 带宽） | 68~99 元/年 |
| 域名（`.top`/`.cn` 首年） | 20~40 元/年 |
| SSL 证书 | 免费（腾讯云 DV / Let's Encrypt） |
| 数据库 | **0** —— 直接退回 H2 文件库 |
| **合计** | **约 130 元/年** |

⚠️ 轻量服务器的 68~99 元是**首年活动价**，续费通常翻 2~4 倍。买之前先看续费价。

**要改的东西（都不难，但必须顺序对）：**

1. **域名备案** —— 15~20 工作日，且腾讯云要求服务器购买时长 ≥ 3 个月（轻量年付满足）
2. `miniprogram/utils/api.js`：
   - `USE_CLOUD` 改成 `false`
   - `LOCAL_BASE` 现在是写死的 `http://localhost:8080`，要改成从 `config.local.js` 读你的 `https://域名`
3. mp 后台 → 开发管理 → 开发设置 → **服务器域名 → request 合法域名**，加上你的 `https://域名`
4. 服务器上：装 JDK 17 + Nginx/Caddy 反代 8080 + 配证书
5. **数据库换成 H2 文件库** —— 云托管 MySQL 只有内网地址（`10.27.101.100`），
   外部服务器根本连不上。`application.yml` 里的默认值就是 H2，删掉那几个
   `SPRING_DATASOURCE_*` 环境变量即可。记得把 `server/data/` 纳入备份
6. `SyncService` 可以改回同步 —— `wx.request` 超时 60s，不再是 15s。
   但保持异步也不影响，不用动

**换过去之后云托管环境可以注销**，但要留意：3 个月免费额度**只送首个环境**，
注销后再建新环境就没有了。所以别急着注销，先跑稳再处理。

### 13.5 哪些省钱动作是「白捡的」（本次已经全部落地）

以下改动**不花一分钱、不影响体验**，已经改完并编译验证通过：

| 文件 | 改动 | 省什么 |
| --- | --- | --- |
| `application.yml` | HikariCP `minimum-idle: 0`（池 5、空闲 1 分钟回收） | 让 MySQL 有机会真的自动暂停 → **可能省掉 0~246 元/月** |
| `application.yml` | H2 console 默认关闭 | 堵掉一个 RCE 面（H2 是 `runtimeOnly` 依赖，生产镜像里确实有它） |
| `application.yml` | 日志 `com.campus.mini` DEBUG → `${CAMPUS_LOG_LEVEL:INFO}` | 0.25 核的机器上，DEBUG 日志的 CPU 开销是实打实的 |
| `application.yml` | `spring.sql.init.mode`: `always` → `embedded` | 生产不再每次启动跑 schema.sql。它是对库的写操作，会**重置「自动暂停」的 10 分钟计时** |
| `application.yml` | Tomcat `threads.max: 25` / `min-spare: 5` | 默认 200 线程是给通用网站的；线程栈按 `Xss512k` 算也省 12 MB |
| `Dockerfile`（**两份都改了**） | `MaxRAMPercentage` 75 → 50，加 `MaxMetaspaceSize=128m`、`Xss512k`、`UseSerialGC`、`ExitOnOutOfMemoryError` | 让 0.5 GiB 容器装得下（实测 214 MB），并且 OOM 时能看出症状 |

本地验证方式（以后改 JVM 参数可以照做）：

```powershell
cd D:\syq\campus-mini\server
# -XX:MaxRAM=512m 让 JVM 按 512MB 容器算堆；NativeMemoryTracking 用来读真实占用
java -XX:MaxRAM=512m -XX:MaxRAMPercentage=50 -XX:MaxMetaspaceSize=128m -Xss512k `
     -XX:+UseSerialGC -XX:NativeMemoryTracking=summary `
     -jar build\libs\app.jar --server.port=8099
# 另开一个窗口：
jps -lv                                  # 找 pid
jcmd <pid> VM.native_memory summary      # 看 Total committed
```

> ⚠️ 本机跑的时候**必须加 `--server.port=8099`**：`application.yml` 里是
> `port: ${PORT:8080}`，而本机环境里存在一个 `PORT` 环境变量，会把端口带偏。
> 云托管里我们自己显式设了 `PORT=8080`，没这个问题。

**环境变量端只剩一件事**：控制台里那个 `SPRING_SQL_INIT_MODE=always` 会**覆盖**
`application.yml` 的新默认值（`embedded`），把它删掉或改成 `never`。

构建费（0.05 元/分钟 × 每次推送 3~5 分钟 ≈ 0.2 元）想省到 0 得改用「镜像拉取」发布，
本机又没装 Docker —— **不值得为这两毛钱折腾**。
| 日志 `com.campus.mini` DEBUG → INFO | 0.5 核的机器上，DEBUG 日志的 CPU 开销是实打实的 |

### 13.6 上线后第一周盯这三个数字

在「云托管控制台 → 费用中心 / 资源监控」里看：

1. **MySQL 算力日用量** —— 如果天天接近 24 个 CCU·时，说明自动暂停没生效，
   回来关掉连接池或换方案 C
2. **实例数** —— 应该稳定在 1。如果频繁上下浮动，说明有东西在反复触发扩缩容
3. **容器 CPU 日用量** —— 0.25 核规格应该稳定在 `0.25 × 24 = 6` 核·时左右；
   明显更高就是有异常流量
4. **服务日志里有没有反复出现 Spring Boot 启动横幅** —— 有就是被 OOM Kill，
   把规格退到 0.5 核 1G

### 13.7 控制台还剩 5 项（代码改不到这些）

代码和 Dockerfile 那边已经全部落地，下面这些只能在 cloud.weixin.qq.com 里改：

| # | 位置 | 改成 | 为什么 |
| --- | --- | --- | --- |
| 1 | 服务 → 版本配置 → **实例规格** | **0.25 核 0.5G** | 21.4 元/月，实测 214 MB 跑得下 |
| 2 | 版本配置 → **实例副本数最小值** | **1** | 保 `SyncService` 后台线程不被缩容杀掉（第八节） |
| 3 | 版本配置 → **实例副本数最大值** | **1** | 费用精确封顶。默认上限 50，被刷就是每天上千元 |
| 4 | **MySQL → 设置** | **自动暂停：开**；有 CCU 上限就设到最小 | 这是最大的一笔，0 vs 246 元/月 |
| 5 | 服务 → **环境变量** | 删掉 `SPRING_SQL_INIT_MODE`（或改成 `never`） | 它现在是 `always`，会**覆盖** yml 里的新默认值 `embedded` |

改完记得点「发布」，新版配置才对运行版本生效。

> 第 4 项是唯一我无法从文档确认颗粒度的一项 —— 云托管 MySQL 的算法规格是
> "自动弹性伸缩，无需手动指定"，不同环境下能不能设 CCU 上限要看控制台实际有没有这个选项。
> 如果只能开/关「自动暂停」，那就把开关打开，然后靠 13.6 的日用量观察。

**改完怎么算成功了：** 费用中心里，
MySQL 算力日用量贴近 0、容器 CPU 日用量贴近 6 核·时、实例数恒定 1。
三项都对上，月账单就是 26 元左右。
