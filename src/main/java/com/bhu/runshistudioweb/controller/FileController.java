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
 * Controller 只负责接参与返回，文件校验、落盘、路径拼装全部在 FileManager。
 * 这样文件逻辑可以被其他入口复用（例如后台批量导入成员头像），
 * 也便于脱离 Web 环境做单元测试——FileManager 只认识 MultipartFile 与本地磁盘，不认识 HTTP。
 *
 * 鉴权：本接口不在 Sa-Token 的白名单里，即必须登录后才能上传
 * （匿名上传等于给攻击者一个免费的图床，极易被用来托管违规内容）。
 * 注意方法上没有写 {@code @SaCheckLogin}——这不是漏写：白名单是「默认拒绝」策略，
 * 不在清单里的路径一律先过 StpUtil.checkLogin()，未登录根本进不到方法体。
 * 而上传成功后返回的 /uploads/** 访问路径是公开的（白名单里登记了 /uploads/**）——
 * 游客看得到图，但不能上传，这正是我们想要的边界：读开放、写受限。
 *
 * 为什么上传接口不做成 /uploads 前缀下的接口：/uploads/** 整段都在白名单里，
 * 如果把写接口也挂在那个前缀下，就等于把「谁能上传」这件事交给了白名单的粒度。
 * 读写分属两个前缀，白名单只需要放行其中一个，看路径就知道权限。
 *
 * 接口地址前缀 /api（来自 server.servlet.context-path），
 * 完整路径形如 http://localhost:8080/api/file/upload。
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
     * <p>为什么这样设计：Controller 只有一行转发，因为上传的安全边界全在 FileManager 里，
     * 而且是三层缺一不可的防线——魔数校验（只看文件头字节判断真实类型，绝不信任文件名后缀）、
     * 重命名落盘（文件名用 UUID 重新生成，从根本上消除路径穿越与同名覆盖）、
     * 路径规范化校验（落盘前再确认目标路径在存储根目录内）。
     * 把这三层写在 Controller 里，将来别的入口要上传就得复制一遍，迟早漏掉其中一层。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① Spring 按字段名 file 从 multipart 请求里取出文件，绑成 MultipartFile。
     *     字段名必须与前端 append 时用的名字一致，否则这里直接得到 null；</li>
     *     <li>② 交给 FileManager 做「校验 → 落盘 → 拼路径」，返回相对访问路径；</li>
     *     <li>③ 把路径包成统一响应体。返回相对路径而不是 http://host:port 的绝对地址——
     *     域名会随环境（本地 / 测试 / 生产）变化，由前端拼接才不会把地址写死。</li>
     * </ol>
     *
     * <p>容易踩的坑：请求必须是 multipart/form-data，且前端不能手动设置 Content-Type。
     * 浏览器需要自己补 boundary 参数，手写 Content-Type 会丢掉 boundary，
     * 后端就解析不出文件，表现为 file 为 null 或直接报请求体不可读。
     * 另外 file 参数没有写 required = false：缺文件时 Spring 抛「缺少必填参数」异常，
     * 被兜成 B0001；FileManager 里还有一层「文件为空」的校验，但那已经是在拿到对象之后了。
     *
     * @param file 上传的文件。字段名固定为 file；只允许 PNG 与 JPEG，真实类型由魔数判定，
     *             改后缀名绕不过去；为空或类型不符时抛 A0401
     * @return 相对访问路径，形如 /uploads/yyyy/MM/{uuid}.png；前端拼上 /api 前缀即可展示
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录（A0201）、
     * 文件为空 / 内容损坏 / 类型不在白名单 / 路径非法（均 A0401）、落盘失败（B0001）时抛出
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
