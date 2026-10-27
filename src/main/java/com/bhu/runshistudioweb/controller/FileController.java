package com.bhu.runshistudioweb.controller;

import com.bhu.runshistudioweb.common.BaseResponse;
import com.bhu.runshistudioweb.common.ResultUtils;
import com.bhu.runshistudioweb.manager.FileManager;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文件管理接口
 *
 * 本接口不在白名单里，必须登录后才能上传（匿名上传等于给攻击者一个免费图床）。
 * 方法上没有 {@code @SaCheckLogin} 不是漏写：不在白名单的路径先过全局登录拦截。
 * 上传后的 /uploads/** 是公开读，读开放、写受限。
 *
 * @author shaoshing
 */
@Tag(name = "文件管理")
@RestController
@RequestMapping("/file")
@RequiredArgsConstructor
public class FileController {

    private final FileManager fileManager;

    /**
     * 文件上传
     *
     * 校验与落盘全在 FileManager，三层缺一不可：魔数判真实类型、UUID 重命名、路径规范化。
     * 请求必须是 multipart/form-data 且前端不手写 Content-Type，否则丢 boundary 解析不出文件。
     *
     * @param file 字段名固定为 file，只允许 PNG 与 JPEG（按魔数判定，改后缀绕不过去）
     * @return 相对访问路径，形如 /uploads/yyyy/MM/{uuid}.png，域名由前端拼接
     */
    @Operation(summary = "文件上传")
    @PostMapping("/upload")
    public BaseResponse<String> upload(@RequestParam("file") MultipartFile file) {
        // ① Spring 按字段名 file 从 multipart 请求里取出文件；名字与前端 append 时不一致就会得到 null
        // ② 三层安全防线（魔数、UUID 重命名、路径边界）都在 FileManager 里，Controller 不重复实现
        String accessPath = fileManager.upload(file);
        // ③ 只回相对路径：域名由前端拼接，避免把本地地址写进生产数据
        return ResultUtils.success(accessPath);
    }
}
