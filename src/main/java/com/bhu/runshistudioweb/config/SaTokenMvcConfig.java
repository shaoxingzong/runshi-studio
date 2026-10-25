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
 * 为什么必须写这个类：Sa-Token 的 starter 只注册了三个过滤器
 * （上下文过滤器、跨域过滤器、防火墙过滤器），它不会自动注册 WebMVC 拦截器。
 * 而 @SaCheckRole / @SaCheckLogin / @SaCheckPermission 这些注解，
 * 是 SaInterceptor 在 preHandle 里解析的——不注册拦截器，注解会被静默忽略：
 * 不报错、不拦截，接口直接放行。这是接入 Sa-Token 最容易踩、也最危险的坑。
 *
 * 本类做两件事：
 * - 构造时传入 auth 函数 handle -> StpUtil.checkLogin()：
 * 让 / 下的所有接口默认要求登录**（白名单除外）；
 * - 用 EXCLUDE_PATHS 明确列出允许匿名访问的路径。
 * 注解鉴权依然生效（SaInterceptor 的 isAnnotation 默认为 true），
 * 两者是「叠加」关系：先做登录校验，再做接口上的 @SaCheckRole 角色校验。
 *
 * 默认拒绝是刻意选择：新增接口如果忘了加鉴权注解，最多是「没登录时访问不了」，
 * 而不是「所有人可访问」。反之（默认放行 + 逐个加注解）一旦漏加就是越权漏洞。
 *
 * 代价与纪律：新写一个「本来就要给游客看」的接口时，
 * 必须把它加进 EXCLUDE_PATHS，否则前端会拿到 A0201。
 * 因此新增公开接口时，请顺手更新这里的清单（并写清注释），别只加在 Controller 上。
 *
 * @author shaoshing
 */
@Configuration
public class SaTokenMvcConfig implements WebMvcConfigurer {

