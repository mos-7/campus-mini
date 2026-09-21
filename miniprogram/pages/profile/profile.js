const api = require('../../utils/api');

const app = getApp();

/** 和 ManualAdapter.SAMPLE 保持一致的示例，方便一键试。 */
const SAMPLE_TIMETABLE = [
  '高等数学,张老师,周一,1-2节,1-16周,教三201',
  '线性代数,李老师,周三,3-4节,1-16周(单),教二105',
  '大学物理,王老师,周五,5-6节,1-8周,实验楼B302'
].join('\n');

Page({
  data: {
    loading: true,
    error: '',

    boundCount: 0,
    totalCount: 0,
    cards: [],

    // 绑定面板
    panelVisible: false,
    activeCard: null,
    isManual: false,
    formUsername: '',
    formSecret: '',
    submitting: false,

    // 同步进度
    syncVisible: false,
    syncProgress: 0,
    syncMessage: ''
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
          boundCount: data.boundCount,
          totalCount: data.totalCount,
          cards: (data.cards || []).map((c) => Object.assign({}, c, {
            loginModeLabel: c.loginModeLabel || '',
            statusText: c.bound ? '已连接' : (c.enabled ? '未连接' : '暂未开放')
          }))
        });
      })
      .catch((err) => this.setData({ loading: false, error: err.message }));
  },

  // ------------------------------------------------------------------
  // 绑定
  // ------------------------------------------------------------------

  openBind(e) {
    const card = e.currentTarget.dataset.card;

    if (!card.enabled) {
      wx.showModal({
        title: card.name + ' 暂未开放',
        content: '这个适配器还没完成联调。'
          + '如果你要接的是超星，按 docs/chaoxing.md 抓包确认后把 campus.chaoxing.enabled 改成 true。',
        showCancel: false,
        confirmText: '知道了'
      });
      return;
    }

    const isManual = card.loginMode === 'MANUAL';
    this.setData({
      panelVisible: true,
      activeCard: card,
      isManual,
      formUsername: '',
      formSecret: isManual ? SAMPLE_TIMETABLE : ''
    });
  },

  closePanel() {
    if (this.data.submitting) {
      return;
    }
    this.setData({ panelVisible: false, activeCard: null, formSecret: '', formUsername: '' });
  },

  fillSample() {
    this.setData({ formSecret: SAMPLE_TIMETABLE });
  },

  onUsernameInput(e) {
    this.setData({ formUsername: e.detail.value });
  },

  onSecretInput(e) {
    this.setData({ formSecret: e.detail.value });
  },

  /** 阻止面板内部点击穿透到遮罩 */
  noop() {
  },

  submitBind() {
    const card = this.data.activeCard;
    if (!card) {
      return;
    }
    if (!this.data.formSecret.trim()) {
      wx.showToast({ title: this.data.isManual ? '请粘贴课表' : '请填写密码或 Token', icon: 'none' });
      return;
    }

    this.setData({ submitting: true });

    api.bind(card.code, this.data.formUsername.trim(), this.data.formSecret)
      .then((res) => {
        if (!res.ok) {
          // 后端校验不过：把适配器给的提示原样显示
          wx.showModal({ title: '绑定失败', content: res.message || '凭据校验没通过', showCancel: false });
          this.setData({ submitting: false });
          return;
        }

        this.setData({ panelVisible: false, submitting: false });
        wx.showToast({ title: '绑定成功', icon: 'success' });

        // 绑定完立刻同步一次，用户马上就能看到课表
        return this.runSync(card.code).then(() => this.load());
      })
      .catch((err) => {
        this.setData({ submitting: false });
        wx.showModal({ title: '绑定失败', content: err.message, showCancel: false });
      });
  },

  unbind(e) {
    const card = e.currentTarget.dataset.card;
    wx.showModal({
      title: '解除绑定',
      content: '确定要解除「' + card.name + '」的绑定吗？已同步的课表缓存也会一并清掉。',
      success: (res) => {
        if (!res.confirm) {
          return;
        }
        api.unbind(card.code)
          .then(() => {
            wx.showToast({ title: '已解绑', icon: 'success' });
            return this.load();
          })
          .catch((err) => wx.showModal({ title: '解绑失败', content: err.message, showCancel: false }));
      }
    });
  },

  // ------------------------------------------------------------------
  // 同步
  // ------------------------------------------------------------------

  syncNow(e) {
    const card = e.currentTarget.dataset.card;
    this.runSync(card.code)
      .then(() => {
        wx.showToast({ title: '同步完成', icon: 'success' });
        return this.load();
      })
      .catch((err) => wx.showModal({ title: '同步失败', content: err.message, showCancel: false }));
  },

  /**
   * 跑一次同步并显示进度。
   *
   * 后端是异步的（云托管 15 秒超时），这里负责把进度条画出来。
   */
  runSync(adapterCode) {
    this.setData({ syncVisible: true, syncProgress: 0, syncMessage: '正在发起…' });

    return api.syncAndWait(adapterCode, (task) => {
      this.setData({
        syncProgress: task.progress || 0,
        syncMessage: task.message || '处理中…'
      });
    }).then((task) => {
      this.setData({
        syncVisible: false,
        syncProgress: 100,
        syncMessage: task.message || '完成'
      });
      return task;
    }).catch((err) => {
      this.setData({ syncVisible: false });
      throw err;
    });
  },

  // ------------------------------------------------------------------
  // 其他
  // ------------------------------------------------------------------

  notReady(e) {
    const what = e.currentTarget.dataset.what;
    wx.showModal({
      title: what + ' 还没做',
      content: '这是个占位入口。作息预设要等课表数据稳定后再做，'
        + '因为上下课时间得先能从你学校的数据里拿到，或者手动配到 campus.term 里。',
      showCancel: false,
      confirmText: '知道了'
    });
  },

  about() {
    wx.showModal({
      title: '关于',
      content: '校园盒子 · 只读的校园信息聚合工具。\n'
        + '不含刷课、代答、签到、代跑等功能，也不破解平台风控。',
      showCancel: false,
      confirmText: '知道了'
    });
  }
});
