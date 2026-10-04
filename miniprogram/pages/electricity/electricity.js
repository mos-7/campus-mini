const api = require('../../utils/api');

const STORE_KEY = 'elec.records';

function pad(n) {
  return n < 10 ? '0' + n : '' + n;
}

/** 时间戳(秒或毫秒 ISO) → 'MM-DD hh:mm' */
function formatTime(ts) {
  const d = ts instanceof Date ? ts : new Date(ts);
  if (isNaN(d.getTime())) {
    return '';
  }
  return pad(d.getMonth() + 1) + '-' + pad(d.getDate()) + ' '
    + pad(d.getHours()) + ':' + pad(d.getMinutes());
}

function round1(v) {
  return Math.round(v * 10) / 10;
}

function fmtNum(v) {
  return (v === null || v === undefined || v === '') ? '--' : v;
}

/**
 * 宿舍用电 · 建行会话接力模式。
 *
 * 学校电费在建行 E码通 上，登录链绑定建行小程序的微信身份，后端没法自己登录。
 * 解法是"接力"：在电脑上用 Reqable 断点截留一次登录请求，relay 脚本把它换成
 * 会话并打成二维码，这里扫码交给后端代查。银行侧会话约半小时不用就过期，
 * 过期后重新截留一次即可（详见 docs/ccb-electricity.md）。
 *
 * 手动记一笔仍然保留：会话过期又懒得截留的时候，也能把数字记进历史。
 */
Page({
  data: {
    loading: true,
    imported: false,   // 是否导入过会话
    status: 'NONE',    // NONE / ACTIVE / EXPIRED
    dormText: '',
    lastError: '',
    fareText: '--',    // 剩余金额（元）
    kwhText: '--',     // 剩余电量（度）
    subsidyText: '',   // 补助摘要
    updatedAtText: '',
    history: [],       // 后端读数历史（新的在前）
    records: [],       // 本地手动记录（新的在前）
    latest: null,
    diff: null,
    kwh: '',
    yuan: ''
  },

  onShow() {
    api.ensureLogin()
      .then(() => this.reload())
      .catch(() => {
        this.setData({ loading: false });
      });
    this.loadLocal();
  },

  reload() {
    this.setData({ loading: true });
    return Promise.all([api.ccbLive(), api.ccbHistory()])
      .then(([live, history]) => {
        this.applyLive(live);
        this.applyHistory(history || []);
        this.setData({ loading: false });
      })
      .catch((e) => {
        this.setData({ loading: false });
        wx.showToast({ title: e.message || '加载失败', icon: 'none' });
      });
  },

  applyLive(live) {
    const latest = live.latest;
    this.setData({
      imported: !!live.imported,
      status: live.status || 'NONE',
      dormText: live.dormText || (latest && latest.dormText) || '',
      lastError: live.lastError || '',
      fareText: latest ? fmtNum(latest.mainFare) : '--',
      kwhText: latest ? fmtNum(latest.bal) : '--',
      subsidyText: this.subsidyText(latest),
      updatedAtText: latest && latest.queriedAt ? formatTime(latest.queriedAt) + ' 查询' : ''
    });
  },

  subsidyText(latest) {
    if (!latest) {
      return '';
    }
    const parts = [];
    if (latest.subsidyBal !== null && latest.subsidyBal !== undefined && latest.subsidyBal !== '') {
      parts.push('补助电量 ' + latest.subsidyBal + ' 度');
    }
    if (latest.subsidyMain !== null && latest.subsidyMain !== undefined && latest.subsidyMain !== '') {
      parts.push('补助金额 ' + latest.subsidyMain + ' 元');
    }
    return parts.join(' · ');
  },

  applyHistory(history) {
    const rows = history.map((r, i) => {
      const prev = history[i + 1];
      let diffText = '';
      if (prev && r.mainFare !== null && prev.mainFare !== null) {
        const d = round1(r.mainFare - prev.mainFare);
        if (d !== 0) {
          diffText = (d > 0 ? '+' : '') + d + ' 元';
        }
      }
      return {
        id: r.id,
        fareText: fmtNum(r.mainFare),
        kwhText: fmtNum(r.bal),
        timeText: formatTime(r.queriedAt),
        diffText
      };
    });
    this.setData({ history: rows });
  },

  // ---------------- 会话导入 ----------------

  scanImport() {
    wx.scanCode({
      onlyFromCamera: false,
      success: (res) => {
        let payload;
        try {
          payload = JSON.parse(res.result);
        } catch (e) {
          wx.showToast({ title: '二维码内容不是会话数据', icon: 'none' });
          return;
        }
        if (!payload || !payload.skey || !payload.uid) {
          wx.showToast({ title: '会话数据不完整', icon: 'none' });
          return;
        }
        wx.showLoading({ title: '导入中' });
        api.ccbImport(payload)
          .then((view) => {
            wx.hideLoading();
            wx.showToast({ title: '导入成功', icon: 'success' });
            this.applyLive({
              imported: true,
              status: 'ACTIVE',
              dormText: view.dormText,
              latest: view
            });
            return this.reload();
          })
          .catch((e) => {
            wx.hideLoading();
            wx.showModal({ title: '导入失败', content: e.message || '会话无效', showCancel: false });
            this.reload();
          });
      }
    });
  },

  refresh() {
    wx.showLoading({ title: '查询中' });
    api.ccbRefresh()
      .then((view) => {
        wx.hideLoading();
        wx.showToast({ title: '已更新', icon: 'success' });
        this.applyLive({ imported: true, status: 'ACTIVE', dormText: view.dormText, latest: view });
        return api.ccbHistory().then((h) => this.applyHistory(h || []));
      })
      .catch((e) => {
        wx.hideLoading();
        wx.showToast({ title: e.message || '刷新失败', icon: 'none' });
        this.reload();
      });
  },

  showImportHelp() {
    wx.showModal({
      title: '怎么导入会话',
      content: '在电脑上：Reqable 开断点 → 微信打开校园e码通小程序 → 断点处复制链接并放弃 → 运行 node relay.js → 出二维码后用这里扫码。',
      showCancel: false,
      confirmText: '知道了'
    });
  },

  // ---------------- 手动记一笔（本地） ----------------

  loadLocal() {
    const records = (wx.getStorageSync(STORE_KEY) || []).map((r) => ({
      ...r,
      timeText: formatTime(r.time)
    }));
    this.setData({
      records,
      latestLocal: records[0] || null,
      diff: this.diffOf(records)
    });
  },

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
    this.loadLocal();
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
        this.loadLocal();
      }
    });
  }
});
