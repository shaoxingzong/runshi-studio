package com.bhu.runshistudioweb.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.bhu.runshistudioweb.constant.AttendanceConstant;
import com.bhu.runshistudioweb.exception.BusinessException;
import com.bhu.runshistudioweb.exception.ErrorCode;
import com.bhu.runshistudioweb.exception.ThrowUtils;
import com.bhu.runshistudioweb.mapper.StudioAttendanceMapper;
import com.bhu.runshistudioweb.mapper.StudioMemberMapper;
import com.bhu.runshistudioweb.mapper.SysUserMapper;
import com.bhu.runshistudioweb.model.entity.StudioAttendance;
import com.bhu.runshistudioweb.model.entity.StudioMember;
import com.bhu.runshistudioweb.model.entity.SysUser;
import com.bhu.runshistudioweb.model.enums.MemberStatusEnum;
import com.bhu.runshistudioweb.model.vo.attendance.AttendanceMonthStatVO;
import com.bhu.runshistudioweb.model.vo.attendance.AttendanceRecordVO;
import com.bhu.runshistudioweb.service.AttendanceStatService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 管理端考勤统计实现
 *
 * @author shaoshing
 */
@Service
@RequiredArgsConstructor
public class AttendanceStatServiceImpl implements AttendanceStatService {

    /** 最多回溯的月数：再往前翻既没有业务意义，也让范围参数失去约束 */
    private static final int MAX_LOOKBACK_MONTHS = 60;

    private final StudioAttendanceMapper studioAttendanceMapper;

    private final StudioMemberMapper studioMemberMapper;

    private final SysUserMapper sysUserMapper;

    /** 月度汇总：有记录的成员 + 在队但没来的成员 */
    @Override
    public List<AttendanceMonthStatVO> listMonthStats(String month) {
        YearMonth target = resolveMonth(month);
        LocalDate start = target.atDay(1);
        LocalDate end = target.atEndOfMonth();

        QueryWrapper<StudioAttendance> statQuery = new QueryWrapper<>();
        statQuery.select("user_id",
                        "COUNT(DISTINCT attendance_date) AS day_count",
                        "COUNT(*) AS time_count",
                        "MIN(check_in_at) AS first_at",
                        "MAX(check_in_at) AS last_at")
                .between("attendance_date", start, end)
                .groupBy("user_id");

        Map<Long, AttendanceMonthStatVO> byUser = new LinkedHashMap<>();
        for (Map<String, Object> row : studioAttendanceMapper.selectMaps(statQuery)) {
            Long userId = toLong(row.get("user_id"));
            if (userId == null) {
                // user_id 在业务上必填；出现 NULL 说明数据已脏，跳过而不是塞进列表
                continue;
            }
            AttendanceMonthStatVO vo = new AttendanceMonthStatVO();
            vo.setUserId(userId);
            vo.setDays(toInt(row.get("day_count")));
            vo.setTimes(toInt(row.get("time_count")));
            vo.setFirstAt(toLocalDateTime(row.get("first_at")));
            vo.setLastAt(toLocalDateTime(row.get("last_at")));
            byUser.put(userId, vo);
        }

        List<StudioMember> activeMembers = studioMemberMapper.selectList(
                new LambdaQueryWrapper<StudioMember>()
                        .isNotNull(StudioMember::getUserId)
                        .eq(StudioMember::getMemberStatus, MemberStatusEnum.IN_TEAM.getValue()));

        Map<Long, StudioMember> memberByUser = new HashMap<>(activeMembers.size());
        for (StudioMember member : activeMembers) {
            memberByUser.put(member.getUserId(), member);
            // 只补 0 行，不覆盖已有统计
            byUser.computeIfAbsent(member.getUserId(), userId -> zeroRow(userId, member.getMemberStatus()));
        }

        // 该月有签到、但现在已离队（或档案已被删）的成员：历史报表里不能凭空少人
        List<Long> extraUserIds = byUser.keySet().stream()
                .filter(userId -> !memberByUser.containsKey(userId))
                .toList();
        if (!extraUserIds.isEmpty()) {
            for (StudioMember member : studioMemberMapper.selectList(
                    new LambdaQueryWrapper<StudioMember>().in(StudioMember::getUserId, extraUserIds))) {
                memberByUser.put(member.getUserId(), member);
            }
        }

        fillNames(byUser.values(), memberByUser);

        List<AttendanceMonthStatVO> result = new ArrayList<>(byUser.values());
        // 排序：先看「来了几天」，再看「来了几次」，最后按姓名稳定排序
        result.sort(Comparator
                .comparing((AttendanceMonthStatVO vo) -> vo.getDays() == null ? 0 : vo.getDays())
                .thenComparing(vo -> vo.getTimes() == null ? 0 : vo.getTimes())
                .reversed()
                .thenComparing(vo -> StrUtil.blankToDefault(vo.getUserName(), "")));
        return result;
    }

