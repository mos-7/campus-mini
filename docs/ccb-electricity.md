# 电费查询：建行 E码通 的"会话接力"方案

学校电费在**建行 E码通**平台上（`app.xiaoyuan.ccb.com`）。它的登录链绑定建行
小程序自己的微信身份（一次性 `ccbParam`），我们的小程序后端无法自己登录——
但可以"接力"用户自己产生的会话。本文记录协议结论和产品形态。

## 一、协议（2026-10 抓包 + 官方 H5 源码逆向，全部实测）

所有业务都是**明文表单** POST 到 `https://app.xiaoyuan.ccb.com/LHECISM/B2CMainPlat_00`：

```
BRANCHID=555000000
SERVLET_NAME=B2CMainPlat_00
CCB_IBSVersion=V6
PT_STYLE=10
TXCODE=<业务码>
<业务参数...>
USERID=<会话用户ID>
SKEY=<会话密钥>
```

无报文加密，无签名。响应是 `{status, msg, data, ERRORCODE, ERRORMSG}`，
`status=="0"` 为成功。

关键业务码：

| TXCODE | 用途 | 关键参数 | 返回 |
|---|---|---|---|
| `DZ0392` | 查已绑定宿舍 | `payType=elec` | roomid/room/areaid/areaname/buildingid/buildingname/floorid/floorname |
| `YJF006` | 校区→楼栋→楼层→房间 四级列表 | `PayType=elecdetails` + 上级参数 | `roomdata` 列表 |
| `YJF004` | **查余额** | **完整四级链** + `area`(=areaid) | `mainFare` 剩余金额(元)、`bal` 剩余电量(度)、`subsidy_*` 补助、`account` |

> ⚠ YJF004 **必须带全四级链和 area 字段**。只发 roomid 会报
> `YBLA3221K998 第三方服务异常`。官方页面在缴费选择器里也是全链提交的。

### 会话模型

- 登录：`GET /LHECISM/CCBYXxcxLgServiceReqServlet?ccbParam=<一次性票据>` →
  响应体 `dl01Json` 里有 `SKEY`（6 位）+ `USERID`，Set-Cookie 带 `CCBIBS1` 等。
- `ccbParam` 由建行小程序的微信身份换出，**有效期只有几分钟**，一次性。
- 会话（SKEY/USERID/Cookie）**支持同一请求连续重复调用**（官方 H5 也这么用）。
- 会话寿命：空闲约 **30 分钟**过期。过期表现：
  - `0130Z1108006 该页面为历史页面`（被官方小程序用完的会话一律这样）
  - `0130Z1108007 暂时未能处理您的请求，请重新登录`（自然过期的会话）

## 二、产品形态：会话接力

后端不登录，而是**接收用户自己截留的会话**：

1. 用户在电脑上：Reqable 启用断点规则 `*CCBYXxcxLgServiceReqServlet*`（只拦请求）→
   微信打开"校园e码通"小程序 → 登录请求被拦 → 复制完整 URL 后**选择放弃**
   （小程序会显示加载失败，这就是我们要的：ccbParam 未被消费）。
2. 运行 `node relay.js`（在 `D:\tools\ccb-probe`，需 `npm i qrcode-terminal`）：
   监视剪贴板 → 自动换会话 → 查绑定宿舍 → 验证一次余额 → 终端出二维码。
3. 小粥历 → 宿舍用电 → 扫一扫导入：会话进后端，立即查一次余额并开始记历史。
4. 之后打开电费页就是实时余额（60 秒内用缓存，不打扰平台）；会话过期后页面
   显示"已过期"+ 最后读数，重新截留一次即可。

### 时序要点（踩过的坑）

- 断点拦住后要**马上用**：页面停留几分钟后 ccbParam 就会报
  `YBLYXDZK0005 第三方跳转超时`。
- 官方小程序被拦后会自己反复重试登录，每次重试都是一个新的未消费 ccbParam
  —— 取**最新**的那条。
- 后端保存的会话能跨重启复用（落在 `ccb_session` 表），同一会话可反复查询。

## 三、接口

| 接口 | 说明 |
|---|---|
| `POST /api/electricity/import` | 扫码导入会话，立即查一次余额并记历史 |
| `GET /api/electricity/live` | 页面总查询：会话状态 + 最新读数 |
| `POST /api/electricity/refresh` | 强制重查一次 |
| `GET /api/electricity/history?limit=30` | 读数历史，新的在前 |

旧的 `options/bind/balance`（演示数据 + 手动绑定）保持原样未动。
