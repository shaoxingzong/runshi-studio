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
     * @param options 选项列表（只含标签与正文，不含「哪个是正确项」的信息）；传 null 或空列表会抛异常
     * @return 可直接写入选项列的 JSON 字符串，非 null
     * @throws BusinessException 选项为空、序列化失败或结果超过列宽时抛出（A0401）
     */
    public String encode(List<QuizOptionVO> options) {
        // 空选项在界面上是「没有选项可点」，属于不可用数据，序列化前就拒绝
        if (options == null || options.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "选项不能为空");
        }
        String json;
        try {
            // 用容器里的 JsonMapper：自己 new 的实例不受全局配置影响，序列化行为会与其它模块不一致
            json = jsonMapper.writeValueAsString(options);
        } catch (Exception e) {
            // 理论上不会发生（List<String> 一定能序列化），兜住避免变成 B0001
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "选项格式不正确");
        }
        if (json.length() > QuizConstant.OPTIONS_JSON_MAX_LENGTH) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "选项内容过长");
        }
        return json;
    }

    /**
     * 把库里的选项 JSON 解析回选项列表，供出题与编辑回填使用
     *
     * @param json 数据库里的 JSON 字符串，可为 null / 空白串
     * @return 选项列表；入参为空或解析失败时返回空列表（不是 null）
     */
    public List<QuizOptionVO> decode(String json) {
        // 空值短路：isBlank 比 isEmpty 更宽，能兜住库里存的纯空格
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            // TypeReference 保留泛型：否则只能还原成 List<LinkedHashMap>
            List<QuizOptionVO> options = jsonMapper.readValue(json, new TypeReference<List<QuizOptionVO>>() {
            });
            // JSON 字面量 null 会得到 null，统一转成空列表，出口只有一种「空」
            return options == null ? new ArrayList<>() : options;
        } catch (Exception e) {
            // 脏数据不该让接口 500：记下长度便于定位，对外表现为「没有选项」
            log.warn("选项 JSON 解析失败，按空列表处理 | 长度={} | 原因={}", json.length(), e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 取出选项的标签集合，保持选项在列表里的原顺序
     *
     * @param options 选项列表，可为 null；元素或元素的 label 为 null 时会被跳过
     * @return 标签列表，保持原顺序；入参为空时返回空列表（不是 null）
     */
    public List<String> labelsOf(List<QuizOptionVO> options) {
        List<String> labels = new ArrayList<>();
        // null 与空列表同义：都表示「没有标签」
        if (options == null) {
            return labels;
        }
        for (QuizOptionVO option : options) {
            // 脏数据可能带 null 元素或 null 标签，跳过比让调用方踩空指针便宜
            if (option != null && option.getLabel() != null) {
                labels.add(option.getLabel());
            }
        }
        return labels;
    }
}
