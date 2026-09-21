const api = require('../../utils/api');
const dateUtil = require('../../utils/date');

const app = getApp();

/** 每节课占多少 rpx 高。网格所有定位都基于它。 */
const ROW_HEIGHT = 100;

Page({
  data: {
    loading: true,
    error: '',
    week: 0,              // 0 = 让后端给当前周
    totalWeeks: 20,
    rows: [],             // 左侧节次轴
    days: [],             // 7 列的课表格子
    weekChips: [],        // 周次选择器
    rowHeight: ROW_HEIGHT,
    gridHeight: 0,
    rangeText: '',
    hasSessions: false,
    demo: false
  },

  onShow() {
    this.load(this.data.week);
  },

  onPullDownRefresh() {
    this.load(this.data.week).then(() => wx.stopPullDownRefresh());
  },

  load(week) {
    this.setData({ loading: true, error: '' });

    return app.login()
      .then(() => api.week(week))
      .then((view) => this.render(view))
      .catch((err) => this.setData({ loading: false, error: err.message }));
  },

  render(view) {
    const todayIso = dateUtil.todayIso();
    const todayShort = todayIso.slice(5); // 'MM-DD'

    const header = dateUtil.weekHeader(view.mondayDate);

    // 每一列：把落在这一天的上课安排转成绝对定位的方块
    const days = header.map((day) => {
      const blocks = (view.sessions || [])
        .filter((s) => s.dayOfWeek === day.dayOfWeek)
        .map((s) => ({
          key: s.courseName + '#' + s.startSection,
          courseName: s.courseName,
          teacher: s.teacher,
          location: s.location,
          weekdayLabel: s.weekdayLabel,
          startSection: s.startSection,
          endSection: s.endSection,
          top: (s.startSection - 1) * ROW_HEIGHT,
          height: (s.endSection - s.startSection + 1) * ROW_HEIGHT - 6
        }));

      return {
        dayOfWeek: day.dayOfWeek,
        label: day.label,
        shortLabel: day.shortLabel,
        date: day.date,
        isToday: day.date === todayShort,
        blocks
      };
    });

    const rows = [];
    for (let i = 1; i <= view.sectionsPerDay; i++) {
      rows.push({ n: i });
    }

    const weekChips = [];
    for (let w = 1; w <= view.totalWeeks; w++) {
      weekChips.push({ w, active: w === view.week, isCurrent: false });
    }

    this.setData({
      loading: false,
      week: view.week,
      totalWeeks: view.totalWeeks,
      rows,
      days,
      weekChips,
      gridHeight: view.sectionsPerDay * ROW_HEIGHT,
      rangeText: dateUtil.shortDate(view.mondayDate) + ' ~ ' + dateUtil.shortDate(view.sundayDate),
      hasSessions: (view.sessions || []).length > 0,
      demo: !!view.demo
    });
  },

  prevWeek() {
    if (this.data.week <= 1) {
      wx.showToast({ title: '已经是第一周', icon: 'none' });
      return;
    }
    this.load(this.data.week - 1);
  },

  nextWeek() {
    if (this.data.week >= this.data.totalWeeks) {
      wx.showToast({ title: '已经是最后一周', icon: 'none' });
      return;
    }
    this.load(this.data.week + 1);
  },

  backToCurrent() {
    this.load(0);
  },

  pickWeek(e) {
    this.load(e.currentTarget.dataset.week);
  },

  showBlock(e) {
    const b = e.currentTarget.dataset.block;
    wx.showModal({
      title: b.courseName,
      content: [
        b.weekdayLabel + ' 第 ' + b.startSection + '-' + b.endSection + ' 节',
        b.location ? '地点：' + b.location : '地点未填',
        b.teacher ? '教师：' + b.teacher : ''
      ].filter(Boolean).join('\n'),
      showCancel: false,
      confirmText: '知道了'
    });
  },

  goBind() {
    wx.switchTab({ url: '/pages/profile/profile' });
  }
});
