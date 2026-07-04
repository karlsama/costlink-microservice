package com.costlink.notification.service;

import java.util.Map;

public interface NotificationService {
    void sendFromTemplate(String templateCode, Long userId,
                           Map<String, String> placeholders,
                           String channel, Long relatedId, String relatedType);
}
