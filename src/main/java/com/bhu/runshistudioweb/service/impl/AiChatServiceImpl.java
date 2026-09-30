package com.bhu.runshistudioweb.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.convert.Convert;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bhu.runshistudioweb.config.AiProperties;
import com.bhu.runshistudioweb.constant.AiChatConstant;
import com.bhu.runshistudioweb.exception.ErrorCode;
import com.bhu.runshistudioweb.exception.ThrowUtils;
import com.bhu.runshistudioweb.manager.AiManager;
import com.bhu.runshistudioweb.mapper.StudioAiMessageMapper;
import com.bhu.runshistudioweb.mapper.StudioAiSessionMapper;
import com.bhu.runshistudioweb.mapper.SysUserMapper;
import com.bhu.runshistudioweb.model.dto.ai.AiChatRequest;
import com.bhu.runshistudioweb.model.entity.StudioAiMessage;
import com.bhu.runshistudioweb.model.entity.StudioAiSession;
import com.bhu.runshistudioweb.model.entity.SysUser;
import com.bhu.runshistudioweb.model.enums.AiMessageRoleEnum;
import com.bhu.runshistudioweb.model.vo.AiChatResponseVO;
import com.bhu.runshistudioweb.model.vo.AiMessageVO;
import com.bhu.runshistudioweb.service.AiChatService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * AI 咨询服务实现
 *
 * author: shaoshing
 *
 * <p><b>本类的四道关键约束（改代码前先读）</b>：
 * <ol>
 *     <li><b>会话归属</b>：绑定用户的会话仅本人可续；匿名会话凭不可枚举雪花 ID 可续。
 *     不存在与无权<b>统一返回 40400 同一句提示</b>，不暴露存在性；</li>
 *     <li><b>配额自增必须原子</b>：{@code SET ai_query_count = ai_query_count + 1}，
 *     绝不「读出来 +1 再写回去」（并发下互相覆盖，计数偏小＝白送额度）；</li>
 *     <li><b>HTTP 在事务之外</b>：AI 调用可能几十秒，包进事务会长时间占用数据库连接。
 *     失败时保留用户消息，返回 50001；成功后才用 {@code TransactionTemplate}
 *     把「assistant 消息 + 会话 updated_at + 计数」包成一步写；</li>
 *     <li><b>排序必须带 id</b>：{@code created_at} 只有秒级精度，同一秒的
 *     user / assistant 两条消息时间相同，只按时间排序会出现对话记录偶发颠倒。</li>
 * </ol>
 *
 * <p><b>为什么 TransactionTemplate 是「自己 new 的」</b>：Boot 4 的
 * {@code TransactionAutoConfiguration} 只提供 {@code TransactionalOperator}（响应式事务），
 * 容器里<b>没有</b> {@code TransactionTemplate} bean（Boot 3 时代有）。直接
 * {@code @Resource TransactionTemplate} 会启动失败——这里注入
 * {@code PlatformTransactionManager}（DataSource 事务管理器一定存在）再自行构造。
 *
 * <p><b>为什么用 TransactionTemplate 而不是 @Transactional</b>：需要包进事务的
 * 只有「调用 AI 成功之后」那一小段。若把整个 chat 方法标上 {@code @Transactional}，
 * 那条外部 HTTP 请求就落进了事务里——这正是本任务要避免的事。
 */
@Slf4j
@Service
public class AiChatServiceImpl implements AiChatService {

    /** 会话不存在 / 无权访问的统一提示：两种情况共用一句，不暴露存在性 */
    private static final String SESSION_NOT_FOUND_MESSAGE = "会话不存在";

    /**
     * 配额原子自增 SQL
     *
     * <p>硬编码列名是安全的：它是常量，不来自用户输入。用 {@code setSql} 是
     * MyBatis-Plus 表达「列 = 列 + 1」的标准方式（Lambda 写不了自引用表达式）。
     */
    private static final String SQL_INCREASE_QUERY_COUNT = "ai_query_count = ai_query_count + 1";

    @Resource
    private StudioAiSessionMapper studioAiSessionMapper;

    @Resource
    private StudioAiMessageMapper studioAiMessageMapper;

    @Resource
    private SysUserMapper sysUserMapper;

    @Resource
    private AiManager aiManager;

    @Resource
    private AiProperties aiProperties;

