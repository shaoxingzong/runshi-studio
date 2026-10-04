package com.bhu.runshistudioweb.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.ResourceHttpRequestHandler;

/**
 * Sa-Token 拦截器注册：全局登录校验 + 注解鉴权 + 白名单
 *
 * author: shaoshing
 *
 * <p><b>为什么必须写这个类</b>：Sa-Token 的 starter 只注册了三个过滤器
 * （上下文过滤器、跨域过滤器、防火墙过滤器），**它不会自动注册 WebMVC 拦截器**。
 * 而 {@code @SaCheckRole} / {@code @SaCheckLogin} / {@code @SaCheckPermission} 这些注解，
 * 是 SaInterceptor 在 preHandle 里解析的——不注册拦截器，注解会被**静默忽略**：
 * 不报错、不拦截，接口直接放行。这是接入 Sa-Token 最容易踩、也最危险的坑。
 *
 * <p>本类做两件事：
 * <ol>
 *     <li>构造时传入 auth 函数 {@code handle -> StpUtil.checkLogin()}：
 *     让 {@code /**} 下的所有接口**默认要求登录**（白名单除外）；</li>
 *     <li>用 {@link #EXCLUDE_PATHS} 明确列出允许匿名访问的路径。</li>
 * </ol>
 * 注解鉴权依然生效（SaInterceptor 的 isAnnotation 默认为 true），
 * 两者是「叠加」关系：先做登录校验，再做接口上的 {@code @SaCheckRole} 角色校验。
 *
 * <p><b>默认拒绝</b>是刻意选择：新增接口如果忘了加鉴权注解，最多是「没登录时访问不了」，
 * 而不是「所有人可访问」。反之（默认放行 + 逐个加注解）一旦漏加就是越权漏洞。
 *
 * <p><b>代价与纪律</b>：新写一个「本来就要给游客看」的接口时，
 * 必须把它加进 {@link #EXCLUDE_PATHS}，否则前端会拿到 40100。
 * 因此新增公开接口时，请顺手更新这里的清单（并写清注释），别只加在 Controller 上。
 */
@Configuration
public class SaTokenMvcConfig implements WebMvcConfigurer {

