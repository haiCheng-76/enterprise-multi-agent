package com.enterprise.agent.router;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.regex.Pattern;

/**
 * 混合意图识别路由器
 * 结合规则匹配和 LLM 智能判断
 */
@Slf4j
@Component
public class IntentRouter {

    private final ChatLanguageModel chatModel;
    private final Cache<String, RouteResult> intentCache;

    // 知识问答关键词模式
    private static final Pattern KNOWLEDGE_PATTERN = Pattern.compile(
            ".*(政策|制度|规定|规范|流程|文档|手册|指南|标准|要求|"+
            "如何|怎么|什么是|为什么|哪些|有没有|能否|可以吗|"+
            "年假|请假|报销|晋升|培训|福利|考勤|薪资|绩效).*",
            Pattern.CASE_INSENSITIVE
    );

    // 数据分析关键词模式
    private static final Pattern DATA_PATTERN = Pattern.compile(
            ".*(分析|统计|报表|报告|数据|查询.*数据|"+
            "销售额|销量|业绩|排名|top|排行|"+
            "趋势|增长|下降|环比|同比|对比|"+
            "最近|本月|上月|本季度|本年|去年|"+
            "多少|几个|总共|平均|最高|最低|总计|汇总).*",
            Pattern.CASE_INSENSITIVE
    );

    // 通用对话模式
    private static final Pattern GENERAL_PATTERN = Pattern.compile(
            "^(你好|您好|hi|hello|嗨|hey|早|晚上好|再见|拜拜|谢谢|thanks).*",
            Pattern.CASE_INSENSITIVE
    );

    public IntentRouter(ChatLanguageModel chatModel,
                        @Value("${app.agent.intent-cache.enabled:true}") boolean cacheEnabled,
                        @Value("${app.agent.intent-cache.max-size:500}") int maxSize,
                        @Value("${app.agent.intent-cache.expire-minutes:60}") int expireMinutes) {
        this.chatModel = chatModel;
        
        // 初始化缓存
        if (cacheEnabled) {
            this.intentCache = Caffeine.newBuilder()
                    .maximumSize(maxSize)
                    .expireAfterWrite(Duration.ofMinutes(expireMinutes))
                    .recordStats()
                    .build();
            log.info("意图识别缓存已启用: maxSize={}, expireMinutes={}", maxSize, expireMinutes);
        } else {
            this.intentCache = null;
            log.info("意图识别缓存已禁用");
        }
    }

    /**
     * 路由用户请求到合适的 Agent
     */
    public RouteResult route(String userMessage) {
        log.debug("开始路由处理: {}", userMessage);

        // 尝试从缓存获取
        if (intentCache != null) {
            RouteResult cached = intentCache.getIfPresent(userMessage);
            if (cached != null) {
                log.debug("命中缓存: {}", cached.agentType());
                return cached;
            }
        }

        // 规则匹配
        RouteResult result = matchByRules(userMessage);
        if (result != null) {
            log.info("规则匹配成功: {} -> {}", userMessage, result.agentType());
            cacheResult(userMessage, result);
            return result;
        }

        // LLM 判断
        log.debug("规则未匹配，使用 LLM 判断");
        result = recognizeByLLM(userMessage);
        log.info("LLM 识别完成: {} -> {}", userMessage, result.agentType());
        cacheResult(userMessage, result);
        
        return result;
    }

    /**
     * 基于规则的快速匹配
     */
    private RouteResult matchByRules(String message) {
        String normalized = message.toLowerCase().trim();

        // 优先匹配通用对话（明确的打招呼）
        if (GENERAL_PATTERN.matcher(normalized).matches()) {
            return new RouteResult(
                    RouteResult.AgentType.GENERAL_CHAT,
                    RouteResult.RouteMethod.RULE_BASED,
                    95,
                    new String[]{"greeting"}
            );
        }

        // 数据分析（关键词更明确）
        if (DATA_PATTERN.matcher(normalized).matches()) {
            return new RouteResult(
                    RouteResult.AgentType.DATA_ANALYSIS,
                    RouteResult.RouteMethod.RULE_BASED,
                    90,
                    extractKeywords(normalized, DATA_PATTERN)
            );
        }

        // 知识问答
        if (KNOWLEDGE_PATTERN.matcher(normalized).matches()) {
            return new RouteResult(
                    RouteResult.AgentType.KNOWLEDGE_QA,
                    RouteResult.RouteMethod.RULE_BASED,
                    90,
                    extractKeywords(normalized, KNOWLEDGE_PATTERN)
            );
        }

        return null;
    }

