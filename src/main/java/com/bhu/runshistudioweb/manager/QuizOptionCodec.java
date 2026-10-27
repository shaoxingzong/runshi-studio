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
 * 单独抽出来是因为选项 JSON 会被管理端与 C 端两个 Service 读写。各写一份就有两个格式定义，
 * 改一处漏一处会让老数据在新代码下解析失败，而这种错只在某条老题目被抽中时才暴露。
 *
 * 用容器里的 JsonMapper（已被 JsonConfig 定制），不自己 new——默认实例不受全局配置影响。
 * 解析失败一律返回空列表并记 warn：一条改库改坏的脏数据不该让整个列表接口 500。
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
     * 选项在库里只有一列，正确答案另存一列——这种「展示数据与判定数据物理分离」是刷题模块的
     * 安全底线：C 端 VO 结构上没有答案字段，前端抓包也拿不到答案。
     *
     * 坑：长度必须在入参侧拦住。等数据库来报错，前端拿到的是驱动抛出的截断异常，
     * 管理员完全看不出「是哪一项太长」。
     *
     * @param options 选项列表（只含标签与正文，不含「哪个是正确项」的信息）；
     * 传 null 或空列表会抛异常
     * @return 可直接写入选项列的 JSON 字符串，非 null
     * @throws BusinessException 选项为空、序列化失败或结果超过列宽时抛出（A0401）
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
     * 约定「永不返回 null」：调用方通常直接拿它去 stream 或遍历，返回 null 等于把判空责任
     * 推给每一个调用点。
     *
     * 坑一：把这里改成抛异常是最容易犯的错——它看起来更严谨，实际会把一条脏数据放大成
     * 整个接口不可用。
     * 坑二：不要做「解析失败就写回空 JSON」的自愈，那会把原始数据永久抹掉，连修都没得修。
     *
     * @param json 数据库里的 JSON 字符串，可为 null / 空白串
     * @return 选项列表；入参为空或解析失败时返回空列表（不是 null）
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
     * 取出选项的标签集合，保持选项在列表里的原顺序
     *
     * 两个用途：判断「正确答案是否都属于已有选项」，以及比较「选项标签集合有没有变」。
     * 顺序刻意保留——顺序一致才能发现「A 和 B 对调了」这种改动。
     *
     * 坑：不做去重也不排序。若选项里出现两个 A，返回的列表就有两个 A，
     * 拿它做长度校验时要当心。
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
