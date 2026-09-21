# 校园盒子 CampusMate

一个类「美汁园」的校园工具微信小程序。**原生小程序 + Spring Boot 3**，部署到**微信云托管**（不需要买服务器、不需要备案域名）。

---

## 这个项目是什么

首页（今日课程 + 快捷入口 + 公告）、课表、工具、我的（服务绑定中心）四个 tab。

数据来源是**你自己的账号**：课表、成绩、一卡通余额、宿舍用电、校园跑记录——都是你本人的信息，属于正规的"校园信息聚合"。

## 这个项目明确不是什么

这是一个**只读**应用。下面的东西在架构层面就不存在，不是"暂未实现"，是**类型系统里没有这个能力**：

| 不做 | 为什么 |
| --- | --- |
| 网课视频进度上报 / 挂机 | 伪造学习记录，等同刷课 |
| 自动答题 / 题库 / 签到代签 | 同上 |
| 校园跑代跑 / 刷步 / 虚拟定位 | 伪造体育成绩，同一类问题 |
| 绕过验证码 / 破解字体混淆 / 代理换 IP 规避风控 | 破解平台的防护措施 |

具体做法：`Capability` 枚举（`adapter/Capability.java`）里只有 `SCHEDULE`、`GRADE`、`CARD_BALANCE` 这类**读取**能力，没有任何写入能力的枚举值。`CampusAdapter` 上的 `readOnly()` 恒为 `true`。想加上述任何一项，你得先改这个枚举 —— 那时候你会非常清楚自己在干什么。

### 关于架构来源

