package com.campus.mini.electricity;

import java.util.List;

/**
 * 校园用电的数据源接口（适配器缝）。
 *
 * <h2>为什么先只给一个演示实现</h2>
 *
 * <p>电费数据要接的是学校自己的电控平台（各校系统不一，需要抓包确认接口，
 * 套路见 docs/jwxt-adapter.md）。在接通之前，先用 {@link DemoElectricityProvider}
 * 顶上：四级联动的选项、读数全部真实可用，流程能完整跑通。以后接真平台时，
 * 再写一个实现类替换掉这个 bean 就行，控制器和服务层都不用动。
 */
public interface ElectricityProvider {

    /** 一次用电读数。金额单位元，电量单位度。 */
    record Reading(double balanceYuan, double remainingKwh, double yesterdayKwh, double monthKwh) {
    }

    /** 全部校区。 */
    List<String> campuses();

    /** 某校区的楼栋。 */
    List<String> buildings(String campus);

    /** 某楼栋的楼层。 */
    List<String> floors(String campus, String building);

    /** 某楼层的房间号。 */
    List<String> rooms(String campus, String building, String floor);

    /** 查一间宿舍的用电读数。 */
    Reading query(String campus, String building, String floor, String room);
}
