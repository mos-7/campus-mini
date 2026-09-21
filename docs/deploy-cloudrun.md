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

### 方式 B：关联代码仓库（推 Git 自动构建）

把仓库连到云托管，之后 push 即部署。

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

## 十二、成本

按量计费。最小副本设为 1 的情况下，一个 0.25 核 512MB 的常驻实例大约是
**每月几块到十几块钱**。流量费另算，但校园工具的量级可以忽略。

想省钱就把最小副本设回 0 —— 代价是同步任务随时可能被杀（我们的僵尸任务清理
能兜住，但用户会看到"任务中断"）。
