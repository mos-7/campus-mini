const api = require('../../utils/api');

const app = getApp();

/** 四级联动：页面步骤名和接口 level 参数一一对应。 */
const STEPS = ['选校区', '选楼栋', '选楼层', '选房间'];
const LEVELS = ['campus', 'building', 'floor', 'room'];

function pad(n) {
  return n < 10 ? '0' + n : '' + n;
}

/** ISO 时间 → 'MM-DD hh:mm' */
function formatTime(iso) {
  if (!iso) {
    return '';
  }
  const d = new Date(iso);
  if (isNaN(d.getTime())) {
    return '';
  }
  return pad(d.getMonth() + 1) + '-' + pad(d.getDate()) + ' '
    + pad(d.getHours()) + ':' + pad(d.getMinutes());
}

Page({
  data: {
    loading: true,
    error: '',

    bound: false,
    dorm: null,
    reading: null,
    querying: false,

    // 绑定面板
    sheetVisible: false,
    steps: STEPS,
    step: 0,
    selections: [null, null, null, null],
    options: [],
    optionsLoading: false,
    binding: false
  },

  onShow() {
    this.load();
  },

  load() {
    this.setData({ loading: true, error: '' });

    return app.login()
      .then(() => api.electricityStatus())
      .then((status) => {
        this.setData({ loading: false, bound: !!status.bound, dorm: status.dorm || null });
        if (status.bound) {
          return this.queryBalance();
        }
      })
      .catch((err) => this.setData({ loading: false, error: err.message }));
  },

  /** 查电费读数。数字在本地格式化，避免 32.0 这种尾巴。 */
  queryBalance() {
    this.setData({ querying: true });

    return api.electricityBalance()
      .then((raw) => {
        this.setData({
          querying: false,
          reading: {
            balanceYuan: Number(raw.balanceYuan).toFixed(2),
            remainingKwh: Number(raw.remainingKwh).toFixed(1),
            yesterdayKwh: Number(raw.yesterdayKwh).toFixed(1),
            monthKwh: Number(raw.monthKwh).toFixed(1),
            demo: !!raw.demo,
            timeText: formatTime(raw.queriedAt)
          }
        });
      })
      .catch((err) => {
        this.setData({ querying: false });
        wx.showModal({ title: '查询失败', content: err.message, showCancel: false });
      });
  },

  // ------------------------------------------------------------------
  // 绑定面板：四级级联
  // ------------------------------------------------------------------

  openSheet() {
    this.setData({
      sheetVisible: true,
      step: 0,
      selections: [null, null, null, null],
      options: [],
      binding: false
    });
    this.loadOptions(0);
  },

  closeSheet() {
    if (this.data.binding) {
      return;
    }
    this.setData({ sheetVisible: false });
  },

  /** 阻止面板内部点击穿透到遮罩 */
  noop() {
  },

  /** 点步骤条回退到某一步，后面几级的选择作废。 */
  tapStep(e) {
    const step = e.currentTarget.dataset.step;
    if (step >= this.data.step) {
      return;
    }
    const selections = this.data.selections.slice();
    for (let i = step; i < selections.length; i++) {
      selections[i] = null;
    }
    this.setData({ step, selections, options: [] });
    this.loadOptions(step);
  },

  loadOptions(step) {
    const [campus, building, floor] = this.data.selections;
    this.setData({ optionsLoading: true });

    api.electricityOptions(LEVELS[step], campus, building, floor)
      .then((options) => this.setData({ options: options || [], optionsLoading: false }))
      .catch((err) => {
        this.setData({ optionsLoading: false });
        wx.showToast({ title: err.message, icon: 'none' });
      });
  },

  /** 选中当前级的一项，自动进入下一级；最后一级选中后停在原地等确认。 */
  pickOption(e) {
    const step = this.data.step;
    const selections = this.data.selections.slice();
    selections[step] = e.currentTarget.dataset.name;
    this.setData({ selections, options: [] });

    if (step < LEVELS.length - 1) {
      this.setData({ step: step + 1 });
      this.loadOptions(step + 1);
    }
  },

  confirmBind() {
    const [campus, building, floor, room] = this.data.selections;
    if (!room) {
      return;
    }
    this.setData({ binding: true });

    api.bindDorm(campus, building, floor, room)
      .then(() => {
        this.setData({ sheetVisible: false, binding: false });
        wx.showToast({ title: '绑定成功', icon: 'success' });
        return this.load();
      })
      .catch((err) => {
        this.setData({ binding: false });
        wx.showModal({ title: '绑定失败', content: err.message, showCancel: false });
      });
  },

  unbind() {
    wx.showModal({
      title: '解绑宿舍',
      content: '确定要解绑当前宿舍吗？解绑后需要重新选择才能查询。',
      success: (res) => {
        if (!res.confirm) {
          return;
        }
        api.unbindDorm()
          .then(() => {
            wx.showToast({ title: '已解绑', icon: 'success' });
            this.setData({ reading: null });
            return this.load();
          })
          .catch((err) => wx.showModal({ title: '解绑失败', content: err.message, showCancel: false }));
      }
    });
  }
});