    /** 单人逐日明细 */
    @Override
    public List<AttendanceRecordVO> listMemberRecords(long userId, String month) {
        ThrowUtils.throwIf(userId <= 0, ErrorCode.PARAMS_ERROR, "成员 id 不合法");
        YearMonth target = resolveMonth(month);
        LocalDate start = target.atDay(1);
        LocalDate end = target.atEndOfMonth();

        LambdaQueryWrapper<StudioAttendance> wrapper = new LambdaQueryWrapper<>();
        wrapper.select(StudioAttendance::getCheckInAt, StudioAttendance::getIp)
                .eq(StudioAttendance::getUserId, userId)
                .between(StudioAttendance::getAttendanceDate, start, end)
                // 次级键：秒级精度下同一秒的多条记录需要稳定顺序
                .orderByDesc(StudioAttendance::getCheckInAt)
                .orderByDesc(StudioAttendance::getId);

        List<AttendanceRecordVO> records = new ArrayList<>();
        for (StudioAttendance record : studioAttendanceMapper.selectList(wrapper)) {
            AttendanceRecordVO vo = new AttendanceRecordVO();
            vo.setCheckInAt(record.getCheckInAt());
            vo.setIp(record.getIp());
            records.add(vo);
        }
        return records;
    }

    // 私有工具

    /** 解析月份并限制可查询范围 */
    private YearMonth resolveMonth(String month) {
        YearMonth current = YearMonth.now(AttendanceConstant.ZONE_ID);
        if (StrUtil.isBlank(month)) {
            return current;
        }
        YearMonth target;
        try {
            target = YearMonth.parse(month.trim());
        } catch (DateTimeParseException e) {
            // 前端选择器理论上不会传错，直接调接口时才会遇到；给可读提示而不是 500
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "月份格式应为 yyyy-MM");
        }
        ThrowUtils.throwIf(target.isAfter(current), ErrorCode.PARAMS_ERROR, "不能查询未来月份");
        ThrowUtils.throwIf(target.isBefore(current.minusMonths(MAX_LOOKBACK_MONTHS)),
                ErrorCode.PARAMS_ERROR, "只能查询最近 5 年的考勤");
        return target;
    }

    /** 构造一条「零签到」汇总（在队但整月没来） */
    private AttendanceMonthStatVO zeroRow(long userId, Integer memberStatus) {
        AttendanceMonthStatVO vo = new AttendanceMonthStatVO();
        vo.setUserId(userId);
        vo.setMemberStatus(memberStatus);
        vo.setDays(0);
        vo.setTimes(0);
        return vo;
    }

    /** 批量补姓名与成员状态 */
    private void fillNames(Iterable<AttendanceMonthStatVO> stats, Map<Long, StudioMember> memberByUser) {
        List<AttendanceMonthStatVO> missing = new ArrayList<>();
        for (AttendanceMonthStatVO vo : stats) {
            StudioMember member = memberByUser.get(vo.getUserId());
            if (member != null) {
                vo.setUserName(member.getName());
                vo.setMemberStatus(member.getMemberStatus());
            } else {
                missing.add(vo);
            }
        }
        if (missing.isEmpty()) {
            return;
        }

        Set<Long> missingIds = missing.stream().map(AttendanceMonthStatVO::getUserId).collect(Collectors.toSet());
        Map<Long, SysUser> userById = sysUserMapper.selectByIds(missingIds).stream()
                .collect(Collectors.toMap(SysUser::getId, Function.identity(), (a, b) -> a));
        for (AttendanceMonthStatVO vo : missing) {
            SysUser user = userById.get(vo.getUserId());
            vo.setUserName(user == null
                    ? "已删除成员"
                    : StrUtil.blankToDefault(user.getUserName(), user.getUserAccount()));
        }
    }

    /** 聚合列类型不确定（Long / BigInteger / BigDecimal），统一走 Number 转换 */
    private static Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.valueOf(String.valueOf(value));
    }

    private static Integer toInt(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.valueOf(String.valueOf(value));
    }

    private static LocalDateTime toLocalDateTime(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDateTime dateTime) {
            return dateTime;
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        return null;
    }
}