    /**
     * 白名单：无需登录即可访问的路径
     *
     * 写法提示：路径是相对 context-path 的（本项目的 context-path 是 /api，
     * 但这里不能写 /api/xxx），支持 Ant 风格，如 /member/**。
     */
    private static final String[] EXCLUDE_PATHS = {
            // ===== C 端公开接口（匿名可访问，产品要求游客也能用）=====
            "/user/register",   // 注册：还没有账号的人必须能访问
            "/user/login",      // 登录：未登录的人必须能访问
            // 业务规则：团队成员不对外展示（成员档案只作为后台内部数据），
            // 这里不留任何 /member/* 路径。成员只通过管理端接口读取
            // （/member/list/page、/member/get），均要求 admin。
            //
            // 一处有意的例外：项目详情 /project/detail 仍返回参与成员（用于项目介绍，
            // 已由产品确认保留），因此匿名访客能通过项目页看到成员姓名/头像。
            // 这是本规则下唯一的成员信息出口。
            // 荣誉证书列表：官网「荣誉墙」页（接口实现见 CertificateController）
            // 证书本身不含任何成员信息（CertificateFrontVO 只有 id/title/级别/类型/日期/图片），
            // 因此可以对外；带成员维度的证书接口不对外，成员页也不再存在
            "/certificate/list",
            // 官网首页「大盘数字」：证书数与分布，游客进入首页就要看到。
            // 首页大盘刻意只做证书维度：成员人数同样属于「展示成员信息」。
            // 必须写精确路径——写成 /statistic/** 会把将来可能新增的
            // 管理端统计接口（可能含敏感数据）一起放行。当前统计接口只有这一个
            "/statistic/overview",
            // 官网「项目案例」页：列表与详情。两条都要精确登记：
            //   /project/list        → C 端列表（脱敏，不含 content）
            //   /project/detail      → C 端详情（含 Markdown 正文）
            // 特别注意不能写 /project/** 或 /project/list*：
            // 管理端还有 /project/list/page、/project/add、/project/get 等接口，
            // 通配符会把它们一起放行（/project/list 与 /project/list/page 只差一个后缀）
            "/project/list",
            "/project/detail",
            // AI 咨询不匿名开放：提问、历史、删除会话全部要求登录
            // （普通用户限 studio.ai.query-limit 次，成员 / 管理员不限次）。
            // 因此这里不登记 /ai/chat、/ai/chat/history、/ai/session/delete——
            // 它们统一走「默认要求登录」，未登录由拦截器直接返回 A0201。
            //
            // 唯一例外是 SSE 流式接口：它必须进到 Service 才能把「未登录」
            // 表达成 error 事件（SSE 的失败契约是 HTTP 200 + error 事件，见 AiChatController），
            // 若由拦截器拒绝会返回 application/json，破坏流式契约。
            // 因此保留它进白名单，由 Service 层强制登录（见 AiChatServiceImpl.prepareChat）。
            "/ai/chat/stream",
            // 官网「帖子」：列表与详情，游客可浏览（产品要求利于传播）。
            // 两条都精确登记，禁止 /post/**——管理端还会有 /post/admin/* 之类的
            // 审核、删除、置顶接口，通配符会把它们一起放行。
            // 另外注意 /post/list/page 是分页接口，与假想的 /post/list 只差一段，登记时不能省略后缀
            "/post/list/page",
            "/post/detail",
            // 帖子的评论列表：游客浏览帖子时应当能直接看到评论，因此精确放行这一条。
            // 注意不放行 /post/comment/add（发评论要登录）——只登记读的路径，
            // 写路径仍走默认要求登录的规则
            "/post/comment/list",
            // 流量埋点：匿名可访问（游客的访问也要统计，只统计登录用户等于只看到冰山一角）。
            // 只登记这一条精确路径，禁止 /traffic/**——管理端的统计接口挂在
            // /admin/traffic 下（另走 @SaCheckRole），通配符会把它们一起放行
            "/traffic/report",
            // 上传后的图片访问路径：官网展示成员头像/证书图片时游客必须能看到。
            // 注意这是「读」的路径；「写」的路径 /file/upload 不在这里，仍然要求登录
            "/uploads/**",

            // 不要把前端静态资源路径加进来：前端是独立工程 web/，由 Nginx（生产）
            // 或 Vite（开发 :5173）托管，请求根本不经过后端。
            // 这里只保留真正需要匿名访问的静态资源：上传的图片 /uploads/**。

            // ===== 接口文档（Knife4j / OpenAPI）=====
            // 这里刻意一条都不登记：文档的匿名访问权由 knife4j.enable /
            // springdoc.api-docs.enabled 控制（默认 false，生产同样不打开）——
            // 白名单再挂一份就是两处控制同一件事，改一处漏一处就是事故：
            // 哪天为了排查把 knife4j 打开，白名单还在，文档裸奔没有任何提示。
            // 分工：配置关文档（安全边界），白名单不留文档路径（少一个失控面）。
            // 本地开发要看文档时，临时切 dev profile（knife4j.enable=true 只写在
            // application-dev.yml，生产 profile 结构上就开不了）——注意即便如此，
            // 文档页仍需登录（未登录访问 /doc.html 会得到 A0201，这是可接受的：
            // 看文档的人本来就有账号）。
            "/favicon.ico",

            // ===== 框架内部路径 =====
            // 说明：这里不需要 /error——不存在的路径并不是「forward 到 /error」，
            // 而是被静态资源处理器（ResourceHttpRequestHandler）接管，拦截器会先抛 NotLoginException。
            // 真正的解法见 addInterceptors 里对 handler 类型的判断。

            // ===== 运维探活 =====
            // 注意：actuator 端点由 WebMvcEndpointHandlerMapping 处理，
            // InterceptorRegistry 注册的拦截器根本不作用于它——把 exposure 放宽之后，
            // 匿名请求 /actuator/env 会直接返回 200（含配置项），拦截器完全不生效。
            // 因此下面这行不是「保护 actuator」的手段，它只是让意图更清晰而已。
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
                    // 所有 404 都会被伪装成 A0201：前端 URL 写错时看到"未登录"，排查方向被带偏，
                    // 监控里也统计不到 404。
                    // 因此这里跳过鉴权，让静态资源处理器正常走完 → 返回真正的 404。
                    // 这不产生越权风险：该处理器只能读静态文件，读不到任何业务接口。
                    if (handler instanceof ResourceHttpRequestHandler) {
                        return;
                    }
                    StpUtil.checkLogin();
                }))
                // 路径注意：server.servlet.context-path（本项目为 /api）不参与拦截器匹配，
                // / 已经覆盖全部接口，不需要也不能写成 /api/
                .addPathPatterns("/**")
                .excludePathPatterns(EXCLUDE_PATHS);
        // 未登录时 StpUtil.checkLogin() 抛 NotLoginException，
        // 由 GlobalExceptionHandler 统一转成 A0201，前端拦截器据此跳登录页
    }
}
