package com.bhu.runshistudioweb.manager;

import com.bhu.runshistudioweb.constant.QuizConstant;
import com.bhu.runshistudioweb.exception.BusinessException;
import com.bhu.runshistudioweb.exception.ErrorCode;
import com.bhu.runshistudioweb.model.vo.quiz.QuizOptionVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 选项 JSON 编解码器
 *
 * 为什么单独抽一个 Manager，而不是在 Service 里写两个私有方法：
 * 题目的选项 JSON 会被两个 Service 读写——管理端的
 * QuizQuestionServiceImpl（增删改查）与 C 端的
 * QuizPracticeServiceImpl（出题与错题详情）。
 * 如果各写一份，「序列化格式」就有两个定义，改一处漏一处会让
 * 老数据在新代码下解析失败，而这种错只在线上某条老题目被抽中时才暴露。
 *
 * 用容器里的 JsonMapper（Boot 4 的 Jackson 3 自动配置，
 * 且已被 JsonConfig 定制），不自己 new——自己 new 出来的实例
 * 不受全局配置影响，与项目其它模块的序列化行为会不一致。
 *
 * 解析失败一律返回空列表并记 warn，绝不抛异常（与
 * StudioProjectServiceImpl#parseTechStack 同一条纪律）：
 * 一条手工改库改坏的脏数据，不该让整个列表接口 500。
 * 空选项的题目在界面上表现为「没有选项可点」，是可发现、可修复的；
 * 而 500 会让整个页面不可用，且用户看不到任何线索。
 *
 * 本类不判断业务规则：选项数量够不够、正确项在不在选项里，都由
 * QuizQuestionServiceImpl 的校验负责。它只负责「把对象变成字符串」与
 * 「把字符串变回对象」这两件事。
 *
 * @author shaoshing
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QuizOptionCodec {

    private final JsonMapper jsonMapper;

    /**
     * 把选项列表序列化成 JSON 快照，准备写进题目表
     *
     * <p>为什么这样设计：选项在库里只有一列（text 类型），存的是「标签 + 正文」的 JSON 数组，
     * 正确答案另存一列。这种「展示数据与判定数据物理分离」是刷题模块的安全底线——
     * C 端题目的 VO 结构上没有答案字段，前端即使抓包也拿不到答案。
     * 本方法还刻意在入库前拦住超长内容：如果等数据库来报错，前端拿到的是驱动抛出的
     * 截断异常，管理员完全看不出「是哪一项太长」，而在入参侧拦下就能给出明确的提示。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 先判空：空选项的题目在界面上就是「没有选项可点」，属于不可用的数据，
     *     必须在序列化之前就拒绝，而不是存一条空 JSON 让问题延后暴露；</li>
     *     <li>② 再序列化：走容器里的 JsonMapper 而不是自己 new，保证与项目其它接口
     *     使用同一套全局配置（日期格式、null 处理等）；</li>
     *     <li>③ 序列化异常转成参数错误：理论上 List 一定能序列化，兜住它只是为了让
     *     万一出现的异常有一个明确的错误码，而不是落到全局兜底的 B0001；</li>
     *     <li>④ 最后校验长度：长度检查必须放在拿到真实 JSON 之后，因为转义与中文
     *     都会改变字节数，无法用「选项条数 × 每条字数」估算。</li>
     * </ol>
     *
     * <p>容易踩的坑：长度判断用的是 String.length()（字符数）而不是字节数，
     * 与列宽的口径一致即可；若将来把列换成更严格的字符集，这里要同步调整，
     * 否则会出现「校验通过、入库仍被截断」的情况。
     *
     * @param options 选项列表（只含标签与正文，不含任何「哪个是正确项」的信息）；
     *                传 null 或空列表会抛异常，调用方需先校验
     * @return 可直接写入选项列的 JSON 字符串，非 null
     * @throws BusinessException 选项为空、序列化失败或结果超过列宽时抛出（错误码 A0401，
     *         即参数错误，调用方不必捕获，交给全局异常处理器转成响应）
     */
    public String encode(List<QuizOptionVO> options) {
        // ① 空选项在界面上是「没有选项可点」，属于不可用数据，序列化前就拒绝
        if (options == null || options.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "选项不能为空");
        }
        String json;
        try {
            // ② 用容器里的 JsonMapper：自己 new 的实例不受全局配置影响，序列化行为会与其它模块不一致
            json = jsonMapper.writeValueAsString(options);
        } catch (Exception e) {
            // ③ 理论上不会发生（List<String> 一定能序列化），兜住避免变成 B0001
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "选项格式不正确");
        }
        // ④ 长度必须在入参侧拦住：否则会撞 text 列的截断 / 报错，
        // 前端拿到的是数据库异常而不是「选项内容过长」的明确提示
        if (json.length() > QuizConstant.OPTIONS_JSON_MAX_LENGTH) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "选项内容过长");
        }
        return json;
    }

    /**
     * 把库里的选项 JSON 解析回选项列表，供出题与编辑回填使用
     *
     * <p>为什么这样设计：解析失败一律返回空列表并记 warn，绝不向上抛异常。
     * 一条被手工改库改坏的脏数据，不该让整个列表接口 500——空选项的题目在界面上
     * 表现为「没有选项可点」，这是可发现、可修复的；而 500 会让整个页面不可用，
     * 用户和排查的人都看不到任何线索。同理，返回值约定「永不返回 null」：
     * 调用方通常直接拿它去 stream 或遍历，返回 null 等于把判空责任推给每一个调用点。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 空值短路：null 与空白串都视为「没有选项」，直接给空列表。
     *     用 isBlank 而不是 isEmpty，是因为库里可能存的是空格串；</li>
     *     <li>② 反序列化：用 TypeReference 保留泛型信息，否则 Jackson 只能还原成
     *     List&lt;LinkedHashMap&gt;，取出来再强转会抛 ClassCastException；</li>
     *     <li>③ 结果兜底：JSON 里若是字面量 null，反序列化会返回 null，
     *     这里转成空列表，保证方法出口只有一种「空」的形态；</li>
     *     <li>④ 异常兜底：把原因记进日志（含长度，便于定位是哪条数据），
     *     对调用方则表现为「没有选项」，让页面继续可用。</li>
     * </ol>
     *
     * <p>容易踩的坑：把这里改成抛异常是最容易犯的错——它看起来「更严谨」，
     * 实际会把一条脏数据放大成整个接口不可用。另外不要在这里做「解析失败就写回空 JSON」
     * 的自愈：那会把原始数据永久抹掉，管理员连修都没得修。
     *
     * @param json 数据库里的 JSON 字符串，可为 null / 空白串
     * @return 选项列表；入参为空或解析失败时返回空列表（不是 null），调用方可直接遍历
     */
    public List<QuizOptionVO> decode(String json) {
        // ① 空值短路：isBlank 比 isEmpty 更宽，能兜住库里存的纯空格
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            // ② TypeReference 保留泛型：否则只能还原成 List<LinkedHashMap>
            List<QuizOptionVO> options = jsonMapper.readValue(json, new TypeReference<List<QuizOptionVO>>() {
            });
            // ③ JSON 字面量 null 会得到 null，统一转成空列表，出口只有一种「空」
            return options == null ? new ArrayList<>() : options;
        } catch (Exception e) {
            // ④ 脏数据不该让接口 500：记下长度便于定位，对外表现为「没有选项」
            log.warn("选项 JSON 解析失败，按空列表处理 | 长度={} | 原因={}", json.length(), e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 取出选项的标签集合（保持选项在列表里的原顺序）
     *
     * <p>为什么这样设计：这个结果有两个用途——判断「正确答案是否都属于已有选项」，
     * 以及判断「选项标签集合有没有变」（改题时决定要不要重置错题连对进度）。
     * 两个用途都只关心标签、不关心正文，所以单独抽出来，避免调用方各自写
     * stream().map().collect() 时漏掉「元素可能为 null」这一层。
     * 顺序被刻意保留：调用方用 equals 比较前后两次的标签列表时，
     * 顺序一致才能发现「A 和 B 对调了」这种改动。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 先建空列表：保证任何分支都返回非 null，调用方可直接 contains / equals；</li>
     *     <li>② null 直接返回空列表：与 decode 的「空值同义」约定保持一致；</li>
     *     <li>③ 逐个取标签并跳过 null 元素与 null 标签：选项列表可能来自解析失败的脏数据，
     *     提前跳过比让调用方在 contains 时踩空指针便宜得多。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法不做去重也不排序。若选项里出现两个 A，返回的列表就有两个 A，
     * 调用方若拿它做「正确答案是否都在选项内」的判断不会出错，但拿它做长度校验时要当心。
     * 标签的连续性由录题时的校验负责，这里只如实反映现状。
     *
     * @param options 选项列表，可为 null；元素或元素的 label 为 null 时会被跳过
     * @return 标签列表，保持原顺序；入参为空时返回空列表（不是 null）
     */
    public List<String> labelsOf(List<QuizOptionVO> options) {
        List<String> labels = new ArrayList<>();
        // ② null 与空列表同义：都表示「没有标签」
        if (options == null) {
            return labels;
        }
        for (QuizOptionVO option : options) {
            // ③ 脏数据可能带 null 元素或 null 标签，跳过比让调用方踩空指针便宜
            if (option != null && option.getLabel() != null) {
                labels.add(option.getLabel());
            }
        }
        return labels;
    }
}
