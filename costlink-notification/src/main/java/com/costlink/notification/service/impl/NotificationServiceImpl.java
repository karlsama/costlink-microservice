package com.costlink.notification.service.impl;

import com.costlink.notification.entity.Message;
import com.costlink.notification.entity.MessageTemplate;
import com.costlink.notification.mapper.MessageMapper;
import com.costlink.notification.mapper.MessageTemplateMapper;
import com.costlink.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationServiceImpl implements NotificationService {

    private final MessageTemplateMapper templateMapper;
    private final MessageMapper messageMapper;

    @Override
    public void sendFromTemplate(String templateCode, Long userId,
                                  Map<String, String> placeholders,
                                  String channel, Long relatedId, String relatedType) {
        MessageTemplate template = templateMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MessageTemplate>()
                        .eq(MessageTemplate::getTemplateCode, templateCode));
        if (template == null || template.getEnabled() == null || template.getEnabled() == 0) {
            log.warn("消息模板不可用, templateCode={}", templateCode);
            return;
        }

        String title = render(template.getTitleTemplate(), placeholders);
        String content = render(template.getContentTemplate(), placeholders);

        Message msg = new Message();
        msg.setUserId(userId);
        msg.setTitle(title);
        msg.setContent(content);
        msg.setMessageType(templateCode);
        msg.setChannel(channel != null ? channel : "IN_APP");
        msg.setRelatedId(relatedId);
        msg.setRelatedType(relatedType);
        msg.setIsRead(0);
        msg.setSendStatus("PENDING");
        msg.setSendTime(LocalDateTime.now());
        messageMapper.insert(msg);

        log.info("消息已创建, userId={}, type={}, title={}", userId, templateCode, title);
    }

    private String render(String text, Map<String, String> placeholders) {
        if (text == null || placeholders == null) return text;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            text = text.replace("{" + entry.getKey() + "}", entry.getValue() != null ? entry.getValue() : "");
        }
        return text;
    }
}
