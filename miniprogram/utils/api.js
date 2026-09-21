/**
 * 统一请求封装。
 *
 * 两个模式，由 USE_CLOUD 切换：
 *
 *  1) USE_CLOUD = false —— 本地联调。走 wx.request 打到 localhost:8080。
 *     记得在开发者工具「详情 → 本地设置」里勾上「不校验合法域名」。
 *
 *  2) USE_CLOUD = true  —— 上线。走 wx.cloud.callContainer 打到微信云托管。
 *     ★ 这是免域名、免备案的关键：callContainer 不需要在 mp 后台配置服务器域名。
 *     必须显式带上 X-WX-SERVICE 头（云托管靠它路由到具体服务）。
 */

// 本地联调 = false（走 wx.request 打 localhost）；上线 = true（走 callContainer）
// ★ 已切到云端模式：后端部署在微信云托管（服务名 campus-api）。
//   要回到本地联调，把这里改回 false 即可。
const USE_CLOUD = true;

// 云托管环境 ID。
//
// ★ 刻意不写死在这里，而是从 config.local.js 读 —— 那个文件已加入 .gitignore，
//   所以你的环境 ID 不会被提交到公开仓库（和 project.config.json 里的 AppID 同理）。
//   照同目录的 config.example.js 建一份 config.local.js 即可。
//   读不到就退回占位符；只有在 USE_CLOUD = true 时才会因此报错。
let CLOUD_ENV = 'REPLACE_WITH_YOUR_CLOUD_ENV_ID';
try {
  const local = require('./config.local');
  if (local && local.CLOUD_ENV) {
    CLOUD_ENV = local.CLOUD_ENV;
  }
} catch (e) {
  // config.local.js 不存在。本地联调（USE_CLOUD=false）用不到它，属正常情况。
}

// 云托管服务名，必须和云托管控制台里创建的服务名一致
const CLOUD_SERVICE = 'campus-api';

const LOCAL_BASE = 'http://localhost:8080';

const TOKEN_KEY = 'campus_token';

// ----------------------------------------------------------------------
// token
// ----------------------------------------------------------------------

function getToken() {
  return wx.getStorageSync(TOKEN_KEY) || '';
}

function setToken(token) {
  wx.setStorageSync(TOKEN_KEY, token);
}

function clearToken() {
  wx.removeStorageSync(TOKEN_KEY);
}

// ----------------------------------------------------------------------
// 底层请求
// ----------------------------------------------------------------------

/**
 * @param {string} path   形如 '/api/schedule/today'
 * @param {object} options { method, data }
 * @returns {Promise<any>} 直接 resolve 信封里的 data；业务失败则 reject 带 message 的 Error
 */
function request(path, options = {}) {
  const method = (options.method || 'GET').toUpperCase();
  const data = options.data || {};

  return new Promise((resolve, reject) => {
    const onSuccess = (res) => {
      const body = res.data;

      // HTTP 层面不成功
      if (res.statusCode < 200 || res.statusCode >= 300) {
        const message = body && body.message ? body.message : `请求失败（HTTP ${res.statusCode}）`;
        if (res.statusCode === 401) {
          clearToken();
        }
        reject(new Error(message));
        return;
      }

      // 业务层面：code !== 0 视为失败
      if (!body || typeof body.code === 'undefined') {
        reject(new Error('响应格式不对，检查后端是否在跑'));
        return;
      }
      if (body.code !== 0) {
        if (body.code === 40100) {
          clearToken();
        }
        reject(new Error(body.message || '请求失败'));
        return;
      }
      resolve(body.data);
    };

    const onFail = (err) => {
      reject(new Error(describeNetworkError(err)));
    };

    if (USE_CLOUD) {
      if (!wx.cloud || !wx.cloud.callContainer) {
        reject(new Error('云托管不可用：基础库版本过低，或未在小程序后台开通云托管'));
        return;
      }
      wx.cloud.callContainer({
        config: { env: CLOUD_ENV },
        path,
        method,
        data,
        header: {
          'X-WX-SERVICE': CLOUD_SERVICE,
          'content-type': 'application/json',
          Authorization: 'Bearer ' + getToken()
        },
        success: onSuccess,
        fail: onFail
      });
    } else {
      wx.request({
        url: LOCAL_BASE + path,
        method,
        data,
        header: {
          'content-type': 'application/json',
          Authorization: 'Bearer ' + getToken()
        },
        success: onSuccess,
        fail: onFail
      });
    }
  });
}

