# 架构

## 全景

```
┌──────────────────────────────────────────────────────┐
│  原生微信小程序                                       │
│  首页 / 课表 / 工具 / 我的                            │
└───────────────────────┬──────────────────────────────┘
                        │ wx.cloud.callContainer  (免域名、免备案)
                        │ 或 wx.request（本地联调）
┌───────────────────────▼──────────────────────────────┐
│  Spring Boot 3  (微信云托管 / Docker)                 │
│                                                      │
│  ┌────────────────────────────────────────────────┐  │
│  │  CampusAdapter  ← 接口，只有读取能力            │  │
│  │   ├─ ChaoxingAdapter   超星学习通 (课表/课程)   │  │
│  │   ├─ ManualAdapter     手动导入兜底             │  │
│  │   └─ (将来) JwxtAdapter / CardAdapter / ...     │  │
│  └────────────────────────────────────────────────┘  │
│          ▲                                           │
│   AdapterRegistry   ← Spring 构造器注入，自动注册      │
│                                                      │
│  CredentialVault    AES-GCM 加密存凭据                │
│  SyncService        异步同步 + 任务状态机              │
│  ScheduleService    周次计算 + 课表聚合                │
└───────────────────────┬──────────────────────────────┘
                        │
              ┌─────────▼─────────┐
              │   MySQL (云托管)   │
              └───────────────────┘
```

## 一、适配器模式（唯一从 MoocPass 学到的东西）

```java
public interface CampusAdapter {
    String code();                        // "chaoxing"
    String name();                        // "超星学习通"
    LoginMode loginMode();                // 决定前端表单长什么样
    Set<Capability> capabilities();       // 声明能读什么
    VerifyResult verify(Credential c);    // 验证凭据是否有效
    List<Course> fetchCourses(Binding b);
    List<CourseSession> fetchSessions(Binding b);   // 课表
}
```

`AdapterRegistry` 用 Spring 的集合注入自动收集所有 `@Component` 实现：

```java
public AdapterRegistry(List<CampusAdapter> adapters) {
    for (CampusAdapter a : adapters) {
        map.put(a.code(), a);
    }
}
```

**加一个新平台 = 写一个 `@Component` 实现 + 建一张能力卡片**，不动任何其他代码。这就是 MoocPass 的 `PlatformAdapterRegistry` 干的事，那部分设计是对的。

### 能力声明

MoocPass 的 `PlatformCatalog.info()` 返回 `enabled` / `status` / `supportsResume` / `resourceTypes`，前端据此渲染"哪些功能可用"。这个思路很好，照搬——你截图里的「服务绑定中心 0/3 已连接」就是这个。

区别在于我们的 `Capability` 枚举：

```java
public enum Capability {
    COURSE_LIST,    // 课程列表
    SCHEDULE,       // 课表
    GRADE,          // 成绩
    EXAM,           // 考试安排
    CARD_BALANCE,   // 一卡通余额
    ELECTRICITY,    // 宿舍用电
    RUN_RECORD      // 校园跑记录
}
```

**每一个都是"读"。** 没有 `VIDEO_PROGRESS`、没有 `QUIZ_SUBMIT`、没有 `CHECK_IN`，而且不会有。这是刻意的：边界写在类型里，不靠自觉。

## 二、凭据处理

要支持"课表自动更新"（静默同步），就必须能重复登录，所以密码必须**可逆加密**存储，不能只存哈希。

```
用户密码 ──AES-GCM(主密钥 kw, 每条独立 IV)──> ciphertext + iv  ──> DB
```

- 主密钥来自环境变量 `CAMPUS_MASTER_KEY`（32 字节 Base64），**绝不进代码库**
- 每条记录独立随机 IV，同一密码密文不同
- `CredentialVault` 是唯一接触明文的类

> MoocPass 的 `SecretVault` + `Crypto` 也是这么做的，这部分设计可以参考（代码不要抄，GPL-3.0）。

