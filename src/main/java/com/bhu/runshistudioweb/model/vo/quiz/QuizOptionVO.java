package com.bhu.runshistudioweb.model.vo.quiz;

import lombok.Data;

import java.io.Serializable;

/**
 * 选项视图对象（C 端与错题详情共用）
 *
 * @author shaoshing
 */
@Data
public class QuizOptionVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 选项标签：A–F */
    private String label;

    /** 选项正文 */
    private String content;
}