适配器 + 注册表这套结构（接口 + Spring 集合注入自动注册 + 声明式能力开关）本身是通用的插件化设计。
本项目在动手前看过开源社区里已有的同类项目，结构上参考了
[`Xiamo-vip/MoocPass`](https://github.com/Xiamo-vip/MoocPass) 的 `PlatformAdapterRegistry` 写法。

**只取结构，不取其余。** 那个项目还附带网课挂机、AI 代答、验证码识别、字体混淆解密、代理换 IP 等功能
—— 正是本项目上表里明确排除的东西。本项目没有复制它的任何源码。

## 许可

[GPL-3.0](LICENSE)。

选它只是一个明确的"改了也要开源"的态度，不代表本项目与任何既有项目有代码关联。

## 目录结构

```
campus-mini/
├─ miniprogram/                 原生微信小程序
│  ├─ app.js / app.json / app.wxss
│  ├─ utils/api.js              统一请求封装（云托管 callContainer）
│  ├─ utils/date.js             周次 / 日期计算（课表核心）
│  └─ pages/
│     ├─ index/                 首页：今日课程 + 快捷入口 + 公告
│     ├─ schedule/              课表：周次切换 + 网格
│     ├─ tools/                 工具：功能入口网格
│     └─ profile/               我的：服务绑定中心 + 设置
├─ server/                      Spring Boot 3 后端
│  ├─ Dockerfile                云托管构建用
│  └─ src/main/java/com/campus/mini/
│     ├─ adapter/               ★ 适配器框架（核心）
│     ├─ binding/               凭据加密 + 账号绑定
│     ├─ schedule/              课表聚合 + 周次计算
│     ├─ sync/                  异步同步任务
│     ├─ web/                   REST API
│     └─ config/
└─ docs/
   ├─ architecture.md           架构与适配器设计
   ├─ chaoxing.md               超星接入：边界 + 抓包验证步骤
   ├─ jwxt-adapter.md           ★ 怎么接一个新平台（逆向方法 + 踩坑记录）
   ├─ deploy-cloudrun.md        云托管部署（免备案）
   └─ publish-miniprogram.md    ★ 发布微信小程序：注册/备案/审核/避坑
```

## 共享约定

改代码时这几个值必须保持一致，否则前后端会对不上：

| 项 | 值 |
| --- | --- |
| Java 包名 | `com.campus.mini` |
| 适配器 code | `chaoxing`（超星）、`mobilejw`（移动教务类厂商 SaaS）、`manual`（手动导入兜底） |
| API 前缀 | `/api` |
| 响应信封 | `{ "code": 0, "message": "ok", "data": ... }`，`code=0` 为成功 |
| 鉴权 | `Authorization: Bearer <jwt>` |
| 云托管服务名 | `campus-api` |
| 数据库表 | `app_user` / `binding` / `course` / `course_session` / `sync_task` / `announcement` |

## 快速开始

### 后端（本地）

需要 JDK 17。

```powershell
cd server
# 方式一：用 IDEA 打开 server 目录，它会自动生成 gradle wrapper，然后
.\gradlew.bat bootRun

# 方式二：本机装了 gradle（或直接用 Dockerfile）
gradle bootRun
```

跑在 `http://localhost:8080`，用 H2 内存库，启动时自动建表并灌入一份**演示课表**，
所以小程序一打开就有课表可看。H2 控制台在 `http://localhost:8080/h2-console`。

> **★ 先改校历。** `application.yml` 里的 `campus.term.start-date` 是第一周**周一**的日期，
> 现在是占位值。不改的话周次全是错的，首页会显示「不在学期内」。

### 小程序

1. 微信开发者工具 → 导入项目 → 选 `miniprogram/` 目录
2. 填你自己的 AppID（`project.config.json` 里的 `appid` 是占位符）
3. 本地联调：把 `miniprogram/utils/api.js` 里的 `USE_CLOUD` 设为 `false`，请求打到 `http://localhost:8080`
4. 记得在开发者工具里勾「不校验合法域名」

### 上线

见 [`docs/deploy-cloudrun.md`](docs/deploy-cloudrun.md)。核心结论：**微信云托管用 `CallContainer` 调用，不需要域名、不需要备案**。

## 现状

**已在本地实测跑通**（JDK 17 + Gradle 8.10.2，`gradle build` 通过，API 全部返回正确）：

- [x] 适配器框架（接口 + 注册表 + 能力枚举）
- [x] 凭据加密（AES-GCM + 主密钥）
- [x] 账号绑定：先验证凭据、通过了才加密落库
- [x] 课表 API + 周次计算（实测 `1-16周(单)` → `1,3,5,7,9,11,13,15` 正确）
- [x] 异步同步任务（taskId 立即返回 + 轮询，绕开云托管 15s 超时）
- [x] 手动导入兜底适配器（零风控风险，先能用）
- [x] 小程序四个 tab（首页 / 课表 / 工具 / 我的）
- [x] 登录鉴权 + 401 拦截（实测未带 token 被拦）

未完成：

- [ ] **超星适配器：接口待抓包确认**（见 `docs/chaoxing.md`）
- [x] **移动教务类厂商 SaaS 适配器（`mobilejw`）** —— 登录链路已端到端验证：
      口令编码与原前端 JS **逐字节一致**（7 个用例含中文/特殊字符），
      实测能连到学校服务器并正确解析出业务错误。课表字段映射待用真实账号确认。
      接入方法见 [`docs/jwxt-adapter.md`](docs/jwxt-adapter.md)
- [ ] 成绩查询适配器
- [ ] 校园跑记录适配器
- [ ] 订阅消息提醒（上课前提醒）
- [ ] 作息时刻设置（需要先能拿到上下课时间）

> **关于 `mobilejw` 适配器**：端点、密钥、开关全部走配置，取值放在
> `application-local.yml`（已在 `.gitignore`）。公开仓库里只有配置驱动的通用骨架，
> **不含任何具体学校信息**。要接你自己的学校，照 `docs/jwxt-adapter.md` 走一遍即可。

## 下一步

超星那部分没法凭空写 —— 它的接口需要你用**自己的账号**抓包确认一遍。
`docs/chaoxing.md` 里写好了具体步骤和三个待确认点（登录接口 / password 是否加密 / 课程列表结构）。

另外提醒一句：**超星的课程列表接口通常不返回上课时间地点**，所以真正的课表大概率要从你学校的
教务系统取。先用手动导入把界面跑通，再决定啃哪个接口。