    /** 见类注释：容器里没有 TransactionTemplate bean，这里自行构造 */
    private final TransactionTemplate transactionTemplate;

    public AiChatServiceImpl(PlatformTransactionManager transactionManager) {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public AiChatResponseVO chat(AiChatRequest request) {
        // 请求体整体为 null 时 @Valid 不会触发，兜一层避免后面 getMessage() 抛 NPE 变成 500
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        // trim 后再判空与计长：避免「   」这种纯空格绕过必填校验
        String message = StrUtil.trim(request.getMessage());
        ThrowUtils.throwIf(StrUtil.isBlank(message), ErrorCode.PARAMS_ERROR, "提问内容不能为空");
        // 长度校验与 DTO 上的 @Size 双保险：Service 也可能被非 Web 入口直接调用
        ThrowUtils.throwIf(message.length() > AiChatConstant.MESSAGE_MAX_LENGTH,
                ErrorCode.PARAMS_ERROR, "提问内容不能超过 " + AiChatConstant.MESSAGE_MAX_LENGTH + " 字");

        // 当前登录用户：null 表示游客（游客也能提问，只是不计配额）
        Long userId = currentUserIdOrNull();

        // ① 会话：sessionId 为空则新建（标题取首问前 30 字）；非空则校验存在性与归属
        StudioAiSession session = resolveSession(request.getSessionId(), userId, message);
        long sessionId = session.getId();

        // ② 配额预检：仅登录用户计数；游客的限流属于后续工程化任务（DESIGN 已登记）
        if (userId != null) {
            assertQuotaAvailable(userId);
        }

        // ③ 上下文窗口：取最近 10 条（内部已翻正为时间正序），交给 Manager 拼 prompt
        List<AiMessageVO> history = recentMessages(sessionId, AiChatConstant.CONTEXT_MESSAGE_COUNT);

        // ④ 先落用户消息，再调 AI —— 顺序不能反。
        // 反过来的话，AI 失败时这条提问就丢了：用户明明问了，历史里却什么都没有，
        // 而且前端往往已经把它渲染在界面上了
        insertMessage(sessionId, AiMessageRoleEnum.USER, message);

        // ⑤ 调 AI：注意这里**没有** @Transactional，HTTP 请求不在数据库事务内
        String answer = aiManager.chat(aiProperties.getSystemPrompt(), history, message);

        // ⑥ 成功：三处写必须原子（assistant 消息 + 会话 updated_at + 配额计数）
        transactionTemplate.executeWithoutResult(status -> {
            insertMessage(sessionId, AiMessageRoleEnum.ASSISTANT, answer);
            touchSession(sessionId);
            if (userId != null) {
                increaseQueryCount(userId);
            }
        });

        AiChatResponseVO responseVO = new AiChatResponseVO();
        // 每次回答都回传会话 ID：前端首次提问后存下它，即可无感续聊
        responseVO.setSessionId(sessionId);
        responseVO.setAnswer(answer);
        return responseVO;
    }

    @Override
    public List<AiMessageVO> listHistory(String sessionId) {
        long parsedSessionId = parseSessionId(sessionId, "会话 id 不能为空");
        // 历史接口的归属校验与提问完全一致：否则它会成为「探测某会话是否存在」的入口
        Long userId = currentUserIdOrNull();
        assertSessionAccessible(parsedSessionId, userId);
        return recentMessages(parsedSessionId, AiChatConstant.HISTORY_MESSAGE_LIMIT);
    }

    // ==================== 会话定位与归属 ====================

    /**
     * 定位会话：sessionId 为空则新建，否则校验存在性与归属
     *
     * @param sessionIdText 请求里的会话 ID（可为空）
     * @param userId        当前登录用户 ID（null 表示游客）
     * @param message       本次提问（新建会话时用于生成标题）
     * @return 会话实体
     */
    private StudioAiSession resolveSession(String sessionIdText, Long userId, String message) {
        if (StrUtil.isBlank(sessionIdText)) {
            StudioAiSession session = new StudioAiSession();
            // userId 为 null 即匿名会话：游客凭不可枚举的雪花 ID 续聊
            session.setUserId(userId);
            session.setTitle(titleOf(message));
            ThrowUtils.throwIf(studioAiSessionMapper.insert(session) != 1,
                    ErrorCode.OPERATION_ERROR, "创建会话失败");
            return session;
        }

        long sessionId = parseSessionId(sessionIdText, "会话 id 不合法");
        StudioAiSession session = studioAiSessionMapper.selectById(sessionId);
        // selectById 自动过滤 deleted_at = 0：已删除的会话同样按「不存在」处理
        ThrowUtils.throwIf(session == null, ErrorCode.NOT_FOUND_ERROR, SESSION_NOT_FOUND_MESSAGE);
        assertSessionOwner(session, userId);
        return session;
    }

    /**
     * 校验会话可访问（存在 + 归属），供历史接口使用
     *
     * @param sessionId 会话 ID
     * @param userId    当前登录用户 ID（null 表示游客）
     */
    private void assertSessionAccessible(long sessionId, Long userId) {
        StudioAiSession session = studioAiSessionMapper.selectById(sessionId);
        ThrowUtils.throwIf(session == null, ErrorCode.NOT_FOUND_ERROR, SESSION_NOT_FOUND_MESSAGE);
        assertSessionOwner(session, userId);
    }

    /**
     * 会话归属校验
     *
     * <p>规则：匿名会话（user_id 为 null）任何人都可凭 ID 访问；
     * 绑定用户的会话只有本人可访问，其他人（含游客）一律按「不存在」处理。
     *
     * @param session 会话实体
     * @param userId  当前登录用户 ID（null 表示游客）
     */
    private void assertSessionOwner(StudioAiSession session, Long userId) {
        if (session.getUserId() == null) {
            // 匿名会话：19 位雪花 ID 无法被枚举，「知道 ID」即等价于持有凭据
            return;
        }
        // 越权与不存在返回**完全相同**的 40400 与提示：
        // 否则攻击者可以用「40400 提示是否有差异」来判断会话是否存在
        ThrowUtils.throwIf(!Objects.equals(session.getUserId(), userId),
                ErrorCode.NOT_FOUND_ERROR, SESSION_NOT_FOUND_MESSAGE);
    }

    /**
     * 解析会话 ID 文本
     *
     * @param sessionIdText 会话 ID 文本
     * @param blankMessage  文本为空时的提示（提问与历史接口的提示不同，故作为参数传入）
     * @return 会话 ID
     */
    private long parseSessionId(String sessionIdText, String blankMessage) {
        ThrowUtils.throwIf(StrUtil.isBlank(sessionIdText), ErrorCode.PARAMS_ERROR, blankMessage);
        // 用 Convert 而不是 Long.valueOf：脏值（如 "abc"）会得到 null 走 40000，
        // 而不是抛 NumberFormatException 变成 500
        Long sessionId = Convert.toLong(sessionIdText, null);
        ThrowUtils.throwIf(sessionId == null || sessionId <= 0, ErrorCode.PARAMS_ERROR, "会话 id 不合法");
        return sessionId;
    }

    /**
     * 生成会话标题：首问的前 30 个字
     *
     * @param message 首次提问内容（已 trim 且非空）
     * @return 会话标题
     */
    private String titleOf(String message) {
        return message.length() <= AiChatConstant.SESSION_TITLE_MAX_LENGTH
                ? message
                : message.substring(0, AiChatConstant.SESSION_TITLE_MAX_LENGTH);
    }

    // ==================== 配额 ====================

    /**
     * 配额预检：已达上限则返回 42900
     *
     * <p>这里读的是「当前值」，并发下两个请求可能同时通过预检，
     * 最终略微超出上限——这是刻意接受的：配额是「防护性上限」而不是计费依据，
     * 为此加锁得不偿失。真正不能错的是自增本身必须原子（见 {@link #increaseQueryCount}）。
     *
     * @param userId 登录用户 ID
     */
    private void assertQuotaAvailable(long userId) {
        SysUser user = sysUserMapper.selectById(userId);
        ThrowUtils.throwIf(user == null, ErrorCode.NOT_LOGIN_ERROR, "登录用户不存在");

        int limit = aiProperties.getQueryLimit() == null ? Integer.MAX_VALUE : aiProperties.getQueryLimit();
        int used = user.getAiQueryCount() == null ? 0 : user.getAiQueryCount();
        ThrowUtils.throwIf(used >= limit, ErrorCode.TOO_MANY_REQUESTS_ERROR,
                "提问次数已达上限（" + limit + " 次），请稍后再试");
    }

    /**
     * 配额 +1（<b>原子自增</b>，仅登录用户）
     *
     * <p>绝不能写成「select 出 aiQueryCount → +1 → updateById」：
     * 两个并发请求会读到同一个旧值，各自写回 old+1，结果是「问了两次只加了一次」。
     * 交给数据库做 {@code SET ai_query_count = ai_query_count + 1} 才是安全的。
     *
     * @param userId 登录用户 ID
     */
    private void increaseQueryCount(long userId) {
        LambdaUpdateWrapper<SysUser> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(SysUser::getId, userId).setSql(SQL_INCREASE_QUERY_COUNT);
        // 传 null 实体：MyBatis-Plus 会跳过 MetaObjectHandler 的 updateFill（拿不到实体），
        // 这也正是我们想要的——配额变更不该改动审计字段
        sysUserMapper.update(null, wrapper);
    }

    // ==================== 消息读写 ====================

    /**
     * 取会话的最近 N 条消息（返回时已翻正为时间正序）
     *
     * @param sessionId 会话 ID
     * @param limit     取多少条
     * @return 消息 VO 列表（旧 → 新）
     */
    private List<AiMessageVO> recentMessages(long sessionId, int limit) {
        LambdaQueryWrapper<StudioAiMessage> wrapper = new LambdaQueryWrapper<>();
        // 排序键用 id 而不是 created_at：created_at 秒级精度，
        // 同秒的 user / assistant 会并列，导致对话顺序偶发颠倒（见实体注释）。
        // 雪花 id 单调递增，等价于时间序且唯一
        wrapper.eq(StudioAiMessage::getSessionId, sessionId)
                .orderByDesc(StudioAiMessage::getId);

        // searchCount=false：这里只要数据不要总数，省掉一次 COUNT 查询
        Page<StudioAiMessage> page = studioAiMessageMapper.selectPage(new Page<>(1, limit, false), wrapper);

        // 查询是「倒序取最近 N 条」，返回前必须翻正：对话界面是旧 → 新自上而下
        List<StudioAiMessage> records = new ArrayList<>(page.getRecords());
        Collections.reverse(records);
        return records.stream().map(this::toMessageVO).toList();
    }

    /**
     * 落一条消息
     *
     * @param sessionId 会话 ID
     * @param role      消息角色
     * @param content   消息正文
     */
    private void insertMessage(long sessionId, AiMessageRoleEnum role, String content) {
        StudioAiMessage entity = new StudioAiMessage();
        entity.setSessionId(sessionId);
        entity.setRole(role.getValue());
        entity.setContent(content);
        ThrowUtils.throwIf(studioAiMessageMapper.insert(entity) != 1,
                ErrorCode.OPERATION_ERROR, "保存消息失败");
    }

    /**
     * 刷新会话的 updated_at（用于「最近活跃会话」排序）
     *
     * <p>用「只带 id 的实体」调 updateById：MP 会跳过 null 字段，
     * 再加上 MetaObjectHandler 的 updateFill，等价于只更新 updated_at / updated_by。
     *
     * @param sessionId 会话 ID
     */
    private void touchSession(long sessionId) {
        StudioAiSession touch = new StudioAiSession();
        touch.setId(sessionId);
        studioAiSessionMapper.updateById(touch);
    }

    /**
     * 实体转消息 VO
     *
     * <p>字段名与类型一一对应（id / role / content / createdAt），可整体拷贝；
     * VO 里没有 sessionId 与审计字段，所以脱敏是「结构上不可能泄露」。
     *
     * @param message 消息实体
     * @return 消息 VO
     */
    private AiMessageVO toMessageVO(StudioAiMessage message) {
        AiMessageVO messageVO = new AiMessageVO();
        BeanUtils.copyProperties(message, messageVO);
        return messageVO;
    }

    /**
     * 取当前登录用户 ID
     *
     * @return 用户 ID；未登录（游客）或 loginId 非数字时返回 null
     */
    private Long currentUserIdOrNull() {
        if (!StpUtil.isLogin()) {
            return null;
        }
        // 本项目登录统一用 Long 型用户 id；万一将来出现非数字 loginId，
        // 这里按游客处理而不是抛异常——提问不应该因为一个 id 形态问题变成 500
        return Convert.toLong(StpUtil.getLoginId(), null);
    }
}
