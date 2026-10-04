/**
 * 宿舍用电 · 待开发占位页。
 *
 * 学校电费在建行 E码通 平台上，登录链绑定建行小程序的微信身份，
 * 普通用户没有办法把会话交给我们的后端（那需要抓包截留，见
 * docs/ccb-electricity.md），所以对发布版本而言此功能暂时无法开放，
 * 页面先挂"开发中"占位。后端的会话接力通道（/api/electricity/import|live|refresh|history）
 * 和 CcbClient 都保留着，接口侧有解法时把页面接回去即可。
 */
Page({});
