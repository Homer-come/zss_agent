package com.sisi.assistant.service.prompt;

/**
 * Prompts and fallback copy for the work agent.
 */
public final class WorkPromptTemplates {

    private WorkPromptTemplates() {
    }

    public static String systemPrompt(String userProfile) {
        return """
                你是资深内容专家与职场提效助手。必须专业、干练、结构清晰。
                直接交付结果，不要输出"任务理解""背景增强""建议结构"这种调试式分段。
                用户要 PPT 时，输出可直接复制的金字塔结构提纲。
                用户要文案时，直接给成稿，优先满足目标平台和语气。
                输出使用 Markdown 标题、列表、加粗和引用，方便前端渲染。

                用户画像：
                %s
                """.formatted(userProfile);
    }

    public static String copyFallback(String title, String body) {
        return """
                ## 小红书种草文案

                ### 标题
                %s

                ### 正文
                这次我直接给你一个能发的版本：

                %s

                ### 结尾引导
                想要的话，我还可以继续帮你补 3 个不同风格的标题。

                ### 标签
                #小红书种草 #文案 #内容创作 #生活方式
                """.formatted(title, body);
    }

    public static String pptFallback(String background) {
        return """
                ## PPT 大纲

                ### 1. 封面
                主题、汇报人、日期

                ### 2. 目录
                背景与目标、核心洞察、行动方案、风险与下一步

                ### 3. 核心观点
                每页只讲一个结论，配 2-3 个支撑点

                ### 4. 结尾页
                一句话结论 + 三条可执行动作

                ### 参考背景
                %s
                """.formatted(background);
    }

    public static String rewriteFallback(String body, String background) {
        return """
                ## 可直接使用的版本

                %s

                ### 参考说明
                %s
                """.formatted(body, background);
    }

    public static String generalFallback(String body, String background) {
        return """
                ## 工作模块输出

                ### 你要的结果
                %s

                ### 参考背景
                %s

                ### 交付口径
                先明确目标，再聚焦关键矛盾，最后给出可落地的行动路径。
                """.formatted(body, background);
    }

    public static String copyBody(String userMessage, String backgroundHint) {
        return """
                围绕这句需求来写：%s

                最近我一直在想，真正打动人的内容，不是把信息讲满，而是让人看完就想试一次。

                %s

                如果你也想把这件事表达得更有吸引力，关键不是堆形容词，而是把真实感、细节感和使用场景讲出来。
                """.formatted(userMessage, backgroundHint);
    }

    public static String rewriteBody(String userMessage) {
        return """
                保留原意，收紧句子，去掉多余修饰，让表达更利落。

                原句：%s

                改写后：更清晰、更直接、更像人说的话。
                """.formatted(userMessage);
    }

    public static String generalBody(String userMessage) {
        return """
                %s

                先把目标定清楚，再把动作拆具体，最后用最少的话把结论说透。
                """.formatted(userMessage);
    }
}
