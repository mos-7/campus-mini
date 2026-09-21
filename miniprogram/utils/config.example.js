/**
 * 本地配置示例 —— 复制成 config.local.js 再填你自己的值。
 *
 * 为什么单独放一个文件：
 *   config.local.js 已加入 .gitignore，所以你的云托管环境 ID 不会进公开仓库。
 *   这跟 project.config.json 里的 AppID 是同一个道理。
 *
 * 怎么用：
 *   1. 复制本文件为同目录下的 config.local.js
 *   2. 把 CLOUD_ENV 换成你自己的云托管环境 ID
 *      （云托管控制台 → 设置 → 环境设置，形如 prod-1g2h3i4j5k6l7m）
 *   3. 只有把 utils/api.js 里的 USE_CLOUD 改成 true 时才需要它
 *
 * 注意：本地联调（USE_CLOUD = false）完全用不到这个文件，不做也行。
 */
module.exports = {
  CLOUD_ENV: 'REPLACE_WITH_YOUR_CLOUD_ENV_ID'
};
