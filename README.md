# 小粥历

一个校园工具微信小程序（备案名 **小粥历**，取意"每周的日程"，"粥"与"周"谐音）。**原生小程序 + Spring Boot 3**，部署到**微信云托管**（不需要买服务器、不需要备案域名）。

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

## 关于项目名

仓库名和内部包名仍是 `campus-mini` / `com.campus.mini`（**不要改** —— 改了会牵动
云托管服务名、构建路径和数据库配置），但**对外名称是备案用的 `小粥历`**。

原名 `校园盒子`、`粥粥源` 都在备案阶段被驳回过。规则和完整经过见
[`docs/publish-miniprogram.md`](docs/publish-miniprogram.md) 第一节「决定 3：名称」。
一句话概括：个人主体的名称必须**同时**满足三条 —— 像个人起的（不涉企业名义）、
不碰前置审批领域（尤其教育/校外培训）、且能看出用途（否则判"名称与服务内容无关联"）。
`小粥历` 的 **"粥" 与 "周" 谐音**，正好由这一个字承担了第三条。

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

跑在 `http://localhost:8080`，数据落在**启动目录**下的 H2 文件库 `data/campus.mv.db`，
启动时灌入一份**演示课表**，所以小程序一打开就有课表可看。

> ★ **首次起库（或删掉 `data/` 之后重建）必须加一个参数：**
> ```powershell
> gradle bootRun --args='--spring.sql.init.mode=always'
> ```
> 因为 `spring.sql.init.mode` 默认是 `embedded`，而 Spring **不把 `jdbc:h2:file:`
> 当作嵌入式数据库** —— 不加这个参数，`schema.sql` 一次都不执行，库里一张表都没有。
> 症状特别隐蔽：**App 照样启动成功、`/api/health` 返回 UP、演示课表也在**，
> 只有一行容易被忽略的告警 `清理僵尸同步任务失败：... bad SQL grammar`
> （真实原因是 `sync_task` 表不存在）。日常重启不需要这个参数。
> 详见 `application.yml` 里的实测对照。

> H2 控制台**默认关闭**（`spring.h2.console.enabled=false`，生产安全考虑）。
> 本地要用就加 `--spring.h2.console.enabled=true`。

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

