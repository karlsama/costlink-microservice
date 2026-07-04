package com.costlink.common.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * MyBatis-Plus 字段自动填充 — 所有使用 MyBatis-Plus 的服务自动继承
 *
 * 通过 @ConditionalOnClass 保证：
 * - 有 mybatis-plus jar 的服务（Auth、Reimbursement、Budget、Approval、Notification、Report）→ 自动加载
 * - 没有 mybatis-plus jar 的服务（Gateway、OCR）→ 自动跳过
 */
@Component
@ConditionalOnClass(name = "com.baomidou.mybatisplus.core.handlers.MetaObjectHandler")
public class BaseMetaObjectHandler implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject metaObject) {
        LocalDateTime now = LocalDateTime.now();
        this.strictInsertFill(metaObject, "createTime", LocalDateTime.class, now);
        this.strictInsertFill(metaObject, "updateTime", LocalDateTime.class, now);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        this.strictUpdateFill(metaObject, "updateTime", LocalDateTime.class, LocalDateTime.now());
    }
}
