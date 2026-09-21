const api = require('../../utils/api');

const app = getApp();

/**
 * 功能卡片的展示元数据。
 *
 * key 必须和后端 Capability 枚举的名字一致 —— 后端 capability.code 就是枚举名。
 * 加一个功能 = 后端加 Capability + 适配器实现，这里加一条元数据。
 */
const FEATURE_META = {
  SCHEDULE: { icon: '📅', title: '课表', desc: '整周课程表', route: '/pages/schedule/schedule', isTab: true },
  COURSE_LIST: { icon: '📚', title: '课程列表', desc: '已选课程', route: '/pages/schedule/schedule', isTab: true },
  GRADE: { icon: '📊', title: '成绩查询', desc: '学期成绩', route: '' },
  EXAM: { icon: '📝', title: '考试安排', desc: '考场与时间', route: '' },
  CARD_BALANCE: { icon: '💳', title: '一卡通余额', desc: '余额与消费', route: '' },
  ELECTRICITY: { icon: '💡', title: '宿舍用电', desc: '用电查询', route: '' },
  RUN_RECORD: { icon: '🏃', title: '校园跑记录', desc: '跑量查询', route: '' }
};

/** 明确不做的东西，单独列出来，免得有人以为是漏做了。 */
const NOT_PLANNED = [
  { icon: '🚫', title: '网课挂机 / 自动答题' },
  { icon: '🚫', title: '签到代签' },
  { icon: '🚫', title: '校园跑代跑 / 虚拟定位' },
  { icon: '🚫', title: '验证码破解 / 代理换 IP' }
];

Page({
  data: {
    loading: true,
    error: '',
    features: [],
    notPlanned: NOT_PLANNED,
    boundCount: 0,
    totalCount: 0
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
        // 已绑定的平台提供了哪些能力
        const available = {};
        (data.cards || []).forEach((card) => {
          if (!card.bound) {
            return;
          }
          (card.capabilities || []).forEach((cap) => {
            if (cap.available) {
              available[cap.code] = true;
            }
          });
        });

        const features = Object.keys(FEATURE_META).map((code) => {
          const meta = FEATURE_META[code];
          return {
            code,
            icon: meta.icon,
            title: meta.title,
            desc: meta.desc,
            route: meta.route,
            isTab: !!meta.isTab,
            available: !!available[code]
          };
        });

        this.setData({
          loading: false,
          features,
          boundCount: data.boundCount,
          totalCount: data.totalCount
        });
      })
      .catch((err) => this.setData({ loading: false, error: err.message }));
  },

  tapFeature(e) {
    const f = e.currentTarget.dataset.feature;

    if (!f.available) {
      wx.showModal({
        title: f.title + ' 暂未开放',
        content: '这个功能需要一个已联调的适配器。当前可用的是「课表」——'
          + '在「我的 → 服务绑定中心」里绑定平台（或手动粘贴课表）后即可使用。',
        showCancel: false,
        confirmText: '知道了'
      });
      return;
    }

    if (!f.route) {
      wx.showToast({ title: '页面还没做', icon: 'none' });
      return;
    }

    if (f.isTab) {
      wx.switchTab({ url: f.route });
    } else {
      wx.navigateTo({ url: f.route });
    }
  },

  goBind() {
    wx.switchTab({ url: '/pages/profile/profile' });
  }
});
