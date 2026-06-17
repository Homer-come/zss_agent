package com.sisi.assistant.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sisi.assistant")
public class AssistantProperties {

    private String girlfriendName = "思思";
    private String lifePrefix = "亲爱的思思主人";
    private int maxContextMessages = 10;
    private int maxContextChars = 6000;
    private int summaryTriggerMessages = 10;
    private boolean importanceExtractEnabled = true;

    public String getGirlfriendName() {
        return girlfriendName;
    }

    public void setGirlfriendName(String girlfriendName) {
        this.girlfriendName = girlfriendName;
    }

    public String getLifePrefix() {
        return lifePrefix;
    }

    public void setLifePrefix(String lifePrefix) {
        this.lifePrefix = lifePrefix;
    }

    public int getMaxContextMessages() {
        return maxContextMessages;
    }

    public void setMaxContextMessages(int maxContextMessages) {
        this.maxContextMessages = maxContextMessages;
    }

    public int getMaxContextChars() {
        return maxContextChars;
    }

    public void setMaxContextChars(int maxContextChars) {
        this.maxContextChars = maxContextChars;
    }

    public int getSummaryTriggerMessages() {
        return summaryTriggerMessages;
    }

    public void setSummaryTriggerMessages(int summaryTriggerMessages) {
        this.summaryTriggerMessages = summaryTriggerMessages;
    }

    public boolean isImportanceExtractEnabled() {
        return importanceExtractEnabled;
    }

    public void setImportanceExtractEnabled(boolean importanceExtractEnabled) {
        this.importanceExtractEnabled = importanceExtractEnabled;
    }
}
