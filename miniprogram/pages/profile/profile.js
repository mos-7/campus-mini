const api = require('../../utils/api');

const app = getApp();

/**
 * 开发者联系邮箱。
 *
 * ★ 改这里要同步改 `pages/privacy/privacy.js` 第九节，
 *   以及微信后台「用户隐私保护指引」里的联系方式 —— 三处必须一致。
 */
const DEVELOPER_EMAIL = '2452246289@qq.com';

/**
 * 个人中心。
 *
 * 平台的绑定 / 同步 / 解绑都挪到了 `pages/bindings` 子页，
 * 这页的服务绑定中心卡片只放三个平台小部件，点任意一个跳过去。
 */
Page({
  data: {
    loading: true,
    error: '',

    nickname: '同学',
    routineLabel: '未设置',
    autoSyncOn: true,

    boundCount: 0,
    totalCount: 0,
    cards: []
  },

  onShow() {
    this.load();
  },

  onPullDownRefresh() {
    this.load().then(() => wx.stopPullDownRefresh());
  },

  load() {
    this.setData({ loading: true, error: '' });

    return app.login()
      .then(() => api.adapters())
      .then((data) => {
        this.setData({
          loading: false,
          nickname: (app.globalData.user && app.globalData.user.nickname) || '同学',
          routineLabel: wx.getStorageSync('routine.label') || '未设置',
          autoSyncOn: wx.getStorageSync('autoSync.enabled') !== false,
          boundCount: data.boundCount,
          totalCount: data.totalCount,
          cards: (data.cards || []).map((c) => ({
            code: c.code,
            name: c.name,
            bound: !!c.bound,
            stateText: c.bound ? '已连接' : (c.enabled ? '未连接' : '暂未开放')
          }))
        });
      })
      .catch((err) => this.setData({ loading: false, error: err.message }));
  },

  // ------------------------------------------------------------------
  // 页面跳转
  // ------------------------------------------------------------------

  /** 服务绑定中心：三个小部件都进同一个绑定管理页 */
  openBindings() {
    wx.navigateTo({ url: '/pages/bindings/bindings' });
  },

  /** 作息时刻设置子页。数据存在本地，子页里选完回来 onShow 会刷新胶囊。 */
  openPeriods() {
    wx.navigateTo({ url: '/pages/periods/periods' });
  },

  /** 隐私政策。提审时需要能查到这个页面，所以放在显眼位置。 */
  privacy() {
    wx.navigateTo({ url: '/pages/privacy/privacy' });
  },

  // ------------------------------------------------------------------
  // 设置
  // ------------------------------------------------------------------

  /**
   * 课表自动更新开关。
   *
   * 开着的时候，首页每次冷启动会检查绑定的平台：超过一天没同步成功
   * 就在后台静默跑一次（逻辑在 pages/index 的 maybeAutoSync）。
   * 本地存一个布尔，没存过 = 开启。
   */
  toggleAutoSync() {
    const next = !this.data.autoSyncOn;
    wx.setStorageSync('autoSync.enabled', next);
    this.setData({ autoSyncOn: next });
    wx.showToast({ title: next ? '已开启自动更新' : '已关闭自动更新', icon: 'none' });
  },

  // ------------------------------------------------------------------
  // 其他
  // ------------------------------------------------------------------

  /**
   * 联系开发者。
   *
   * ★ 隐私政策页里承诺的联系渠道就是这里 —— 两处必须同时存在，
   *   否则就是"隐私政策与实际不符"（可被驳回）。
   *   小程序的执行环境调不起邮件客户端，所以做成复制邮箱。
   */
  contact() {
    wx.setClipboardData({
      data: DEVELOPER_EMAIL,
      success() {
        wx.showToast({ title: '邮箱已复制', icon: 'none' });
      }
    });
  },

  about() {
    wx.showModal({
      title: '关于',
      content: '小粥历 · 只读的个人信息查询工具。\n'
        + '只读取你本人的信息，不做任何修改。',
      showCancel: false,
      confirmText: '知道了'
    });
  }
});