    /**
     * 白名单：无需登录即可访问的路径
     *
     * <p>写法提示：路径是**相对 context-path 的**（本项目的 context-path 是 /api，
     * 但这里不能写 /api/xxx），支持 Ant 风格，如 {@code /member/**}。
     */
    private static final String[] EXCLUDE_PATHS = {
            // ===== C 端公开接口（匿名可访问，产品要求游客也能用）=====
            "/user/register",   // 注册：还没有账号的人必须能访问
            "/user/login",      // 登录：未登录的人必须能访问
            // ⚠️ 业务规则：**团队成员不对外展示**（成员档案只作为后台内部数据）。
            // 原先匿名放行的三条 /member/* 接口（/member/list、/member/certificate/list、
            // /member/detail）已随「官网成员页下线」一并删除，这里**不留任何 /member/* 路径**。
            // 成员只通过管理端接口读取（/member/list/page、/member/get），均要求 admin。
            //
            // 一处**有意的例外**：项目详情 /project/detail 仍返回参与成员（用于项目介绍，
            // 已由产品确认保留），因此匿名访客能通过项目页看到成员姓名/头像。
            // 这是本规则下唯一的成员信息出口，已登记在 db/DESIGN.md 场景 L。
            // 荣誉证书列表：官网「荣誉墙」页（接口实现见 CertificateController）
            // 证书本身**不含任何成员信息**（CertificateFrontVO 只有 id/title/级别/类型/日期/图片），
            // 因此可以对外；「某成员的证书」那条（带成员维度）已随成员页一起下线
            "/certificate/list",
            // 官网首页「大盘数字」：证书数与分布，游客进入首页就要看到。
            // ⚠️ 成员人数（memberTotal / memberInTeam / memberGraduated）已随本规则移除，
            // 首页大盘只剩证书维度。必须写**精确路径**——写成 /statistic/** 会把将来
            // 可能新增的管理端统计接口（可能含敏感数据）一起放行。本期统计接口只有这一个
            "/statistic/overview",
            // 官网「项目案例」页：列表与详情。两条都要**精确登记**：
            //   /project/list        → C 端列表（脱敏，不含 content）
            //   /project/detail      → C 端详情（含 Markdown 正文）
            // 特别注意不能写 /project/** 或 /project/list*：
            // 管理端还有 /project/list/page、/project/add、/project/get 等接口，
            // 通配符会把它们一起放行（/project/list 与 /project/list/page 只差一个后缀）
            "/project/list",
            "/project/detail",
            // 官网「AI 咨询」：提问与历史。两条都精确登记，禁止 /ai/**（见 AiChatController 注释）。
            // 注意路径里带有 chat 段：/ai/chat 是提问（POST），/ai/chat/history 是历史（GET），
            // 两者必须分别登记——只登记前者会让历史接口返回 40100
            "/ai/chat",
            "/ai/chat/history",
            // SSE 流式提问：与 /ai/chat 同源同规则，同样匿名可访问。
            // 注意它是独立路径、必须单独登记——只登记 /ai/chat 不会匹配到 /ai/chat/stream
            "/ai/chat/stream",
            // 删除会话：游客也要能删掉自己刚聊完的会话（凭不可枚举的雪花 ID）。
            // 注意**只放行这一条**：/ai/session/list（我的会话列表）要求登录，
            // 是「按登录用户维度聚合」的接口，匿名没有任何语义
            "/ai/session/delete",
            // 上传后的图片访问路径：官网展示成员头像/证书图片时游客必须能看到。
            // 注意这是「读」的路径；「写」的路径 /file/upload 不在这里，仍然要求登录
            "/uploads/**",

            // ⚠️ 原「前端静态资源」四条（/index.html、/js/**、/css/**、/vendor/**）已删除。
            // 原因：随 jar 发布的旧无构建前端（src/main/resources/static）已整体移除——
            // 两套前端并存会让「改了 A 却看的是 B」，而且旧前端的 AI 助手走的是同步接口，
            // 看起来就像流式没生效。前端现在是独立工程 web/，由 Nginx（生产）或
            // Vite（开发 :5173）托管，**请求根本不经过后端**。
            // 因此这里只保留真正需要匿名访问的静态资源：上传的图片 /uploads/**。

            // ===== 接口文档（Knife4j / OpenAPI）=====
            // 注意：生产环境更稳妥的做法是直接关闭文档（knife4j.enable=false），
            // 而不是长期把这些路径挂在白名单上
            "/doc.html",        // Knife4j 文档页
            "/webjars/**",      // 文档页依赖的静态资源
            "/v3/api-docs/**",  // OpenAPI JSON
            "/swagger-ui/**",   // 备用 swagger-ui 路径
            "/favicon.ico",

            // ===== 框架内部路径 =====
            // 说明：曾经把 /error 加进这里，但实测**无效**——不存在的路径并不是"forward 到 /error"，
            // 而是被静态资源处理器（ResourceHttpRequestHandler）接管，拦截器在那之前就抛了 NotLoginException。
            // 真正的修法见 addInterceptors 里对 handler 类型的判断，这里不需要 /error。

            // ===== 运维探活 =====
            // ⚠️ 重要（已实测）：actuator 端点由 WebMvcEndpointHandlerMapping 处理，
            // InterceptorRegistry 注册的拦截器**根本不作用于它**——实测把 exposure 放宽后
            // 匿名请求 /actuator/env 直接返回 200（含配置项），拦截器完全没生效。
            // 因此下面这行**不是"保护 actuator"的手段**，它只是让意图更清晰而已。
            //
            // actuator 的真正安全边界在别处，三者缺一不可：
            //   1) management.endpoints.web.exposure.include 保持最小集（默认仅 health）——
            //      这是安全边界，不是调试开关，切勿为了排查方便改成 "*"；
            //   2) 需要暴露更多端点时，用 management.server.port 挪到独立端口，仅内网可达；
            //   3) 生产环境配合网络层限制（安全组 / 反向代理 / 防火墙）。
            "/actuator/health"
    };

    /**
     * 注册 Sa-Token 拦截器
     *
     * @param registry Spring MVC 的拦截器注册表
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor(handler -> {
                    // 关键：handler 是 Spring 已经解析出来的处理器，这里能区分请求的去向。
                    //
                    // 不存在的路径（以及静态资源）会落到静态资源处理器 ResourceHttpRequestHandler，
                    // 它同样挂在 /** 上、同样会被本拦截器命中。若无条件 checkLogin()，
                    // 所有 404 都会被伪装成 40100：前端 URL 写错时看到"未登录"，排查方向被带偏，
                    // 监控里也统计不到 404。
                    // 因此这里跳过鉴权，让静态资源处理器正常走完 → 返回真正的 404。
                    // 这不产生越权风险：该处理器只能读静态文件，读不到任何业务接口。
                    if (handler instanceof ResourceHttpRequestHandler) {
                        return;
                    }
                    StpUtil.checkLogin();
                }))
                // 路径注意：server.servlet.context-path（本项目为 /api）不参与拦截器匹配，
                // /** 已经覆盖全部接口，不需要也不能写成 /api/**
                .addPathPatterns("/**")
                .excludePathPatterns(EXCLUDE_PATHS);
        // 未登录时 StpUtil.checkLogin() 抛 NotLoginException，
        // 由 GlobalExceptionHandler 统一转成 40100，前端拦截器据此跳登录页
    }
}
