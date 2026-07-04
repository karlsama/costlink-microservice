package com.costlink.reimbursement.mq;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.costlink.common.mq.MqConstants;
import com.costlink.reimbursement.dto.event.ApprovalCompletedEvent;
import com.costlink.reimbursement.dto.event.OcrCompletedEvent;
import com.costlink.reimbursement.dto.event.OcrFailedEvent;
import com.costlink.reimbursement.entity.Attachment;
import com.costlink.reimbursement.entity.Reimbursement;
import com.costlink.reimbursement.mapper.AttachmentMapper;
import com.costlink.reimbursement.mapper.ReimbursementMapper;
import com.costlink.reimbursement.service.ReimbursementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
@Slf4j
public class ReimbursementEventConsumer {

    private final ReimbursementMapper reimbursementMapper;
    private final AttachmentMapper attachmentMapper;
    private final ReimbursementService reimbursementService;

    @RabbitListener(queues = MqConstants.QUEUE_APPROVAL_COMPLETED)
    @Transactional
    public void onApprovalCompleted(ApprovalCompletedEvent event) {
        Reimbursement r = reimbursementMapper.selectById(event.getReimbursementId());
        if (r == null) {
            log.warn("审批完成事件: 报销单不存在, reimbursementId={}", event.getReimbursementId());
            return;
        }
        if (!"PENDING".equals(r.getStatus())) {
            throw new AmqpRejectAndDontRequeueException("状态不是PENDING, 等待重试");
        }

        if ("APPROVED".equals(event.getAction())) {
            r.setStatus("APPROVED");
            r.setApproveTime(LocalDateTime.now());
            reimbursementMapper.updateById(r);
            log.info("报销单审批通过, reimbursementId={}", r.getId());

        } else if ("REJECTED".equals(event.getAction())) {
            r.setStatus("REJECTED");
            reimbursementMapper.updateById(r);
            log.info("报销单审批驳回, reimbursementId={}", r.getId());
        }
    }

    @RabbitListener(queues = MqConstants.QUEUE_OCR_COMPLETED)
    @Transactional
    public void onOcrCompleted(OcrCompletedEvent event) {
        Attachment att = attachmentMapper.selectById(event.getAttachmentId());
        if (att == null) {
            log.warn("OCR完成事件: 附件不存在, attachmentId={}", event.getAttachmentId());
            return;
        }
        att.setOcrAmount(event.getOcrAmount());
        att.setOcrResult(event.getOcrResult());
        att.setOcrStatus("SUCCESS");
        attachmentMapper.updateById(att);
        log.info("OCR识别成功, attachmentId={}, amount={}", event.getAttachmentId(), event.getOcrAmount());
    }

    @RabbitListener(queues = MqConstants.QUEUE_OCR_FAILED)
    @Transactional
    public void onOcrFailed(OcrFailedEvent event) {
        Attachment att = attachmentMapper.selectById(event.getAttachmentId());
        if (att == null) {
            log.warn("OCR失败事件: 附件不存在, attachmentId={}", event.getAttachmentId());
            return;
        }
        att.setOcrStatus("FAILED");
        attachmentMapper.updateById(att);
        log.info("OCR识别失败, attachmentId={}, error={}", event.getAttachmentId(), event.getErrorMessage());
    }
}