    /**
     * 基于 LLM 的智能识别
     */
    private RouteResult recognizeByLLM(String message) {
        String prompt = """
                你是一个意图识别专家。分析用户问题，判断应该路由到哪个处理模块。
                
                可用模块：
                1. KNOWLEDGE_QA - 知识问答
                   适用于：公司政策查询、产品文档、技术规范、制度流程等企业知识问题
                   
                2. DATA_ANALYSIS - 数据分析
                   适用于：销售统计、业务分析、数据查询、报表生成、趋势分析等
                   
                3. GENERAL_CHAT - 通用对话
                   适用于：打招呼、闲聊、无法明确分类的问题
                
                用户问题：%s
                
                请严格按照以下 JSON 格式返回，不要有任何其他内容：
                {
                  "agentType": "KNOWLEDGE_QA 或 DATA_ANALYSIS 或 GENERAL_CHAT",
                  "confidence": 置信度数字(0-100),
                  "reason": "选择理由",
                  "keywords": ["关键词1", "关键词2"]
                }
                """.formatted(message);

        try {
            String response = chatModel.generate(prompt);
            return parseLLMResponse(response, message);
        } catch (Exception e) {
            log.error("LLM 识别失败，降级到通用对话", e);
            return new RouteResult(
                    RouteResult.AgentType.GENERAL_CHAT,
                    RouteResult.RouteMethod.LLM_BASED,
                    50,
                    new String[]{}
            );
        }
    }

    /**
     * 解析 LLM 返回结果
     */
    private RouteResult parseLLMResponse(String response, String originalMessage) {
        try {
            String cleaned = response.trim()
                    .replaceAll("```json", "")
                    .replaceAll("```", "")
                    .trim();

            RouteResult.AgentType agentType;
            if (cleaned.contains("DATA_ANALYSIS")) {
                agentType = RouteResult.AgentType.DATA_ANALYSIS;
            } else if (cleaned.contains("KNOWLEDGE_QA")) {
                agentType = RouteResult.AgentType.KNOWLEDGE_QA;
            } else {
                agentType = RouteResult.AgentType.GENERAL_CHAT;
            }

            int confidence = 75;
            if (cleaned.contains("\"confidence\"")) {
                try {
                    String confStr = cleaned.split("\"confidence\"")[1]
                            .split(":")[1]
                            .split(",")[0]
                            .trim();
                    confidence = Integer.parseInt(confStr);
                } catch (Exception ignored) {
                }
            }

            return new RouteResult(
                    agentType,
                    RouteResult.RouteMethod.LLM_BASED,
                    confidence,
                    new String[]{originalMessage}
            );

        } catch (Exception e) {
            log.warn("解析 LLM 响应失败: {}", response, e);
            return new RouteResult(
                    RouteResult.AgentType.GENERAL_CHAT,
                    RouteResult.RouteMethod.LLM_BASED,
                    50,
                    new String[]{}
            );
        }
    }

    private String[] extractKeywords(String message, Pattern pattern) {
        return new String[]{message.substring(0, Math.min(20, message.length()))};
    }

    private void cacheResult(String message, RouteResult result) {
        if (intentCache != null) {
            intentCache.put(message, result);
        }
    }

    public String getCacheStats() {
        if (intentCache == null) {
            return "缓存未启用";
        }
        var stats = intentCache.stats();
        return String.format("缓存命中率: %.2f%%, 总请求: %d, 命中: %d, 未命中: %d",
                stats.hitRate() * 100,
                stats.requestCount(),
                stats.hitCount(),
                stats.missCount());
    }
}