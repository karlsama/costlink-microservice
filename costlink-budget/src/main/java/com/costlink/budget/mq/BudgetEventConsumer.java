package com.costlink.budget.mq;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.costlink.budget.entity.BudgetChangeLog;
import com.costlink.budget.service.impl.BudgetFreezeServiceImpl;
import com.costlink.common.feign.BudgetClient;
import com.costlink.common.mq.MqConstants;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class BudgetEventConsumer {

    private final BudgetFreezeServiceImpl budgetFreezeService;
    private final ObjectMapper objectMapper;

    /**
     * 消费报销单已通过事件 → 消费冻结金额
     * 消息体: {"reimbursementId":1,"items":[{"category":"TRAVEL_TRANSPORT","amount":1000.00}]}
     */
    @RabbitListener(queues = MqConstants.QUEUE_REIMBURSEMENT_APPROVED)
    public void onReimbursementApproved(String message) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = objectMapper.readValue(message, Map.class);
            Long reimbursementId = Long.valueOf(body.get("reimbursementId").toString());

            // 幂等检查
            Long count = budgetFreezeService.checkConsumeExists(reimbursementId);
            if (count > 0) {
                log.info("消费已处理（幂等跳过）, reimbursementId={}", reimbursementId);
                return;
            }

            BudgetClient.ConsumeRequest req = new BudgetClient.ConsumeRequest();
            req.setReimbursementId(reimbursementId);

            List<Map<String, Object>> items = (List<Map<String, Object>>) body.get("items");
            List<BudgetClient.ConsumeItem> consumeItems = items.stream().map(m -> {
                BudgetClient.ConsumeItem ci = new BudgetClient.ConsumeItem();
                ci.setCategory((String) m.get("category"));
                ci.setAmount(new BigDecimal(m.get("amount").toString()));
                return ci;
            }).toList();
            req.setItems(consumeItems);

            budgetFreezeService.consume(req);
            log.info("MQ消费预算成功, reimbursementId={}", reimbursementId);
        } catch (Exception e) {
            log.error("处理报销通过事件失败", e);
        }
    }

    /**
     * 消费报销单已驳回事件 → 解冻金额
     * 消息体: {"reimbursementId":1}
     */
    @RabbitListener(queues = MqConstants.QUEUE_REIMBURSEMENT_REJECTED)
    public void onReimbursementRejected(String message) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = objectMapper.readValue(message, Map.class);
            Long reimbursementId = Long.valueOf(body.get("reimbursementId").toString());

            BudgetClient.UnfreezeRequest req = new BudgetClient.UnfreezeRequest();
            req.setReimbursementId(reimbursementId);
            budgetFreezeService.unfreeze(req);
            log.info("MQ解冻预算成功, reimbursementId={}", reimbursementId);
        } catch (Exception e) {
            log.error("处理报销驳回事件失败", e);
        }
    }
}
