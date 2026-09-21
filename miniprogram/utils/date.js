/**
 * 日期小工具。只处理课表页要用到的几个场景。
 *
 * 注意：学期第一周周一是【后端】的配置（campus.term.start-date），
 * 前端不重复算周次 —— 两边各算一遍必然对不上。这里只做展示格式化。
 */

const WEEKDAY_LABELS = ['', '周一', '周二', '周三', '周四', '周五', '周六', '周日'];

function pad(n) {
  return n < 10 ? '0' + n : '' + n;
}

/** '2026-03-02' → '03-02' */
function shortDate(isoDate) {
  if (!isoDate) {
    return '';
  }
  const parts = String(isoDate).split('-');
  return parts.length === 3 ? parts[1] + '-' + parts[2] : isoDate;
}

/** '2026-03-02' → Date（按本地时区，避免 new Date('2026-03-02') 被当 UTC） */
function parseLocalDate(isoDate) {
  const parts = String(isoDate).split('-').map(Number);
  return new Date(parts[0], parts[1] - 1, parts[2]);
}

/** 生成一周 7 天的表头 */
function weekHeader(mondayIso) {
  const base = parseLocalDate(mondayIso);
  const out = [];
  for (let i = 0; i < 7; i++) {
    const d = new Date(base.getTime());
    d.setDate(base.getDate() + i);
    out.push({
      dayOfWeek: i + 1,
      label: WEEKDAY_LABELS[i + 1],
      shortLabel: WEEKDAY_LABELS[i + 1].replace('周', ''),
      date: pad(d.getMonth() + 1) + '-' + pad(d.getDate())
    });
  }
  return out;
}

function weekdayLabel(dayOfWeek) {
  return WEEKDAY_LABELS[dayOfWeek] || '';
}

/** 今天的 ISO 日期，用于判断"今天"高亮 */
function todayIso() {
  const d = new Date();
  return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate());
}

module.exports = {
  WEEKDAY_LABELS,
  shortDate,
  parseLocalDate,
  weekHeader,
  weekdayLabel,
  todayIso
};
