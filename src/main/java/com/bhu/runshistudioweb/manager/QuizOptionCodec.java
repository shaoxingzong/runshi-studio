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

// 选项 JSON 编解码器
//
// 为什么单独抽一个 Manager，而不是在 Service 里写两个私有方法：
// 题目的选项 JSON 会被两个 Service 读写——管理端的
// QuizQuestionServiceImpl（增删改查）与 C 端的
// QuizPracticeServiceImpl（出题与错题详情）。
// 如果各写一份，「序列化格式」就有两个定义，改一处漏一处会让
// 老数据在新代码下解析失败，而这种错只在线上某条老题目被抽中时才暴露。
//
// 用容器里的 JsonMapper（Boot 4 的 Jackson 3 自动配置，
// 且已被 JsonConfig 定制），不自己 new——自己 new 出来的实例
// 不受全局配置影响，与项目其它模块的序列化行为会不一致。
//
// 解析失败一律返回空列表并记 warn，绝不抛异常（与
// StudioProjectServiceImpl#parseTechStack 同一条纪律）：
// 一条手工改库改坏的脏数据，不该让整个列表接口 500。
// 空选项的题目在界面上表现为「没有选项可点」，是可发现、可修复的；
// 而 500 会让整个页面不可用，且用户看不到任何线索。
@Slf4j
@Component
@RequiredArgsConstructor
public class QuizOptionCodec {

    private final JsonMapper jsonMapper;

    // 把选项列表序列化成 JSON 快照
    //
    // @param options 选项列表（不含正确性）
    // @return JSON 字符串
    // @throws BusinessException 序列化失败或结果超过列宽时抛出（A0401）
    public String encode(List<QuizOptionVO> options) {
        if (options == null || options.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "选项不能为空");
        }
        String json;
        try {
            json = jsonMapper.writeValueAsString(options);
        } catch (Exception e) {
            // 理论上不会发生（List<String> 一定能序列化），兜住避免变成 B0001
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "选项格式不正确");
        }
        // 长度必须在入参侧拦住：否则会撞 text 列的截断 / 报错，
        // 前端拿到的是数据库异常而不是「选项内容过长」的明确提示
        if (json.length() > QuizConstant.OPTIONS_JSON_MAX_LENGTH) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "选项内容过长");
        }
        return json;
    }

    // 把 JSON 快照解析回选项列表
    //
    // @param json 数据库里的 JSON 字符串，可为空
    // @return 选项列表；为空或解析失败时返回空列表（不返回 null）
    public List<QuizOptionVO> decode(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            List<QuizOptionVO> options = jsonMapper.readValue(json, new TypeReference<List<QuizOptionVO>>() {
            });
            return options == null ? new ArrayList<>() : options;
        } catch (Exception e) {
            log.warn("选项 JSON 解析失败，按空列表处理 | 长度={} | 原因={}", json.length(), e.getMessage());
            return new ArrayList<>();
        }
    }

    // 取选项的标签集合（保持原顺序）
    //
    // 用于「正确答案必须是已有选项」的校验，以及判断
    // 「选项标签集合是否变化」（判定相关变更检测）。
    //
    // @param options 选项列表
    // @return 标签列表；入参为空时返回空列表
    public List<String> labelsOf(List<QuizOptionVO> options) {
        List<String> labels = new ArrayList<>();
        if (options == null) {
            return labels;
        }
        for (QuizOptionVO option : options) {
            if (option != null && option.getLabel() != null) {
                labels.add(option.getLabel());
            }
        }
        return labels;
    }
}
