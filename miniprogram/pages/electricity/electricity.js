const STORE_KEY = 'elec.records';

function pad(n) {
  return n < 10 ? '0' + n : '' + n;
}

/** 时间戳 → 'MM-DD hh:mm' */
function formatTime(ts) {
  const d = new Date(ts);
  return pad(d.getMonth() + 1) + '-' + pad(d.getDate()) + ' '
    + pad(d.getHours()) + ':' + pad(d.getMinutes());
}

function round1(v) {
  return Math.round(v * 10) / 10;
}

/**
 * 宿舍用电 · 手动录入模式。
 *
 * 学校电费走的是建行 E码通 平台，其登录链绑定了他们小程序的微信身份
 * （一次性 ccbParam + 会话防重放），后端无法自动化查询——所以电费数字
 * 由你在别处查到后手动记一笔，这里负责保存历史、算变化量。
 * 记录只存本机（wx.storage），不上传。
 */
Page({
  data: {
    records: [],   // 新的在前
    latest: null,  // 最新一条
    diff: null,    // 与上一条的变化 { kwh, yuan }
    kwh: '',
    yuan: ''
  },

  onShow() {
    this.refresh();
  },

  refresh() {
    const records = (wx.getStorageSync(STORE_KEY) || []).map((r) => ({
      ...r,
      timeText: formatTime(r.time)
    }));
    this.setData({
      records,
      latest: records[0] || null,
      diff: this.diffOf(records)
    });
  },

  /** 最新一条相对上一条的变化，没有可比字段就不显示 */
  diffOf(records) {
    if (records.length < 2) {
      return null;
    }
    const a = records[0];
    const b = records[1];
    const diff = {};
    if (a.kwh !== '' && b.kwh !== '') {
      diff.kwh = round1(a.kwh - b.kwh);
    }
    if (a.yuan !== '' && b.yuan !== '') {
      diff.yuan = round1(a.yuan - b.yuan);
    }
    return Object.keys(diff).length ? diff : null;
  },

  onKwh(e) {
    this.setData({ kwh: e.detail.value });
  },

  onYuan(e) {
    this.setData({ yuan: e.detail.value });
  },

  save() {
    const kwh = String(this.data.kwh).trim();
    const yuan = String(this.data.yuan).trim();
    if (kwh === '' && yuan === '') {
      wx.showToast({ title: '电量金额至少填一个', icon: 'none' });
      return;
    }

    const records = wx.getStorageSync(STORE_KEY) || [];
    records.unshift({
      id: Date.now(),
      time: Date.now(),
      kwh: kwh === '' ? '' : Number(kwh),
      yuan: yuan === '' ? '' : Number(yuan)
    });
    if (records.length > 60) {
      records.length = 60;
    }
    wx.setStorageSync(STORE_KEY, records);

    this.setData({ kwh: '', yuan: '' });
    this.refresh();
    wx.showToast({ title: '已记录', icon: 'success' });
  },

  remove(e) {
    const id = e.currentTarget.dataset.id;
    wx.showModal({
      title: '删除记录',
      content: '确定删除这条记录吗？',
      success: (res) => {
        if (!res.confirm) {
          return;
        }
        const records = (wx.getStorageSync(STORE_KEY) || []).filter((r) => r.id !== id);
        wx.setStorageSync(STORE_KEY, records);
        this.refresh();
      }
    });
  }
});
