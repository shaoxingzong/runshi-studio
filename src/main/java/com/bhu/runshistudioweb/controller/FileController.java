package com.bhu.runshistudioweb.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.bhu.runshistudioweb.common.BaseResponse;
import com.bhu.runshistudioweb.common.ResultUtils;
import com.bhu.runshistudioweb.manager.FileManager;
import com.bhu.runshistudioweb.manager.UploadQuotaManager;
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
 * @author shaoshing
 */
@Tag(name = "文件管理")
@RestController
@RequestMapping("/file")
@RequiredArgsConstructor
public class FileController {

    private final FileManager fileManager;

    /** 每日上传配额 */
    private final UploadQuotaManager uploadQuotaManager;

    /**
     * 文件上传
     *
     * @param file 字段名固定为 file，只允许 PNG 与 JPEG（按魔数判定，改后缀绕不过去）
     * @return 相对访问路径，形如 /uploads/yyyy/MM/{uuid}.png，域名由前端拼接
     */
    @Operation(summary = "文件上传")
    @PostMapping("/upload")
    public BaseResponse<String> upload(@RequestParam("file") MultipartFile file) {
        uploadQuotaManager.assertAllowed(StpUtil.getLoginIdAsLong());
        // 三层安全防线（魔数、UUID 重命名、路径边界）都在 FileManager 里，Controller 不重复实现
        String accessPath = fileManager.upload(file);
        // 只回相对路径：域名由前端拼接，避免把本地地址写进生产数据
        return ResultUtils.success(accessPath);
    }
}
