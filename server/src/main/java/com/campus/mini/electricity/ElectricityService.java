package com.campus.mini.electricity;

import com.campus.mini.common.ApiException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 校园用电：四级联动的选项 + 宿舍绑定 + 电费查询。
 *
 * <p>选项和读数全部来自 {@link ElectricityProvider}（现在是演示实现），
 * 这里只做参数校验和绑定状态的存取。
 */
@Service
public class ElectricityService {

    /** 查询结果：宿舍定位 + 读数。 */
    public record BalanceView(DormStore.Dorm dorm,
                              double balanceYuan,
                              double remainingKwh,
                              double yesterdayKwh,
                              double monthKwh,
                              boolean demo,
                              Instant queriedAt) {
    }

    private final ElectricityProvider provider;
    private final DormStore dormStore;

    public ElectricityService(ElectricityProvider provider, DormStore dormStore) {
        this.provider = provider;
        this.dormStore = dormStore;
    }

    /** 级联选项。level ∈ campus / building / floor / room，后三级需要带上级的选中值。 */
    public List<String> options(String level, String campus, String building, String floor) {
        return switch (level == null ? "" : level) {
            case "campus" -> provider.campuses();
            case "building" -> {
                requireCampus(campus);
                yield provider.buildings(campus);
            }
            case "floor" -> {
                requireCampus(campus);
                requireBuilding(campus, building);
                yield provider.floors(campus, building);
            }
            case "room" -> {
                requireCampus(campus);
                requireBuilding(campus, building);
                requireFloor(campus, building, floor);
                yield provider.rooms(campus, building, floor);
            }
            default -> throw ApiException.badRequest("level 只能是 campus / building / floor / room");
        };
    }

    /** 绑定宿舍。房间必须真实存在于数据源里，防止前端绕过级联乱传。 */
    public void bind(long userId, String campus, String building, String floor, String room) {
        List<String> validRooms = options("room", campus, building, floor);
        if (room == null || !validRooms.contains(room)) {
            throw ApiException.badRequest("这个房间不存在，请重新选择。");
        }
        dormStore.save(userId, new DormStore.Dorm(campus, building, floor, room));
    }

    public void unbind(long userId) {
        dormStore.delete(userId);
    }

    public Optional<DormStore.Dorm> dormOf(long userId) {
        return dormStore.find(userId);
    }

    public BalanceView balance(long userId) {
        DormStore.Dorm dorm = dormStore.find(userId)
                .orElseThrow(() -> ApiException.badRequest("还没绑定宿舍，先去选一间。"));

        ElectricityProvider.Reading reading =
                provider.query(dorm.campus(), dorm.building(), dorm.floor(), dorm.room());

        return new BalanceView(dorm, reading.balanceYuan(), reading.remainingKwh(),
                reading.yesterdayKwh(), reading.monthKwh(),
                provider instanceof DemoElectricityProvider, Instant.now());
    }

    // ------------------------------------------------------------------

    private void requireCampus(String campus) {
        if (campus == null || !provider.campuses().contains(campus)) {
            throw ApiException.badRequest("请先选择校区。");
        }
    }

    private void requireBuilding(String campus, String building) {
        if (building == null || !provider.buildings(campus).contains(building)) {
            throw ApiException.badRequest("请先选择楼栋。");
        }
    }

    private void requireFloor(String campus, String building, String floor) {
        if (floor == null || !provider.floors(campus, building).contains(floor)) {
            throw ApiException.badRequest("请先选择楼层。");
        }
    }
}
