package com.costlink.reimbursement.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.costlink.reimbursement.entity.OutboxMessage;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface OutboxMessageMapper extends BaseMapper<OutboxMessage> {
}
