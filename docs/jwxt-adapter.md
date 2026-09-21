# 加一个新的学校 / 新平台适配器

这份文档用「移动教务」类厂商 SaaS 作为**完整worked example**，讲清怎么把一个新的教务系统
接进本项目。方法对所有平台通用。

> **本仓库不含任何具体学校信息。** 地址、密钥、开关都在
> `application-local.yml`（已 gitignore）。仓库里只有配置驱动的通用骨架。
> 你照着这份文档给自己的学校接一套即可。

---

## 一、先判断属于哪一类

| 类型 | 特征 | 接法 |
| --- | --- | --- |
| 老式服务端渲染 | URL 里带 `.do` / `xtgl`，返回 HTML 表格 | Jsoup 解析 HTML |
| SPA + JSON API | hash 路由（`#/login`），前端是 Vue/React | **本文档的方式**：找 JSON 接口 |
| 有开放平台/API 文档 | 官方给了文档和密钥 | 直接按文档接 |
| 只有 App、无 Web | 抓包看 App 的请求 | 同上，但要用抓包工具 |

**先找有没有 Web 版。** 浏览器 F12 比抓 App 包容易十倍。

---

## 二、找出 API 地址（三个地方按顺序找）

### 1. `serverconfig.json` —— 先看这个，常常一步到位

很多这类系统把部署配置放在一个**公开静态文件**里：

```
GET {前端地址}/serverconfig.json
```

典型内容：

```json
{
  "ApiUrl": "http://<host>:<port>/<ctx>",
  "title": "<学校名>",
  "SelectUrl": "http://<内网IP>/jsxsd",
  "schoolCode": "<学校代码>"
}
```

**注意区分 `ApiUrl` 和 `SelectUrl`：** 前者是主 API（可能在公网），后者常常指向
**校内网的正方教务系统**（`jsxsd` 是正方标准路径，私有 IP 只能在校园网访问）。

> **这决定了架构可行性**：只有公网可达的地址，云托管后端才能同步。
> 内网地址只能校园网内用。

### 2. 前端 bundle 里的构建期常量

搜 `API_ROOT` / `baseURL` / `axios.create`，常常能看到厂商云的默认地址：

```js
API_ROOT:     "http://<厂商云IP>:8001/xxx",
API_ROOT_PRO: "http://<厂商云IP>:8001/h5_Backstage",
API_ROOT_SEL: "http://<厂商云IP>:8001/xxx_jsxsd"
```

运行时会被 `serverconfig.json` 覆盖。

### 3. 还有一个「学校地址分发」接口

多租户系统常有一个 `POST /getSchoolUrL`，用学校代码换取该学校的真实地址，
前端拿到后塞进 `sessionStorage`。如果你要支持多个学校，需要模拟这一步。

---

## 三、搞清认证方式（别想当然）

**不要假设是 `Authorization` 或 Cookie。** 直接去请求拦截器里看：

```js
axios.interceptors.request.use(function (t) {
  t.headers.token = sessionStorage.getItem("Token");   // ← 就叫 token
  return t;
});
```

常见的几种：`token` 自定义头、`Authorization: Bearer`、Cookie、签名参数。
**以拦截器为准。**

### ★ 成功码不一定是 0

```js
if (1 != res.code || !res.data || !res.data.token) { /* 失败 */ }
```

这个系统用 **`code === 1`** 表示成功。写成 `== 0` 会全程失败且很难查。
做成配置项（`success-code`），别硬编码。

### ★ 参数在 query 里，不在 body 里

```js
// axios 的 params 进 URL，data 进 body —— 差一个字就 401
request({ url: "/login", method: "post", params: t })
```

看到 `params` 就拼 query string；看到 `data` 才进 body。

---

## 四、密码/签名：先找常量，再动手实现

如果登录要对密码做编码，**先把算法和它依赖的常量一起挖出来**。

挖的时候注意一个**压缩代码的坑**：

> webpack 压缩后，**不同模块会用相同的短变量名**。
> 我在同一个 bundle 里见过两个 `Uw`：一个是登录用的 `Uw.encrypt`，
> 另一个是打卡用的 `Uw.encryption`（完全不同的算法）。
> **认函数体，不要认变量名。**
>
> 同理，同一个短名常量也会有多个值。确认某个常量时，
> 搜 `<名字>\s*=\s*"` 看它在本 bundle 里有几处赋值 —— 只有一处才敢用。

