const api = require('../../utils/api');

const app = getApp();

/** 开源仓库地址，展示在首页底部。 */
const REPO_URL = 'https://github.com/mos-7/campus-mini';

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
      })
      .catch((err) => {
        this.setData({ loading: false, error: err.message });
      });
  },

  goBind() {
    wx.switchTab({ url: '/pages/profile/profile' });
  },

  goSchedule() {
    wx.switchTab({ url: '/pages/schedule/schedule' });
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
