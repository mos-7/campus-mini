const api = require('./utils/api');

App({
  globalData: {
    user: null,
    loginError: '',
    autoSyncBusy: false,  // 静默同步正在跑，防止重复发起
    autoSyncedAt: 0       // 本次会话已经自动同步过的时间戳（每次冷启动最多跑一次）
  },

  onLaunch() {
    if (api.USE_CLOUD) {
      if (!wx.cloud) {
        console.error('基础库版本过低，无法使用云托管。请在 project.config.json 里提高 libVersion。');
        return;
      }
      // callContainer 需要先 init。env 填云托管环境 ID。
      wx.cloud.init({
        env: api.CLOUD_ENV,
        traceUser: true
      });
    }
  },

  /**
   * 页面复用的登录。已登录时直接返回缓存，不会重复请求。
   * @param {boolean} force 强制重新登录
   */
  login(force) {
    return api.ensureLogin(force)
      .then((user) => {
        this.globalData.user = user;
        this.globalData.loginError = '';
        return user;
      })
      .catch((err) => {
        this.globalData.loginError = err.message;
        throw err;
      });
  }
});
