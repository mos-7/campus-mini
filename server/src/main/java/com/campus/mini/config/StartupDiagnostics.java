package com.campus.mini.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;

/**
 * 启动自检 —— 把最容易配错、又最难查的几项配置打进日志。
 *
 * <h2>为什么需要它</h2>
 *
 * <p>生产环境最常见的事故是「环境变量没生效」，而云托管控制台上的变量列表
 * 只能证明<b>你存了</b>，证明不了<b>正在运行的那个版本真的读到了</b>。
 * 这两件事的排查方式完全不同：
 *
 * <ul>
 *   <li>没存 → 回控制台补上</li>
 *   <li>存了但没应用 → 重新发布一次（旧版本没带这批变量）</li>
 * </ul>
 *
 * <p>启动时把解析后的实际值打出来，一眼就能分辨。踩过这个坑 ——
 * 云端「移动教务」显示暂未开放，但控制台里变量看着都在。
 *
 * <h2>只打非敏感信息</h2>
 *
 * <p>数据源 URL（不含密码）、适配器开关、密钥<b>长度</b>而非内容。
 */
@Component
public class StartupDiagnostics implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupDiagnostics.class);

    private final CampusProperties properties;
    private final DataSource dataSource;

    public StartupDiagnostics(CampusProperties properties, DataSource dataSource) {
        this.properties = properties;
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("[启动自检] ================ 配置生效情况 ================");

        try (Connection connection = dataSource.getConnection()) {
            log.info("[启动自检] 数据源 = {}", connection.getMetaData().getURL());
        } catch (Exception e) {
            log.error("[启动自检] 数据源连接失败：{}", e.getMessage());
        }

        CampusProperties.MobileJw mobileJw = properties.getMobileJw();
        log.info("[启动自检] 移动教务 enabled={} baseUrl={} pwdKey={}",
                mobileJw.isEnabled(),
                mobileJw.getBaseUrl().isBlank() ? "(空 !! )" : mobileJw.getBaseUrl(),
                mobileJw.getPwdKey().isBlank() ? "(空 !! )" : (mobileJw.getPwdKey().length() + " 字符"));

        log.info("[启动自检] 超星 enabled={}  演示数据={}  微信 mock={}  openid 头={}",
                properties.getChaoxing().isEnabled(),
                properties.isDemoData(),
                properties.getWechat().isMockEnabled(),
                properties.getWechat().getOpenidHeader());

        log.info("[启动自检] 主密钥={}  JWT 密钥={}",
                properties.getMasterKey().isBlank() ? "(空 !! 正在用开发默认值)" : "已配置",
                properties.getJwtSecret().isBlank() ? "(空 !! 正在用开发默认值)" : "已配置");

        log.info("[启动自检] ================================================");
    }
}