function describeNetworkError(err) {
  const raw = (err && (err.errMsg || err.message)) || '';
  if (raw.indexOf('timeout') >= 0) {
    return '请求超时。同步类操作要轮询，别用同步请求硬等。';
  }
  if (raw.indexOf('domain') >= 0 || raw.indexOf('not in domain list') >= 0) {
    return '域名未配置：本地联调请在开发者工具里勾「不校验合法域名」；上线请用云托管 callContainer。';
  }
  return '网络错误：' + (raw || '未知');
}

// ----------------------------------------------------------------------
// 登录
// ----------------------------------------------------------------------

/**
 * 确保已登录。已登录直接返回缓存的用户。
 *
 * 云托管模式下后端直接从请求头拿 openid，所以这里不用 wx.login 换 code。
 * 本地联调时后端 mock 模式会认 body 里的 openid。
 */
function ensureLogin(force) {
  if (!force && getToken()) {
    return Promise.resolve(wx.getStorageSync('campus_user') || null);
  }

  const payload = USE_CLOUD ? {} : { openid: 'demo', nickname: '演示同学' };

  return request('/api/auth/login', { method: 'POST', data: payload }).then((data) => {
    setToken(data.token);
    wx.setStorageSync('campus_user', data);
    return data;
  });
}

// ----------------------------------------------------------------------
// 业务接口 —— 与后端 CampusController 一一对应
// ----------------------------------------------------------------------

const api = {
  USE_CLOUD,
  CLOUD_ENV,
  CLOUD_SERVICE,
  ensureLogin,
  getToken,
  clearToken,

  /** 平台卡片 + 「x / y 已连接」 */
  adapters: () => request('/api/adapters'),

  /** 绑定。manual 模式下 username 随便填，secret 传课表文本。 */
  bind: (adapterCode, username, secret) =>
    request('/api/bindings', { method: 'POST', data: { adapterCode, username, secret } }),

  unbind: (adapterCode) =>
    request('/api/bindings/' + adapterCode, { method: 'DELETE' }),

  /** 发起同步，立刻拿到 taskId */
  startSync: (adapterCode) =>
    request('/api/sync/' + adapterCode, { method: 'POST' }),

  taskStatus: (taskId) => request('/api/sync/tasks/' + taskId),

  /** week 省略取当前周 */
  week: (week) => request('/api/schedule' + (week ? '?week=' + week : '')),

  today: () => request('/api/schedule/today'),

  courses: () => request('/api/courses'),

  announcements: () => request('/api/announcements'),

  /**
   * 发起同步并轮询到结束。
   *
   * 云托管 callContainer 超时 15 秒，所以后端一定是异步的；
   * 这里负责把它包装成一个"看起来同步"的 Promise 给页面用。
   *
   * @param {string} adapterCode
   * @param {(task: object) => void} onProgress 每次轮询回调，用来更新进度条
   */
  syncAndWait(adapterCode, onProgress) {
    return api.startSync(adapterCode).then((res) => {
      const taskId = res.taskId;
      return new Promise((resolve, reject) => {
        let attempts = 0;
        const MAX_ATTEMPTS = 80; // 80 * 1.5s = 2 分钟

        const tick = () => {
          attempts += 1;
          if (attempts > MAX_ATTEMPTS) {
            reject(new Error('同步超时（超过 2 分钟），请重试'));
            return;
          }
          api.taskStatus(taskId).then((task) => {
            if (onProgress) {
              onProgress(task);
            }
            if (task.status === 'SUCCESS') {
              resolve(task);
            } else if (task.status === 'FAILED') {
              reject(new Error(task.message || '同步失败'));
            } else {
              setTimeout(tick, 1500);
            }
          }).catch(reject);
        };

        setTimeout(tick, 600);
      });
    });
  }
};

module.exports = api;
