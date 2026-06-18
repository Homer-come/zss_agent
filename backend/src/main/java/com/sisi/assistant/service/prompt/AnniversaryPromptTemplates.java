package com.sisi.assistant.service.prompt;

/**
 * Reminder copy for anniversary-related flows.
 */
public final class AnniversaryPromptTemplates {

    private AnniversaryPromptTemplates() {
    }

    public static String reminderMessage(String title, String dayText) {
        return """
                亲爱的思思主人，%s %s啦。
                我已经帮你想好了：礼物可以选一束温柔色系鲜花或一件她最近念叨过的小物；约会适合安排轻松晚餐加散步；祝福文案就写得真诚一点，不堆漂亮话，重点是“我记得、我在意、我会一直陪你”。
                """.formatted(title, dayText);
    }
}
