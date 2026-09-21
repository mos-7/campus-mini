-- 校园盒子 · 建表脚本
-- 同时兼容 H2（MODE=MySQL）、MySQL 5.7 和 MySQL 8。
-- 所有语句都是幂等的，可以重复执行。
--
-- ★★ 关于 TIMESTAMP 列：必须显式写 DEFAULT CURRENT_TIMESTAMP
--   踩过这个坑：MySQL 5.7 默认 sql_mode 含 NO_ZERO_DATE，而 MySQL 只让
--   【第一个】TIMESTAMP 列自动获得 DEFAULT CURRENT_TIMESTAMP。于是
--   `binding` 表里第二个 NOT NULL 的 TIMESTAMP（created_at）没默认值，
--   隐式取零值 '0000-00-00' 又被 NO_ZERO_DATE 拒绝，启动直接报：
--       Invalid default value for 'created_at'
--   所以下面每个 NOT NULL 的 TIMESTAMP 都显式给了默认值。
--   （MySQL 5.6.5 起允许多个列都有 CURRENT_TIMESTAMP 默认值。）

-- 表名用 app_user 而不是 user：
-- H2 2.x 把 USER 当保留字（它是内置函数），`CREATE TABLE user` 会直接报语法错误。
-- MySQL 允许，但为了两边都能跑，统一避开保留字。
CREATE TABLE IF NOT EXISTS app_user (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    openid     VARCHAR(64)  NOT NULL UNIQUE,
    nickname   VARCHAR(64),
    avatar_url VARCHAR(512),
    created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 平台绑定。secret_encrypted 是 CredentialVault 的密文，永不存明文。
CREATE TABLE IF NOT EXISTS binding (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id          BIGINT       NOT NULL,
    adapter_code     VARCHAR(32)  NOT NULL,
    login_mode       VARCHAR(16)  NOT NULL,
    account_label    VARCHAR(128),
    secret_encrypted VARCHAR(1024),
    status           VARCHAR(16)  NOT NULL DEFAULT 'BOUND',
    last_sync_at     TIMESTAMP    NULL,
    last_error       VARCHAR(1024),
    created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_binding_user_adapter UNIQUE (user_id, adapter_code)
);

CREATE TABLE IF NOT EXISTS course (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id          BIGINT       NOT NULL,
    adapter_code     VARCHAR(32)  NOT NULL,
    external_id      VARCHAR(128) NOT NULL,
    name             VARCHAR(255) NOT NULL,
    teacher          VARCHAR(128),
    class_name       VARCHAR(255),
    cover_url        VARCHAR(512),
    raw_time_text    VARCHAR(512),
    updated_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 课表网格的一个格子。weeks 是 CSV，如 "1,3,5,7"
CREATE TABLE IF NOT EXISTS course_session (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id            BIGINT       NOT NULL,
    adapter_code       VARCHAR(32)  NOT NULL,
    course_external_id VARCHAR(128),
    course_name        VARCHAR(255) NOT NULL,
    teacher            VARCHAR(128),
    location           VARCHAR(255),
    day_of_week        INT          NOT NULL,
    start_section      INT          NOT NULL,
    end_section        INT          NOT NULL,
    weeks              VARCHAR(512) NOT NULL,
    raw_text           VARCHAR(512),
    updated_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 注意：这里【不】建索引。
-- 原因：MySQL 不支持 `CREATE INDEX IF NOT EXISTS`，而 H2 支持；两个都要兼容就会
-- 在 MySQL 上启动失败。而本项目的数据量是"一个学生自己的课表"（几十行），
-- 索引对性能没有任何影响。真要加，等数据量上来了手工加，见 docs/deploy-cloudrun.md。

-- 同步任务状态机。落库是为了让缩容/重启后能收拾掉僵尸任务。
CREATE TABLE IF NOT EXISTS sync_task (
    id            VARCHAR(48) PRIMARY KEY,
    user_id       BIGINT       NOT NULL,
    adapter_code  VARCHAR(32)  NOT NULL,
    status        VARCHAR(16)  NOT NULL,
    progress      INT          NOT NULL DEFAULT 0,
    message       VARCHAR(1024),
    course_count  INT          NOT NULL DEFAULT 0,
    session_count INT          NOT NULL DEFAULT 0,
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at   TIMESTAMP    NULL
);

CREATE TABLE IF NOT EXISTS announcement (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    title        VARCHAR(255) NOT NULL,
    body         VARCHAR(2048),
    published_at DATE         NOT NULL,
    pinned       BOOLEAN      NOT NULL DEFAULT FALSE
);

-- FROM DUAL 是 H2 和 MySQL 都认的写法（MySQL 里省略 FROM 的 SELECT ... WHERE 不可靠）
INSERT INTO announcement (title, body, published_at, pinned)
SELECT '项目已就跑通', '课表 + 今日课程可用（演示数据）。超星适配器待抓包联调，见 docs/chaoxing.md。', CURRENT_DATE, TRUE
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM announcement);
