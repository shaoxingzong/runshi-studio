package com.bhu.runshistudioweb.model.vo.log;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 操作动作字典项（供前端下拉与标签文案）
 *
 * 由 OperationTypeEnum 直接派生，前端拿到的就是后端校验用的同一份字典：
 * 硬编码在前端的下拉，一旦后端新增动作就会出现「记录存在但筛不出来」的静默问题。
 *
 * @author shaoshing
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OperationActionOptionVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 动作码（提交筛选时原样回传） */
    private String code;

    /** 动作中文标签 */
    private String label;

    /** 对象类型码：POST / COMMENT / USER / QUIZ */
    private String targetType;

    /** 对象类型中文标签（下拉分组用） */
    private String targetTypeLabel;
}
