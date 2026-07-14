package com.xammer.cloud.service.faq;

import java.util.List;

/**
 * One entry in the chatbot's feature/tab FAQ knowledge base.
 * tabKey mirrors the keys in frontend-app/src/constants/xamopsTabs.ts.
 */
public record FeatureFaqEntry(
        String tabKey,
        String moduleLabel,
        String tabLabel,
        List<String> keywords,
        String description
) {
}
