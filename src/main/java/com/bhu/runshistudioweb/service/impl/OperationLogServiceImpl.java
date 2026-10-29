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
 * 三条约束全部收在 record / recordBatch 里：
 *   ① **绝不抛异常**：日志是附属产物，表满、字段超长、无请求上下文都不该让审核失败；
 *   ② **与业务同事务**：本类不加 @Transactional，写操作自动加入调用方的事务，
 *      业务回滚则日志一起消失——不会留下「操作成功」的假痕迹；
 *   ③ **只存可读的快照**：操作人账号/昵称、对象标题都在写入时定格，事后账号被删也能读懂。
 *
 * 为什么不用 AOP 切面：切面只能拿到方法签名，拿不到「实际影响了哪几行、驳回理由是什么、
 * 角色从什么改成了什么」。这些恰恰是事故排查时唯一有用的信息。
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

    /**
     * 客户端 IP 解析（与登录限流、考勤共用同一个 Manager）
     *
     * 不能直接读 X-Forwarded-For：请求头完全由客户端伪造，只有 TCP 对端是可信代理时才采信，
     * 这条信任链已经在 ClientIpManager 里实现，这里不重复判断。
     */
    private final ClientIpManager clientIpManager;

    // ==================== 写入 ====================

    /**
     * 记录一条管理员操作日志
     *
     * catch 住异常且不重新抛出，事务不会被标记为回滚，主业务照常提交——这正是想要的：
     * 「日志没写成」是运维问题，「审核失败」是业务事故，不能混为一谈。
     *
     * @param action 动作码；为 null 说明埋点漏传，记一条 warn 后跳过
     * @param targetId 对象 ID，可为 null
     * @param targetTitle 对象标题快照，可为 null
     * @param detail 处置细节，可为 null；调用方不得传入口令与正文
     */
    @Override
    public void record(OperationTypeEnum action, Long targetId, String targetTitle, String detail) {
        // ① 埋点漏传动作码属于代码缺陷，但不该让管理员的审核操作失败
        if (action == null) {
            log.warn("操作日志缺少动作码，已跳过 | targetId={} | detail={}", targetId, detail);
            return;
        }
        try {
            // ② 操作人与 IP 只解析一次：这两步都要碰登录态与请求上下文，属于「一次性上下文」
            Operator operator = currentOperator();
            String ip = currentIp();
            this.save(buildRow(action, targetId, targetTitle, detail, operator, ip));
        } catch (Exception e) {
            // ③ 只打一行：成功不打 info，失败不打整段入参，避免批量操作刷屏与隐私外泄
            log.warn("操作日志写入失败（不影响业务）| action={} | targetId={} | 原因={}",
                    action.getCode(), targetId, e.getMessage());
        }
    }

    /**
     * 批量记录（一次 saveBatch，不逐条往返）
     *
     * 每条目标单独一行，是为了让「这篇内容被谁处置过」能用 idx_target 直接反查；
     * 若合并成一行，事故排查就退化成全文搜索 detail。
     *
     * @param action 动作码
     * @param targets 目标列表，空列表直接返回
     * @param detail 本批共用的细节（如驳回理由）
     */
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

    // ==================== 查询 ====================

    /**
     * 分页查询操作日志（时间倒序）
     *
     * 排序必须「created_at DESC, id DESC」：datetime 只到秒，同一秒内的多行
     * （批量审核就是典型）若只按时间排，翻页会出现重复与漏行。
     *
     * @param request 查询条件，允许为 null
     * @return 分页结果（含中文标签）
     */
    @Override
    public Page<OperationLogVO> listByPage(OperationLogQueryRequest request) {
        OperationLogQueryRequest query = request == null ? new OperationLogQueryRequest() : request;

        LambdaQueryWrapper<StudioOperationLog> wrapper = new LambdaQueryWrapper<>();

        // ① 动作码：非法值报参数错误而不是静默忽略——忽略会让用户以为筛选生效了
        if (StrUtil.isNotBlank(query.getAction())) {
            OperationTypeEnum action = OperationTypeEnum.of(query.getAction().trim());
            ThrowUtils.throwIf(action == null, ErrorCode.PARAMS_ERROR, "未知的操作动作码");
            wrapper.eq(StudioOperationLog::getAction, action.getCode());
        }

        // ② 对象类型：同上
        if (StrUtil.isNotBlank(query.getTargetType())) {
            OperationTypeEnum.TargetType targetType = OperationTypeEnum.TargetType.of(query.getTargetType().trim());
            ThrowUtils.throwIf(targetType == null, ErrorCode.PARAMS_ERROR, "未知的对象类型");
            wrapper.eq(StudioOperationLog::getTargetType, targetType.getCode());
        }

        // ③ 时间范围：反了就是用户填错，明确报错；只填一端则按开区间查
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

        // ④ 操作人：账号与昵称都要能搜（管理员未必记得昵称，账号名往往是更好用的线索）
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

    /**
     * 动作字典
     *
     * 直接由枚举派生：前端下拉、表格标签、后端校验共用同一份定义，
     * 新增动作只改枚举即可（漏改前端也不会出现「筛不出来」）。
     *
     * @return 全部动作选项
     */
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

    // ==================== 内部实现 ====================

    /**
     * 拼一行日志实体（快照与截断都在这里完成）
     *
     * @param action 动作码
     * @param targetId 对象 ID
     * @param targetTitle 对象标题
     * @param detail 细节
     * @param operator 操作人快照
     * @param ip 来源 IP
     * @return 待落库的实体（审计四列由 MyMetaObjectHandler 填充）
     */
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

    /**
     * 按列宽截断（不是抛异常，也不是让数据库替我们拒绝整条日志）
     *
     * 刻意不用 StrUtil.maxLength：它会在末尾补 "..."，截断后仍超出列宽，
     * MySQL 严格模式下会直接报 Data too long——一条冗余日志不该让审核失败。
     *
     * @param text 原文，可为 null
     * @param maxLength 列宽
     * @return 截断后的文本；原文为 null 时返回 null
     */
    private String truncate(String text, int maxLength) {
        if (text == null) {
            return null;
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    /**
     * 当前操作人快照
     *
     * 无请求上下文的线程（定时任务、单元测试）调用 StpUtil 会抛 SaTokenContextException，
     * 这里必须兜住并回退到「系统」——审计字段的填充不能把主业务打挂。
     *
     * @return 操作人快照；未登录 / 上下文缺失时返回系统操作人
     */
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

    /**
     * 来源 IP（取不到返回 null，不因此报错）
     *
     * 走 ClientIpManager 而不是直接读请求头：只有 TCP 对端是可信代理时才采信
     * X-Real-IP / X-Forwarded-For，否则任何人加个头就能把操作日志指向别人。
     *
     * @return 归一化后的客户端 IP；无请求上下文或解析失败时为 null
     */
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

    /**
     * 实体转视图：补上两个中文标签
     *
     * @param row 实体
     * @return 视图对象
     */
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

    /**
     * 操作人快照（ID + 账号 + 昵称）
     *
     * 用 record 而非实体：它只在本类的三个方法之间传递，不需要可变性，
     * 也不该被 MyBatis-Plus 当成表映射对象。
     *
     * @param id 操作人用户 ID，0 表示系统
     * @param account 账号快照，可为空串
     * @param name 昵称快照，可为 null
     */
    private record Operator(long id, String account, String name) {

        /** 系统操作人：未登录、定时任务、无请求上下文时使用（与审计字段的 0 同义） */
        private static final Operator SYSTEM = new Operator(SYSTEM_OPERATOR_ID, "", null);
    }
}
