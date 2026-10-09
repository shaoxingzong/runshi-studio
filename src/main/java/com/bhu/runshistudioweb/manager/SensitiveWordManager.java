package com.bhu.runshistudioweb.manager;

import com.bhu.runshistudioweb.config.PostProperties;
import com.bhu.runshistudioweb.utils.AcAutomaton;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 敏感词匹配器：负责词库的加载、自动机的构建与持有
 *
 * author: shaoshing
 *
 * <p><b>本类与 {@link AcAutomaton} 的分工</b>（与 {@link ClientIpManager} 调 {@link IpUtils}
 * 是同一套路）：
 * <ul>
 *     <li>{@link AcAutomaton}：纯算法，建树与匹配，无 Spring 依赖，可被单测直接覆盖；</li>
 *     <li>本类：<b>生命周期</b>——什么时候读词库、构建几次、怎么热更新。</li>
 * </ul>
 * 不拆开的话，想测一次匹配就得先造配置文件、起 Spring。
 *
 * <p><b>为什么在启动时构建一次，而不是每次匹配都现查</b>：
 * 建树要遍历整个词库，而帖子正文可能几千字——每次请求重建等于把词库成本摊到每一次提交上。
 * 树建好后是<b>只读</b>的（{@link AcAutomaton#match} 不修改任何内部状态），
 * 所以可以被所有请求并发共用。
 *
 * <p><b>热更新用 volatile 原子替换，不用锁</b>：替换引用是一个原子操作，
 * 正在进行的匹配会继续用旧树跑完（旧树仍然完整可用），新请求自然看到新树。
 * 为它加锁只会让每次匹配都付出同步成本，而收益为零。
 *
 * <p><b>降级纪律（重要）</b>：词库文件读不到时，降级为<b>空词库</b>并打 warn，
 * 绝不阻断启动。这选择的依据是「宁可漏拦，不可误杀」——
 * 与 {@link ClientIpManager} 在网段未配置时一律判外网是同一条纪律：
 * 过滤是防护手段，不能反过来变成故障源，更不能因为配置缺失就让所有内容被判违规。
 * 漏拦的后果由后续的信任等级、AI 初判与人工队列兜住。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SensitiveWordManager {

    private final PostProperties postProperties;

    /** 资源加载器：同时支持 classpath: 与 file: 两种前缀 */
    private final ResourceLoader resourceLoader;

    /**
     * 当前生效的自动机
     *
     * <p>{@code volatile} 保证热更新时对其它线程<b>立即可见</b>。
     * 初始值是一个空词库，避免 {@code null} 判空——即使词库还没加载完，
     * 调用方也能安全地询问（结果一律是「无命中」）。
     */
    private volatile AcAutomaton automaton = AcAutomaton.build(List.of());

    /**
     * 启动时加载词库并构建自动机
     */
    @PostConstruct
    public void init() {
        reload();
    }

    /**
     * 重新加载词库并替换自动机（支持运行期热更新）
     *
     * <p>词库是运营性数据，增补会很频繁；暴露这个方法是为了将来加一个
     * 「管理员触发刷新」的接口时不必改本类。
     */
    public void reload() {
        List<String> words = loadWords();
        // 先构建再赋值：构建过程中旧树仍在服务，不会出现「短暂没有匹配器」的空档
        AcAutomaton rebuilt = AcAutomaton.build(words);
        this.automaton = rebuilt;

        log.info("敏感词库加载完成 | location={} | words={}",
                postProperties.getSensitiveWordsLocation(), words.size());
    }

    /**
     * 文本是否命中敏感词
     *
     * @param text 待检查文本，可为 null
     * @return 命中返回 true；词库为空或未加载时一律 false（安全侧降级）
     */
    public boolean contains(String text) {
        return automaton.contains(text);
    }

    /**
     * 找出文本中所有命中的敏感词
     *
     * @param text 待检查文本，可为 null
     * @return 命中列表（原文索引区间 + 词）；无命中返回空列表
     */
    public List<AcAutomaton.Hit> match(String text) {
        return automaton.match(text);
    }

    /**
     * 读取词库文件并解析成词列表
     *
     * <p>文件格式：<b>每行一个词</b>，{@code #} 开头为注释，空行忽略。
     * 这样词库可以直接用文本编辑器维护，也能进 Git 做版本对比。
     *
     * <p>为什么用「每行一个」而不是逗号分隔：词本身可能含有空格或标点
     * （比如某些短语），逗号分隔需要转义规则，行格式则天然没有歧义。
     *
     * @return 词列表；读取失败时返回空列表（降级，见类注释）
     */
    private List<String> loadWords() {
        String location = postProperties.getSensitiveWordsLocation();
        try {
            Resource resource = resourceLoader.getResource(location);
            if (!resource.exists()) {
                // 明确 warn：让人一眼看出是路径写错还是忘了放文件，而不是「过滤为什么没生效」
                log.warn("敏感词库文件不存在，本次运行不启用敏感词过滤（漏拦由后续审核层兜住）"
                        + " | location={}", location);
                return List.of();
            }
            String content = resource.getContentAsString(StandardCharsets.UTF_8);
            // 剥 BOM：Windows 编辑器保存的 UTF-8 常带 ﻿ 前缀，
            // 它会成为第一行的第一个字符，让该行变成读不懂的词
            if (content.startsWith("\uFEFF")) {
                content = content.substring(1);
            }

            List<String> words = new ArrayList<>();
            for (String line : content.lines().toList()) {
                String word = line.strip();
                // 空行与注释行跳过；空词绝不能进词库——空模式会在每个字符位置命中，
                // 一行空行就能让所有内容被判为违规（这是 AcAutomatonTest 专门守的坑）
                if (word.isEmpty() || word.startsWith("#")) {
                    continue;
                }
                words.add(word);
            }
            return words;
        } catch (Exception e) {
            log.warn("敏感词库加载失败，本次运行不启用敏感词过滤 | location={}", location, e);
            return List.of();
        }
    }
}
