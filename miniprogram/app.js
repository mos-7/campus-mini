const api = require('./utils/api');

App({
  globalData: {
    user: null,
    loginError: ''
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
