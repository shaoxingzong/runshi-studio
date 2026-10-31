package com.bhu.runshistudioweb.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.bhu.runshistudioweb.exception.ErrorCode;
import com.bhu.runshistudioweb.exception.ThrowUtils;
import com.bhu.runshistudioweb.manager.ClientIpManager;
import com.bhu.runshistudioweb.mapper.StudioOperationLogMapper;
import com.bhu.runshistudioweb.mapper.SysUserMapper;
import com.bhu.runshistudioweb.model.dto.log.OperationLogQueryRequest;
import com.bhu.runshistudioweb.model.entity.StudioOperationLog;
import com.bhu.runshistudioweb.model.entity.SysUser;
import com.bhu.runshistudioweb.model.enums.OperationTypeEnum;
import com.bhu.runshistudioweb.model.vo.log.OperationActionOptionVO;
import com.bhu.runshistudioweb.model.vo.log.OperationLogVO;
import com.bhu.runshistudioweb.service.OperationLogService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 管理端操作日志服务实现
 *
 * @author shaoshing
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OperationLogServiceImpl extends ServiceImpl<StudioOperationLogMapper, StudioOperationLog>
        implements OperationLogService {

    /** 每页条数缺省值（与后台其它列表一致） */
    private static final long DEFAULT_PAGE_SIZE = 10L;

    /** 每页条数上限：日志表只增不减，放开就等于允许一次拉全表 */
    private static final long MAX_PAGE_SIZE = 50L;

    /** 对象标题列宽，与 DDL 的 varchar(256) 对齐 */
    private static final int MAX_TITLE_LENGTH = 256;

    /** 细节列宽，与 DDL 的 varchar(1000) 对齐；超长截断而不是让整条日志写失败 */
    private static final int MAX_DETAIL_LENGTH = 1000;

    /** 系统操作人（未登录 / 定时任务）：与 MyMetaObjectHandler 的回退值同一口径 */
    private static final long SYSTEM_OPERATOR_ID = 0L;

    /** Nginx 惯用配置 proxy_set_header X-Real-IP $remote_addr; 对应的头名 */
    private static final String HEADER_X_REAL_IP = "X-Real-IP";

    /** 逐跳追加的代理链，形如 客户端, 代理1, 代理2 */
    private static final String HEADER_X_FORWARDED_FOR = "X-Forwarded-For";

    /** 系统用户表：只用于把操作人 ID 翻译成账号 / 昵称快照 */
    private final SysUserMapper sysUserMapper;

    /** 客户端 IP 解析（与登录限流、考勤共用同一个 Manager） */
    private final ClientIpManager clientIpManager;

    // 写入

    /** 记录一条管理员操作日志 */
    @Override
    public void record(OperationTypeEnum action, Long targetId, String targetTitle, String detail) {
        // 埋点漏传动作码属于代码缺陷，但不该让管理员的审核操作失败
        if (action == null) {
            log.warn("操作日志缺少动作码，已跳过 | targetId={} | detail={}", targetId, detail);
            return;
        }
        try {
            // 操作人与 IP 只解析一次：这两步都要碰登录态与请求上下文，属于「一次性上下文」
            Operator operator = currentOperator();
            String ip = currentIp();
            this.save(buildRow(action, targetId, targetTitle, detail, operator, ip));
        } catch (Exception e) {
            // 只打一行：成功不打 info，失败不打整段入参，避免批量操作刷屏与隐私外泄
            log.warn("操作日志写入失败（不影响业务）| action={} | targetId={} | 原因={}",
                    action.getCode(), targetId, e.getMessage());
        }
    }

    /** 批量记录（一次 saveBatch，不逐条往返） */
    @Override
    public void recordBatch(OperationTypeEnum action, List<LogTarget> targets, String detail) {
        if (action == null || targets == null || targets.isEmpty()) {
            return;
        }
        try {
            Operator operator = currentOperator();
            String ip = currentIp();
            List<StudioOperationLog> rows = new ArrayList<>(targets.size());
            for (LogTarget target : targets) {
                if (target == null) {
                    continue;
                }
                rows.add(buildRow(action, target.id(), target.title(), detail, operator, ip));
            }
            if (!rows.isEmpty()) {
                this.saveBatch(rows);
            }
        } catch (Exception e) {
            log.warn("批量操作日志写入失败（不影响业务）| action={} | 条数={} | 原因={}",
                    action.getCode(), targets.size(), e.getMessage());
        }
    }

    // 查询

    /** 分页查询操作日志（时间倒序） */
    @Override
    public Page<OperationLogVO> listByPage(OperationLogQueryRequest request) {
        OperationLogQueryRequest query = request == null ? new OperationLogQueryRequest() : request;

        LambdaQueryWrapper<StudioOperationLog> wrapper = new LambdaQueryWrapper<>();

        // 动作码：非法值报参数错误而不是静默忽略——忽略会让用户以为筛选生效了
        if (StrUtil.isNotBlank(query.getAction())) {
            OperationTypeEnum action = OperationTypeEnum.of(query.getAction().trim());
            ThrowUtils.throwIf(action == null, ErrorCode.PARAMS_ERROR, "未知的操作动作码");
            wrapper.eq(StudioOperationLog::getAction, action.getCode());
        }

        // 对象类型：同上
        if (StrUtil.isNotBlank(query.getTargetType())) {
            OperationTypeEnum.TargetType targetType = OperationTypeEnum.TargetType.of(query.getTargetType().trim());
            ThrowUtils.throwIf(targetType == null, ErrorCode.PARAMS_ERROR, "未知的对象类型");
            wrapper.eq(StudioOperationLog::getTargetType, targetType.getCode());
        }

        // 时间范围：反了就是用户填错，明确报错；只填一端则按开区间查
        LocalDateTime start = query.getStartTime();
        LocalDateTime end = query.getEndTime();
        ThrowUtils.throwIf(start != null && end != null && start.isAfter(end),
                ErrorCode.PARAMS_ERROR, "开始时间不能晚于结束时间");
        if (start != null) {
            wrapper.ge(StudioOperationLog::getCreatedAt, start);
        }
        if (end != null) {
            wrapper.le(StudioOperationLog::getCreatedAt, end);
        }

        // 操作人：账号与昵称都要能搜（管理员未必记得昵称，账号名往往是更好用的线索）
        String keyword = StrUtil.trimToNull(query.getOperatorKeyword());
        if (keyword != null) {
            wrapper.and(w -> w.like(StudioOperationLog::getOperatorAccount, keyword)
                    .or()
                    .like(StudioOperationLog::getOperatorName, keyword));
        }

        wrapper.orderByDesc(StudioOperationLog::getCreatedAt)
                .orderByDesc(StudioOperationLog::getId);

        long current = query.getCurrent() == null || query.getCurrent() < 1 ? 1L : query.getCurrent();
        long pageSize = query.getPageSize() == null || query.getPageSize() < 1
                ? DEFAULT_PAGE_SIZE
                : Math.min(query.getPageSize(), MAX_PAGE_SIZE);

        Page<StudioOperationLog> entityPage = this.page(new Page<>(current, pageSize), wrapper);

        Page<OperationLogVO> voPage = new Page<>(entityPage.getCurrent(), entityPage.getSize(), entityPage.getTotal());
        voPage.setRecords(entityPage.getRecords().stream().map(this::toVO).toList());
        return voPage;
    }

    /** 动作字典 */
    @Override
    public List<OperationActionOptionVO> actionOptions() {
        List<OperationActionOptionVO> options = new ArrayList<>(OperationTypeEnum.values().length);
        for (OperationTypeEnum action : OperationTypeEnum.values()) {
            options.add(new OperationActionOptionVO(
                    action.getCode(),
                    action.getLabel(),
                    action.getTargetType().getCode(),
                    action.getTargetType().getLabel()));
        }
        return options;
    }

    // 内部实现

    /** 拼一行日志实体（快照与截断都在这里完成） */
    private StudioOperationLog buildRow(OperationTypeEnum action, Long targetId, String targetTitle,
                                        String detail, Operator operator, String ip) {
        StudioOperationLog row = new StudioOperationLog();
        row.setOperatorId(operator.id());
        row.setOperatorAccount(truncate(operator.account(), 64));
        row.setOperatorName(truncate(operator.name(), 64));
        row.setAction(action.getCode());
        row.setTargetType(action.getTargetType().getCode());
        row.setTargetId(targetId);
        row.setTargetTitle(truncate(targetTitle, MAX_TITLE_LENGTH));
        row.setDetail(truncate(detail, MAX_DETAIL_LENGTH));
        row.setIp(ip);
        return row;
    }

    /** 按列宽截断（不是抛异常，也不是让数据库替我们拒绝整条日志） */
    private String truncate(String text, int maxLength) {
        if (text == null) {
            return null;
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    /** 当前操作人快照 */
    private Operator currentOperator() {
        try {
            if (!StpUtil.isLogin()) {
                return Operator.SYSTEM;
            }
            long operatorId = StpUtil.getLoginIdAsLong();
            SysUser user = sysUserMapper.selectById(operatorId);
            if (user == null) {
                // 账号已被物理删除：ID 仍然是有意义的线索，账号/昵称留空
                return new Operator(operatorId, "", null);
            }
            String account = StrUtil.isBlank(user.getUserAccount()) ? "" : user.getUserAccount();
            return new Operator(operatorId, account, user.getUserName());
        } catch (Exception e) {
            log.debug("取当前登录人失败，操作人记为系统 | 原因={}", e.getMessage());
            return Operator.SYSTEM;
        }
    }

    /** 来源 IP（取不到返回 null，不因此报错） */
    private String currentIp() {
        try {
            RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
            if (!(attributes instanceof ServletRequestAttributes servletAttributes)) {
                return null;
            }
            HttpServletRequest request = servletAttributes.getRequest();
            return clientIpManager.resolveClientIp(request.getRemoteAddr(),
                    request.getHeader(HEADER_X_REAL_IP),
                    request.getHeader(HEADER_X_FORWARDED_FOR));
        } catch (Exception e) {
            log.debug("解析操作来源 IP 失败，已留空 | 原因={}", e.getMessage());
            return null;
        }
    }

    /** 实体转视图：补上两个中文标签 */
    private OperationLogVO toVO(StudioOperationLog row) {
        OperationLogVO vo = new OperationLogVO();
        vo.setId(row.getId());
        vo.setOperatorId(row.getOperatorId());
        vo.setOperatorAccount(row.getOperatorAccount());
        vo.setOperatorName(row.getOperatorName());
        vo.setAction(row.getAction());
        vo.setTargetType(row.getTargetType());
        vo.setTargetId(row.getTargetId());
        vo.setTargetTitle(row.getTargetTitle());
        vo.setDetail(row.getDetail());
        vo.setIp(row.getIp());
        vo.setCreatedAt(row.getCreatedAt());

        // 标签查不到（历史动作码被下线）时降级显示原始码，不把整页打不开
        OperationTypeEnum action = OperationTypeEnum.of(row.getAction());
        vo.setActionLabel(action == null ? row.getAction() : action.getLabel());
        OperationTypeEnum.TargetType targetType = OperationTypeEnum.TargetType.of(row.getTargetType());
        vo.setTargetTypeLabel(targetType == null ? row.getTargetType() : targetType.getLabel());
        return vo;
    }

    /** 操作人快照（ID + 账号 + 昵称） */
    private record Operator(long id, String account, String name) {

        /** 系统操作人：未登录、定时任务、无请求上下文时使用（与审计字段的 0 同义） */
        private static final Operator SYSTEM = new Operator(SYSTEM_OPERATOR_ID, "", null);
    }
}
