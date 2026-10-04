const api = require('../../utils/api');
const dateUtil = require('../../utils/date');

const app = getApp();

/** 每节课占多少 rpx 高。网格所有定位都基于它（样式里的横向行线间距也是 100rpx，改要一起改）。 */
const ROW_HEIGHT = 100;

/**
 * 课表方块的糖果色盘。
 * 按课程名散列取色 —— 同一门课每周颜色固定，不同课程尽量错开。
 */
const BLOCK_PALETTES = [
  { bg: '#e7defc', text: '#5546a8' },
  { bg: '#d9e8fd', text: '#2f5fa8' },
  { bg: '#d3f2e0', text: '#1f7a4d' },
  { bg: '#fdeec2', text: '#8a6d1a' },
  { bg: '#fbdcec', text: '#a83e7d' },
  { bg: '#ffe3cc', text: '#a35422' }
];

function colorIndexOf(courseName) {
  let hash = 0;
  const name = String(courseName || '');
  for (let i = 0; i < name.length; i++) {
    hash = (hash * 31 + name.charCodeAt(i)) % 997;
  }
  return hash % BLOCK_PALETTES.length;
}

Page({
  data: {
    loading: true,
    error: '',
    week: 0,              // 0 = 让后端给当前周
    totalWeeks: 20,
    todayText: '',        // 顶部胶囊里的“10月4日 周日”
    rows: [],             // 左侧节次轴
    days: [],             // 7 列的课表格子
    weekLabels: [],       // 周次选择器的选项
    weekIndex: 0,
    isCurrentWeek: true,
    rowHeight: ROW_HEIGHT,
    gridHeight: 0,
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
      .then((view) => {
        // week=0 表示“当前周”，顺手把当前周号记下来，之后翻到别的周还能标注（本周）
        if (!week) {
          this._currentWeek = view.week;
        }
        this.render(view);
      })
      .catch((err) => this.setData({ loading: false, error: err.message }));
  },

  render(view) {
    const todayIso = dateUtil.todayIso();
    const todayShort = todayIso.slice(5); // 'MM-DD'

    const now = new Date();
    const todayText = (now.getMonth() + 1) + '月' + now.getDate() + '日 '
      + dateUtil.weekdayLabel(now.getDay() === 0 ? 7 : now.getDay());

    // 每一列：把落在这一天的上课安排转成绝对定位的方块
    const days = dateUtil.weekHeader(view.mondayDate).map((day) => {
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
          ci: colorIndexOf(s.courseName),
          top: (s.startSection - 1) * ROW_HEIGHT,
          height: (s.endSection - s.startSection + 1) * ROW_HEIGHT - 8
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

    const weekLabels = [];
    for (let w = 1; w <= view.totalWeeks; w++) {
      weekLabels.push('第 ' + w + ' 周');
    }

    this.setData({
      loading: false,
      week: view.week,
      totalWeeks: view.totalWeeks,
      todayText,
      rows,
      days,
      weekLabels,
      weekIndex: view.week - 1,
      isCurrentWeek: view.week === this._currentWeek,
      gridHeight: view.sectionsPerDay * ROW_HEIGHT,
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

  /** 回到当前周（顶部日期胶囊旁的刷新按钮也是它） */
  backToCurrent() {
    this.load(0);
  },

  /** 周次选择器：picker 返回的是下标 */
  pickWeek(e) {
    this.load(Number(e.detail.value) + 1);
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
    wx.navigateTo({ url: '/pages/bindings/bindings' });
  }
});
