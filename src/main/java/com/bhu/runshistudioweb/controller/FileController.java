package com.bhu.runshistudioweb.controller;

import com.bhu.runshistudioweb.common.BaseResponse;
import com.bhu.runshistudioweb.common.ResultUtils;
import com.bhu.runshistudioweb.manager.FileManager;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文件管理接口
 *
 * author: shaoshing
 *
 * <p>Controller 只负责接参与返回，文件校验、落盘、路径拼装全部在 {@link FileManager}。
 * 这样文件逻辑可以被其他入口复用（例如以后后台批量导入成员头像），
 * 也便于脱离 Web 环境做单元测试。
 *
 * <p><b>鉴权</b>：本接口**不在** Sa-Token 白名单里，即必须登录后才能上传
 * （匿名上传等于给攻击者一个免费的图床，极易被用来托管违规内容）。
 * 而上传成功后返回的 {@code /uploads/**} 访问路径是公开的——游客看得到图，
 * 但不能上传，这正是我们想要的边界。
 */
@Tag(name = "文件管理")
@RestController
@RequestMapping("/file")
public class FileController {

    @Resource
    private FileManager fileManager;

    /**
     * 文件上传
     *
     * <p>请求必须是 {@code multipart/form-data}，字段名为 {@code file}：
     * 前端 {@code new FormData()} 后 {@code formData.append("file", file)}，
     * 不要手动设置 Content-Type——浏览器需要自己补 boundary，手写会导致后端解析不到文件。
     *
     * @param file 上传的文件（仅允许 PNG / JPEG，真实类型由魔数判定）
     * @return 相对访问路径，如 {@code /uploads/2026/09/xxxx.png}；前端拼上 /api 前缀即可展示
     */
    @Operation(summary = "文件上传")
    @PostMapping("/upload")
    public BaseResponse<String> upload(@RequestParam("file") MultipartFile file) {
        String accessPath = fileManager.upload(file);
        return ResultUtils.success(accessPath);
    }
}