### ★★ 必须用对照实验验证实现，别靠读代码

**血的教训**：CryptoJS 的 `AES.encrypt(...).toString()`
返回的是 **Base64**，不是十六进制。我按 hex 实现，**全错**。
（裸 `.toString()` 走 OpenSSL 格式化器 → Base64；要 hex 得写 `.ciphertext.toString()`。）

**正确的验证流程**（本项目的做法，可复用）：

1. 用 Node 加载**原始 bundle 里未经修改的压缩函数**，配齐它依赖的库（如 `crypto-js`），
   跑出**基准值**
2. 用自己的语言（Java）独立实现一遍
3. 两边对同一批输入比对，**必须逐字节相同**
4. 测试用例要覆盖：纯数字、含符号、**中文**、空串、含逗号/引号/反斜杠

第 1 步是关键 —— 它验证的是**你读代码读得对不对**，而第 2、3 步只能验证**你写得对不对**。
只做 2、3 步，读错了会一起错。

本项目 `MobileJwCrypto` 有完整的算法和踩坑记录，可作参考。

---

## 五、写适配器

只要实现 `CampusAdapter` 一个接口：

```java
@Component
public class XxxAdapter implements CampusAdapter {
    public static final String CODE = "xxx";     // 稳定标识，前端靠它拼请求

    public String code()   { return CODE; }
    public String name()   { return "某某教务"; }
    public LoginMode loginMode() { return LoginMode.PASSWORD; }
    public Set<Capability> capabilities() { return Set.of(Capability.SCHEDULE, Capability.GRADE); }

    public VerifyResult verify(Credential c) { /* 登录，成功/失败 */ }
    public FetchResult  fetch(Credential c)  { /* 登录 + 抓课表 */ }
}
```

`AdapterRegistry` 用 Spring 集合注入**自动发现**它 —— 不用改注册表，
不用改 `PlatformCatalog`，前端自动出现一张新卡片。

**所有端点、密钥走 `CampusProperties` 配置**，别硬编码。

### 解析响应：不要硬编码 JSON 路径

各校版本结构不一样。**递归找出"像一行记录"的对象**更稳：

```java
// 判据：该对象的直接字段里，同时能匹配到 课程名 + 星期 + 节次 三类候选字段
if (name != null && day != null && sections != null) { /* 当一条记录 */ }
```

字段名候选多列几个（`kcmc`/`courseName`/`kcname`…）。宁可多试，不要瞎猜路径。

### 格式兼容的两个实用点

- **节次**可能是紧凑写法：`0102` = 第 1-2 节（两位一节）
- **周次**可能是位图：`11111111111111110000` = 第 1-16 周

两种都要处理。`WeekTextParser` 处理区间/单双周，位图和紧凑节次在适配器里预处理。

---

## 六、调试字段映射（不用猜）

打开调试接口，直接看平台原始响应：

```yaml
campus:
  debug-endpoints: true      # ★ 联调完必须关掉
```

```
GET /api/debug/raw/{adapterCode}
```

它复用**已绑定**的凭据（不从请求参数取密码 —— 那样密码会进 URL 和访问日志），
所以顺序是：**先绑定成功，再探测**。

> ⚠️ 它返回未裁剪的平台数据，含个人信息。生产环境绝不能开。

字段名确认后，直接改适配器里的候选数组，或者把接口需要的参数写进
`curriculum-params`（不用改代码就能试）。

---

## 七、这个项目不做什么

写适配器时，有几条边界是**设计上就锁死的**（见 `Capability`）：

| 不做 | 为什么 |
| --- | --- |
| 验证码识别 | 那是绕过平台的风控措施 |
| 选课 / 退课 / 报名 | `Capability` 里没有写操作，且这是别人的业务系统 |
| 提交作业 / 答题 | 伪造学习记录 |
| 视频进度上报 | 同上 |
| 代理池规避风控 | 同上 |

**适配器只能"读"。** 上面那些不是"还没实现"，是枚举里没有对应的值。

另外：**自动化访问你自己的账号仍然可能违反学校的服务条款**。
这是你的判断，但你应该知道这一点。
