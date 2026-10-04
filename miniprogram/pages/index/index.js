const api = require('../../utils/api');

const app = getApp();

/** 开源仓库地址，展示在首页底部。 */
const REPO_URL = 'https://github.com/mos-7/campus-mini';

/** 距上次成功同步超过这个时长，打开小程序时就自动静默同步一次（课表自动更新）。 */
const AUTO_SYNC_INTERVAL = 24 * 60 * 60 * 1000;

/** 按当前时间给个问候语，和参考截图里那个"午安 / 同学 同学"对应。 */
function greetingOf(hour) {
  if (hour < 6) {
    return '夜深了';
  }
  if (hour < 11) {
    return '早安';
  }
  if (hour < 14) {
    return '午安';
  }
  if (hour < 18) {
    return '下午好';
  }
  return '晚上好';
}

Page({
  data: {
    loading: true,
    error: '',
    greeting: '',
    nickname: '同学',
    today: null,
    announcements: [],
    boundCount: 0,
    totalCount: 0,
    hasBinding: false,
    repoUrl: REPO_URL
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
      .then(() => Promise.all([api.today(), api.announcements(), api.adapters()]))
      .then(([today, announcements, adapters]) => {
        this.setData({
          loading: false,
          greeting: greetingOf(new Date().getHours()),
          nickname: (app.globalData.user && app.globalData.user.nickname) || '同学',
          today,
          announcements: announcements || [],
          boundCount: adapters.boundCount,
          totalCount: adapters.totalCount,
          hasBinding: adapters.boundCount > 0
        });
        this.maybeAutoSync(adapters);
      })
      .catch((err) => {
        this.setData({ loading: false, error: err.message });
      });
  },

  /**
   * 课表自动更新：有绑定的平台超过一天没同步成功，就在后台静默跑一次。
   *
   * 不弹进度框，失败也不打扰（下次打开再试）；「我的 → 课表自动更新」可以关掉。
   * 每次冷启动最多自动跑一次，防抖标记放在 app.globalData。
   */
  maybeAutoSync(adapters) {
    if (wx.getStorageSync('autoSync.enabled') === false) {
      return;
    }
    if (app.globalData.autoSyncBusy || app.globalData.autoSyncedAt) {
      return;
    }

    const stale = (adapters.cards || []).find((c) => {
      if (!c.bound) {
        return false;
      }
      if (!c.lastSyncAt) {
        return true;
      }
      return Date.now() - new Date(c.lastSyncAt).getTime() > AUTO_SYNC_INTERVAL;
    });
    if (!stale) {
      return;
    }

    app.globalData.autoSyncBusy = true;
    api.syncAndWait(stale.code)
      .then(() => this.refreshToday())
      .catch(() => {
      })
      .then(() => {
        app.globalData.autoSyncBusy = false;
        app.globalData.autoSyncedAt = Date.now();
      });
  },

  /** 静默刷新今日课程，不闪 loading。 */
  refreshToday() {
    api.today()
      .then((today) => this.setData({ today }))
      .catch(() => {
      });
  },

  goBind() {
    wx.navigateTo({ url: '/pages/bindings/bindings' });
  },

  goSchedule() {
    wx.switchTab({ url: '/pages/schedule/schedule' });
  },

  /** 快捷入口里的宿舍用电：进用电页（没绑宿舍会有引导）。 */
  goElectricity() {
    wx.navigateTo({ url: '/pages/electricity/electricity' });
  },

  /** 点仓库地址就复制，小程序的执行环境点不开外链，复制最实用。 */
  copyRepo() {
    wx.setClipboardData({
      data: REPO_URL,
      success() {
        wx.showToast({ title: '仓库地址已复制', icon: 'none' });
      }
    });
  },

  /** 点某节课看详情 */
  showSession(e) {
    const s = e.currentTarget.dataset.session;
    wx.showModal({
      title: s.courseName,
      content: [
        s.weekdayLabel + ' 第 ' + s.startSection + '-' + s.endSection + ' 节',
        s.location ? '地点：' + s.location : '',
        s.teacher ? '教师：' + s.teacher : ''
      ].filter(Boolean).join('\n'),
      showCancel: false,
      confirmText: '知道了'
    });
  }
});
