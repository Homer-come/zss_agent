package com.sisi.assistant.router;

import com.sisi.assistant.common.dto.AgentRoute;
import com.sisi.assistant.common.dto.RouteResult;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

@Component
public class IntentRouter {

    private static final List<String> WORK_KEYWORDS = List.of(
            "总结", "ppt", "大纲", "改写", "润色", "翻译", "扩写", "文章", "长文",
            "文案", "报告", "汇报", "提纲", "小红书", "学术", "公文", "方案"
    );

    private static final List<String> LIFE_KEYWORDS = List.of(
            "天气", "外卖", "吃什么", "难过", "开心", "想你", "晚安", "早安",
            "纪念日", "生日", "约会", "礼物", "奶茶", "旅行", "心情", "累了", "抱抱"
    );

    /**
     * 意图路由器：多 Agent 系统里的第一道分流器。
     * 当前用关键词打分，适合教学和 MVP；生产环境可以替换为小模型分类、规则引擎或 LLM function calling。
     */
    public RouteResult route(String input) {
        String normalized = input == null ? "" : input.toLowerCase(Locale.ROOT);
        int workScore = score(normalized, WORK_KEYWORDS);
        int lifeScore = score(normalized, LIFE_KEYWORDS);

        if (workScore > lifeScore) {
            return new RouteResult(AgentRoute.WORK, confidence(workScore, lifeScore), "命中内容创作/提效意图");
        }
        if (lifeScore > 0) {
            return new RouteResult(AgentRoute.LIFE, confidence(lifeScore, workScore), "命中陪伴/生活意图");
        }
        return new RouteResult(AgentRoute.LIFE, 0.58, "默认进入更有陪伴感的生活模块");
    }

    /**
     * 关键词命中计分。这里故意保持简单透明，便于观察路由为什么进入某个 Agent。
     */
    private int score(String input, List<String> keywords) {
        int score = 0;
        for (String keyword : keywords) {
            if (input.contains(keyword.toLowerCase(Locale.ROOT))) {
                score++;
            }
        }
        return score;
    }

    /**
     * 粗略置信度，用于前端展示和日志观察，不参与安全决策。
     */
    private double confidence(int winner, int loser) {
        return Math.min(0.98, 0.60 + (winner - loser) * 0.12);
    }
}