**说清楚风险**：只要服务端能解密你的密码，运维方（也就是你）技术上就能拿到它。自己用没问题；如果给别人用，你得在隐私政策里写明，并且不要存明文日志。这是这类应用绕不开的取舍——另一种选择是只存 Cookie/Token 并接受过期后要重新绑定。

## 三、同步：15 秒硬约束

云托管的 `CallContainer` 超时 **≤ 15 秒**（官方文档明确限制）。而爬教务系统/超星动辄十几秒。所以：

```
小程序               后端
  │  POST /api/sync/chaoxing
  │─────────────────────> 建 sync_task(status=PENDING)，立即返回 taskId
  │<─────────────────────   （< 1s，绝不超时）
  │
  │  GET /api/sync/tasks/{id}     ← 每 1.5s 轮询一次
  │─────────────────────> 返回 status + progress + message
  │<─────────────────────
  │  ... 直到 status = SUCCESS / FAILED
```

后端用 `@Async` 线程池执行真正的抓取。这就是 MoocPass 的 `TaskQueue` / `TaskWorker` / `TaskLogger` 那套东西。

⚠️ 云托管注意：**容器不要在处理请求之外自己起后台线程**（官方文档第 11 条），否则响应可能提前返回导致异步任务被中断。所以同步任务要设计成可重入、幂等，并落库状态。

## 四、缓存优先

**不要在用户打开页面时才去爬超星。** 慢、且高频请求会触发风控。

正确做法：
1. 绑定账号时同步一次
2. 定时触发（云托管的「定时触发」能力）每天凌晨静默同步
3. 前端永远读自己的 MySQL，只有用户手动点「立即同步」才去抓

MoocPass 的 `RetentionJob` 是同类设计。

## 五、课表模型

超星/教务系统给的是**一堆课程 + 上课时间文本**，前端要的是**周次 × 星期的网格**。转换在 `ScheduleService`：

```
课程原始数据                     课表网格
  课程名 / 教师 / 教室            ┌────┬────┬────┬────┬────┬────┬────┐
  周几 / 第几节 / 周次范围   ──>  │    │周一│周二│周三│周四│周五│周六│周日│
  "1-16周" / "单周" / "3-5,8周"   ├────┼────┼────┼────┼────┼────┼────┤
                                  │ 1-2│ 高数    │     │ 英语    │    │
                                  │ 3-4│     │ 线代    │     │ 物理    │
```

**周次计算是课表最容易做错的地方。** `WeekCalculator` 需要：

- 学期第一周周一的日期（`campus.term.start-date`）
- 总周数（`campus.term.total-weeks`）
- 今天是第几周：`(today - startDate) / 7 + 1`
- 单双周解析：`"1-16周(单)"` → `{1,3,5,...,15}`

这个必须按**你学校的校历**配置，否则全错。`application.yml` 里现在是占位的示例值。

## 六、加一个新平台要做什么

以"成绩查询"为例：

1. `Capability.GRADE` 已经在了
2. 写 `JwxtAdapter implements CampusAdapter`，`capabilities()` 返回 `Set.of(COURSE_LIST, SCHEDULE, GRADE)`
3. 实现 `fetchGrades()`（需要在接口里加一个 `default` 方法，默认抛"不支持"）
4. 前端 `tools.js` 里加一张卡片
5. 完事。注册表、权限、加密、同步队列全部复用

## 七、明确不做的扩展方向

如果将来有人想往这个库里加：

- 视频进度上报
- 自动答题 / 题库
- 签到代签
- 校园跑代跑 / 虚拟定位
- 验证码识别用于绕过风控
- 代理池换 IP

——请先读一遍本文件第一节，然后看 `Capability` 枚举。这些能力**不在设计里**。加它们不是"扩展功能"，是换一个项目。前面提到的 MoocPass 及其 2021 年辽宁朝阳案的背景，说明了这类项目的实际下场（[中国长安网报道](http://chinapeace.gov.cn/chinapeace/c100042/2021-09/13/content_12535955.shtml)）。
