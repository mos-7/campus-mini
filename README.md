# 校园盒子 CampusMate

这是一个基于MoocPass改版来究极丐版程序

---

## 这个项目是什么

首页（今日课程 + 快捷入口 + 公告）、课表、工具、我的（服务绑定中心）四个 tab。

数据来源是**你自己的账号**：课表、成绩、一卡通余额、宿舍用电、校园跑记录——都是你本人的信息，属于正规的"校园信息聚合"。


## 许可

[GPL-3.0](LICENSE)。


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

**已完整跑通，后端部署在微信云托管，小程序通过 `callContainer` 直连** ——
真实教务系统的课表能正确拉取并渲染。剩下的只有平台侧流程（备案审核）。

已验证：

- [x] 适配器框架（接口 + 注册表 + 能力枚举）
- [x] 凭据加密（AES-GCM + 主密钥，密钥走环境变量，绝不入库）
- [x] 账号绑定：先验证凭据、通过了才加密落库
- [x] 课表 API + 周次计算（实测 `1-16周(单)` → `1,3,5,7,9,11,13,15`）
- [x] 异步同步任务（taskId 立即返回 + 轮询，绕开云托管 15s 硬超时）
- [x] 解绑时一并清理已同步数据
- [x] 手动导入兜底适配器（零风控风险，不用交出账号）
- [x] **`mobilejw` 适配器** —— 用真实账号端到端验证通过：
      口令编码与原前端 JS **逐字节一致**（7 个用例含中文/特殊字符）；
      逐周抓取的课表与实际选课一致（含只在部分周出现的课）
- [x] 7 个单元测试（用真实响应结构做夹具，见 `MobileJwParserTest`）
- [x] 小程序四个 tab + 隐私政策页
- [x] 云端部署 + 启动自检日志（`StartupDiagnostics`，打印解析后的实际配置）

未完成 / 待办：

- [ ] **微信小程序备案**（审核中）—— 通过后才能提交审核发布
- [ ] 超星适配器：接口待抓包确认，见 [`docs/chaoxing.md`](docs/chaoxing.md)
      （当前用不到 —— 课表走教务系统那条路）
- [ ] 成绩查询（后端 `Capability.GRADE` 已预留，接口 `gradeList` / `student/termGPA`）
- [ ] 考试安排（接口 `student/examinationArrangement`）
- [ ] 校园跑记录查询（只查记录，不做代跑）
- [ ] 订阅消息提醒（上课前提醒）
- [ ] 作息时刻设置（需要先拿到上下课时间）
- [ ] 课表页面精装修

> **关于 `mobilejw` 适配器**：端点、密钥、开关全部走配置，取值放在
> `application-local.yml`（已 gitignore）。公开仓库里只有配置驱动的通用骨架，
> **不含任何具体学校信息**。要接你自己的学校，照 [`docs/jwxt-adapter.md`](docs/jwxt-adapter.md)
> 走一遍即可 —— 那份文档里还记着几个静默失败、极难查的坑。

## 下一步

**代码部分已完工。** 剩下的是平台侧流程：

1. **等小程序备案通过** —— 这是唯一在等别人的环节，1~20 个工作日
2. 备案通过后：mp 后台「版本管理」→ **提交审核** → 审核通过 → **发布**
3. 提审时记得：
   - 把 `/pages/privacy/privacy` 也填进「功能页面」
   - 审核备注里说明「需绑定用户本人的学校账号后才能看到课表，未绑定时显示示例数据」

要加新功能，看 [`docs/jwxt-adapter.md`](docs/jwxt-adapter.md) —— 里面讲了完整套路
（找 API → 验证编码 → 写适配器 → 调字段映射），以及踩过的坑。

