/**
 * 作息时刻设置。
 *
 * 数据暂时只存本地（wx.storage）：选中的作息 id + 展示名，自定义的起止时间。
 * 「我的 → 作息时刻设置」右侧胶囊读的就是 routine.label。
 * 以后要让课表按节次画具体时间，再把这些值同步给后端 campus.term。
 */

const STORE_ACTIVE = 'routine.active';
const STORE_LABEL = 'routine.label';
const STORE_CUSTOM = 'routine.custom';

/** 三套常见高校作息。am/pm/eve 是各时段的起止时刻，自定义模板也用这份数据。 */
const PRESETS = [
  {
    id: 'p1',
    name: '预设一',
    tag: '早八版',
    desc: '上午 8:00 开课，适合有早课的日子',
    am: ['08:00', '11:40'],
    pm: ['14:00', '17:40'],
    eve: ['19:00', '20:40']
  },
  {
    id: 'p2',
    name: '预设二',
    tag: '常规版',
    desc: '上午 8:30 开课，午休和晚间更宽裕',
    am: ['08:30', '12:10'],
    pm: ['14:30', '18:10'],
    eve: ['19:30', '21:10']
  },
  {
    id: 'p3',
    name: '预设三',
    tag: '紧凑版',
    desc: '全天排课紧凑，午休更短',
    am: ['08:30', '12:00'],
    pm: ['14:00', '17:30'],
    eve: ['19:00', '20:40']
  }
];

const BLOCK_LABELS = { am: '上午 1-4 节', pm: '下午 5-8 节', eve: '晚间 9-10 节' };

/** 预设 → 卡片里的时段格子 */
function blocksOf(preset) {
  return Object.keys(BLOCK_LABELS).map((key) => ({
    label: BLOCK_LABELS[key],
    time: preset[key][0] + ' ~ ' + preset[key][1]
  }));
}

/** 本地存的自定义 → 编辑器行（key/label + 起止时间，缺省留空给 placeholder） */
function customToBlocks(custom) {
  return Object.keys(BLOCK_LABELS).map((key) => ({
    key,
    label: BLOCK_LABELS[key],
    start: (custom && custom[key] && custom[key].start) || '',
    end: (custom && custom[key] && custom[key].end) || ''
  }));
}

Page({
  data: {
    tab: 'preset',        // preset = 推荐作息，custom = 自定义
    presets: [],          // 渲染用：带时段格子 + 是否生效
    activeLabel: '未设置',
    customActive: false,
    customBlocks: []
  },

  onShow() {
    this.refresh();
  },

  refresh() {
    const activeId = wx.getStorageSync(STORE_ACTIVE) || '';
    const custom = wx.getStorageSync(STORE_CUSTOM) || null;

    this.setData({
      presets: PRESETS.map((p) => ({
        id: p.id,
        name: p.name,
        tag: p.tag,
        desc: p.desc,
        blocks: blocksOf(p),
        active: p.id === activeId
      })),
      activeLabel: wx.getStorageSync(STORE_LABEL) || '未设置',
      customActive: activeId === 'custom',
      customBlocks: customToBlocks(custom)
    });
  },

  switchTab(e) {
    this.setData({ tab: e.currentTarget.dataset.tab });
  },

  /** 直接启用一套推荐作息 */
  apply(e) {
    const preset = PRESETS.find((p) => p.id === e.currentTarget.dataset.id);
    if (!preset) {
      return;
    }
    wx.setStorageSync(STORE_ACTIVE, preset.id);
    wx.setStorageSync(STORE_LABEL, preset.name);
    this.refresh();
    wx.showToast({ title: '已启用 ' + preset.name, icon: 'success' });
  },

  /** 把某套预设的时间填进自定义表单，切过去微调 */
  useAsTemplate(e) {
    const preset = PRESETS.find((p) => p.id === e.currentTarget.dataset.id);
    if (!preset) {
      return;
    }
    this.setData({
      tab: 'custom',
      customBlocks: customToBlocks({ am: preset.am, pm: preset.pm, eve: preset.eve })
    });
  },

  /** 自定义表单里的时间选择 */
  pickTime(e) {
    const { key, field } = e.currentTarget.dataset;
    this.setData({
      customBlocks: this.data.customBlocks.map((b) =>
        b.key === key ? Object.assign({}, b, { [field]: e.detail.value }) : b)
    });
  },

  /** 保存自定义作息并启用 */
  saveCustom() {
    const custom = {};

    for (const b of this.data.customBlocks) {
      if (!b.start || !b.end) {
        wx.showToast({ title: '请把「' + b.label + '」的起止补全', icon: 'none' });
        return;
      }
      if (b.end <= b.start) {
        wx.showToast({ title: '「' + b.label + '」结束要晚于开始', icon: 'none' });
        return;
      }
      custom[b.key] = { start: b.start, end: b.end };
    }

    wx.setStorageSync(STORE_CUSTOM, custom);
    wx.setStorageSync(STORE_ACTIVE, 'custom');
    wx.setStorageSync(STORE_LABEL, '自定义');
    this.refresh();
    wx.showToast({ title: '自定义作息已生效', icon: 'success' });
  }
});
