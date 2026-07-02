-- ============================================================
-- CostLink 数据库初始化脚本
-- 在 MySQL 中执行: mysql -u root -p < init.sql
-- 日期: 2026-07-01
-- ============================================================

-- ============================================================
-- 1. 创建所有数据库
-- ============================================================
CREATE DATABASE IF NOT EXISTS nacos_config
    CHARACTER SET utf8 COLLATE utf8_bin;

CREATE DATABASE IF NOT EXISTS costlink_shared
    CHARACTER SET utf8 COLLATE utf8_unicode_ci;

CREATE DATABASE IF NOT EXISTS costlink_reimbursement
    CHARACTER SET utf8 COLLATE utf8_unicode_ci;

CREATE DATABASE IF NOT EXISTS costlink_budget
    CHARACTER SET utf8 COLLATE utf8_unicode_ci;

CREATE DATABASE IF NOT EXISTS costlink_approval
    CHARACTER SET utf8 COLLATE utf8_unicode_ci;

CREATE DATABASE IF NOT EXISTS costlink_notification
    CHARACTER SET utf8 COLLATE utf8_unicode_ci;

-- ============================================================
-- 2. 导入 Nacos 初始化表（如果还没导入的话）
--    如果你的 Nacos 已经在用 MySQL 且表已建好，跳过此段
--    否则: 手动执行 D:\nacos\conf\mysql-schema.sql
-- ============================================================
-- source D:/nacos/conf/mysql-schema.sql
-- （上面的 source 命令在 Navicat/DBeaver 中可能需要手动打开文件执行）


-- ============================================================
-- 3. costlink_shared — 认证服务
-- ============================================================
USE costlink_shared;

CREATE TABLE IF NOT EXISTS `user` (
    id              BIGINT PRIMARY KEY COMMENT '用户ID',
    username        VARCHAR(50)  NOT NULL UNIQUE COMMENT '用户名',
    password        VARCHAR(200) NOT NULL COMMENT '密码(BCrypt加密)',
    display_name    VARCHAR(50)  NOT NULL COMMENT '显示姓名',
    email           VARCHAR(100) COMMENT '邮箱',
    phone           VARCHAR(20)  COMMENT '手机号',
    role            VARCHAR(50)  NOT NULL DEFAULT 'EMPLOYEE' COMMENT '角色: ADMIN/FINANCE_MANAGER/FINANCE/DEPARTMENT_HEAD/EMPLOYEE',
    department_id   BIGINT       COMMENT '所属部门ID',
    department_name VARCHAR(100) COMMENT '部门名称(冗余)',
    alipay_account  VARCHAR(200) COMMENT '支付宝账号(AES加密)',
    bank_account    VARCHAR(200) COMMENT '银行卡号(AES加密)',
    status          VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' COMMENT '状态: ACTIVE/DISABLED',
    deleted         TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_username (username),
    INDEX idx_department (department_id),
    INDEX idx_role (role)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='用户表';

CREATE TABLE IF NOT EXISTS `department` (
    id              BIGINT PRIMARY KEY COMMENT '部门ID',
    name            VARCHAR(100) NOT NULL COMMENT '部门名称',
    parent_id       BIGINT       DEFAULT 0 COMMENT '上级部门ID',
    level           INT          DEFAULT 1 COMMENT '层级',
    sort_order      INT          DEFAULT 0 COMMENT '排序',
    deleted         TINYINT      NOT NULL DEFAULT 0,
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_parent (parent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='部门表';

-- 初始管理员账号 (密码: admin123)
INSERT INTO `user` (id, username, password, display_name, role, department_id, department_name) VALUES
(1, 'admin', '$2b$10$ZKRnQ32Zc2kwQWoVJTmRgusBpFvb1I4RYwyYOJDCCBVX3q0V4CUZu', '系统管理员', 'ADMIN', 0, '总经办');


-- ============================================================
-- 4. costlink_reimbursement — 报销服务
-- ============================================================
USE costlink_reimbursement;

CREATE TABLE IF NOT EXISTS `reimbursement` (
    id                  BIGINT PRIMARY KEY COMMENT '报销单ID(雪花算法)',
    applicant_id        BIGINT       NOT NULL COMMENT '申请人ID',
    department_id       BIGINT       NOT NULL COMMENT '所属部门ID',
    title               VARCHAR(200) NOT NULL COMMENT '报销事由',
    total_amount        DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT '报销总金额',
    expense_type        VARCHAR(50)  NOT NULL COMMENT '费用类型: TRAVEL/ENTERTAIN/OFFICE/TRANSPORT/OTHER',
    status              VARCHAR(30)  NOT NULL DEFAULT 'DRAFT' COMMENT '状态: DRAFT/PENDING/APPROVED/REJECTED/PAID',
    approval_instance_id BIGINT      COMMENT '关联审批实例ID',
    amount_hash         VARCHAR(200) COMMENT '金额防篡改哈希',
    submit_time         DATETIME     COMMENT '提交时间',
    approve_time        DATETIME     COMMENT '审批通过时间',
    paid_time           DATETIME     COMMENT '付款时间',
    remark              VARCHAR(500) COMMENT '备注',
    deleted             TINYINT      NOT NULL DEFAULT 0,
    create_time         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_applicant (applicant_id),
    INDEX idx_department (department_id),
    INDEX idx_status (status),
    INDEX idx_submit_time (submit_time),
    INDEX idx_expense_type (expense_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='报销单主表';

CREATE TABLE IF NOT EXISTS `expense_item` (
    id                  BIGINT PRIMARY KEY COMMENT '费用明细ID',
    reimbursement_id    BIGINT       NOT NULL COMMENT '关联报销单ID',
    category            VARCHAR(50)  NOT NULL COMMENT '费用科目',
    amount              DECIMAL(12,2) NOT NULL COMMENT '金额',
    receipt_date        DATE         COMMENT '票据日期',
    remark              VARCHAR(500) COMMENT '说明',
    attachment_id       BIGINT       COMMENT '关联附件ID',
    deleted             TINYINT      NOT NULL DEFAULT 0,
    create_time         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_reimbursement (reimbursement_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='费用明细表';

CREATE TABLE IF NOT EXISTS `reimbursement_attachment` (
    id                  BIGINT PRIMARY KEY COMMENT '附件ID',
    reimbursement_id    BIGINT       NOT NULL COMMENT '关联报销单ID',
    file_name           VARCHAR(200) NOT NULL COMMENT '原始文件名',
    file_url            VARCHAR(500) NOT NULL COMMENT '文件存储路径',
    file_size           BIGINT       COMMENT '文件大小(字节)',
    file_hash           VARCHAR(64)  COMMENT '文件MD5(OCR去重用)',
    ocr_amount          DECIMAL(12,2) COMMENT 'OCR识别金额',
    ocr_status          VARCHAR(30)  DEFAULT 'PENDING' COMMENT 'OCR状态: PENDING/PROCESSING/SUCCESS/FAILED',
    ocr_result          JSON         COMMENT 'OCR完整识别结果',
    deleted             TINYINT      NOT NULL DEFAULT 0,
    create_time         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_reimbursement (reimbursement_id),
    INDEX idx_file_hash (file_hash),
    INDEX idx_ocr_status (ocr_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='票据附件表';

CREATE TABLE IF NOT EXISTS `payment_record` (
    id                  BIGINT PRIMARY KEY COMMENT '付款记录ID',
    reimbursement_id    BIGINT       NOT NULL COMMENT '关联报销单ID',
    payee_id            BIGINT       NOT NULL COMMENT '收款人ID',
    amount              DECIMAL(12,2) NOT NULL COMMENT '付款金额',
    pay_method          VARCHAR(50)  COMMENT '付款方式: ALIPAY/BANK_TRANSFER',
    pay_account         VARCHAR(200) COMMENT '收款账号(加密)',
    pay_status          VARCHAR(30)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/SUCCESS/FAILED',
    pay_time            DATETIME     COMMENT '付款时间',
    transaction_id      VARCHAR(100) COMMENT '交易流水号',
    operator_id         BIGINT       COMMENT '操作人ID(财务)',
    remark              VARCHAR(500),
    deleted             TINYINT      NOT NULL DEFAULT 0,
    create_time         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_reimbursement (reimbursement_id),
    INDEX idx_payee (payee_id),
    INDEX idx_pay_status (pay_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='付款记录表';

-- 本地消息表（保证数据库写入和MQ发送的原子性）
CREATE TABLE IF NOT EXISTS `outbox_message` (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    message_id      VARCHAR(64)  NOT NULL UNIQUE COMMENT '消息唯一ID',
    aggregate_id    BIGINT       NOT NULL COMMENT '聚合根ID(如报销单ID)',
    event_type      VARCHAR(100) NOT NULL COMMENT '事件类型',
    payload         JSON         NOT NULL COMMENT '消息体',
    status          VARCHAR(20)  DEFAULT 'PENDING' COMMENT 'PENDING/SENT/FAILED',
    retry_count     INT          DEFAULT 0,
    error_message   VARCHAR(1000),
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    sent_time       DATETIME,
    INDEX idx_status_create (status, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='本地消息表(Transactional Outbox)';


-- ============================================================
-- 5. costlink_budget — 预算服务
-- ============================================================
USE costlink_budget;

CREATE TABLE IF NOT EXISTS `budget` (
    id              BIGINT PRIMARY KEY COMMENT '预算ID',
    fiscal_year     INT          NOT NULL COMMENT '财年',
    department_id   BIGINT       NOT NULL COMMENT '部门ID',
    project_id      BIGINT       COMMENT '项目ID(可选)',
    total_amount    DECIMAL(14,2) NOT NULL DEFAULT 0.00 COMMENT '总预算',
    status          VARCHAR(30)  NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/ACTIVE/FROZEN/CLOSED',
    version         INT          NOT NULL DEFAULT 1 COMMENT '乐观锁版本号',
    deleted         TINYINT      NOT NULL DEFAULT 0,
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_department_year (department_id, fiscal_year)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='预算主表';

CREATE TABLE IF NOT EXISTS `budget_line` (
    id              BIGINT PRIMARY KEY COMMENT '预算明细ID',
    budget_id       BIGINT       NOT NULL COMMENT '预算主表ID',
    category        VARCHAR(50)  NOT NULL COMMENT '费用科目',
    total_amount    DECIMAL(14,2) NOT NULL DEFAULT 0.00 COMMENT '预算总额',
    used_amount     DECIMAL(14,2) NOT NULL DEFAULT 0.00 COMMENT '已使用金额',
    frozen_amount   DECIMAL(14,2) NOT NULL DEFAULT 0.00 COMMENT '冻结金额(审批中)',
    warning_threshold DECIMAL(5,2) DEFAULT 80.00 COMMENT '预警阈值(%)',
    control_strategy VARCHAR(30) DEFAULT 'STRICT' COMMENT '控制策略: STRICT/SOFT/FLEXIBLE',
    version         INT          NOT NULL DEFAULT 1 COMMENT '乐观锁',
    deleted         TINYINT      NOT NULL DEFAULT 0,
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_budget (budget_id),
    INDEX idx_category (category),
    UNIQUE KEY uk_budget_category (budget_id, category)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='预算明细表';

CREATE TABLE IF NOT EXISTS `budget_change_log` (
    id              BIGINT PRIMARY KEY COMMENT '流水ID',
    budget_line_id  BIGINT       NOT NULL COMMENT '预算明细ID',
    change_type     VARCHAR(30)  NOT NULL COMMENT '变动类型: INITIAL/ADJUSTMENT/FREEZE/CONSUME/UNFREEZE',
    change_amount   DECIMAL(14,2) NOT NULL COMMENT '变动金额',
    source_id       BIGINT       COMMENT '来源单据ID(报销单ID/调整单ID)',
    source_type     VARCHAR(50)  COMMENT '来源类型: REIMBURSEMENT/ADJUSTMENT',
    before_amount   DECIMAL(14,2) COMMENT '变动前金额',
    after_amount    DECIMAL(14,2) COMMENT '变动后金额',
    remark          VARCHAR(500),
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_line (budget_line_id),
    INDEX idx_source (source_type, source_id),
    INDEX idx_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='预算变动流水表';

CREATE TABLE IF NOT EXISTS `budget_alert_config` (
    id              BIGINT PRIMARY KEY COMMENT '配置ID',
    department_id   BIGINT       COMMENT '部门ID(null=全局配置)',
    warning_threshold DECIMAL(5,2) DEFAULT 80.00 COMMENT '预警阈值(%)',
    critical_threshold DECIMAL(5,2) DEFAULT 95.00 COMMENT '严重阈值(%)',
    notify_roles    VARCHAR(200) COMMENT '通知角色(逗号分隔)',
    enabled         TINYINT      DEFAULT 1,
    deleted         TINYINT      NOT NULL DEFAULT 0,
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='预算预警配置表';


-- ============================================================
-- 6. costlink_approval — 审批服务
-- ============================================================
USE costlink_approval;

CREATE TABLE IF NOT EXISTS `approval_template` (
    id              BIGINT PRIMARY KEY COMMENT '模板ID',
    name            VARCHAR(100) NOT NULL COMMENT '模板名称',
    description     VARCHAR(500) COMMENT '模板描述',
    rules           JSON         NOT NULL COMMENT '审批规则(条件→审批人链)',
    enabled         TINYINT      DEFAULT 1,
    deleted         TINYINT      NOT NULL DEFAULT 0,
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='审批链模板表';

-- 默认审批模板
INSERT INTO `approval_template` (id, name, description, rules) VALUES
(1, '标准审批链', '按金额分级审批',
 '[
   {"priority":1,"condition":{"amountMin":0,"amountMax":1000},"approvers":[{"type":"ROLE","value":"DEPARTMENT_HEAD","mode":"SINGLE"}]},
   {"priority":2,"condition":{"amountMin":1000,"amountMax":5000},"approvers":[{"type":"ROLE","value":"DEPARTMENT_HEAD","mode":"SINGLE"},{"type":"ROLE","value":"FINANCE_MANAGER","mode":"SINGLE"}]},
   {"priority":3,"condition":{"amountMin":5000,"amountMax":999999},"approvers":[{"type":"ROLE","value":"DEPARTMENT_HEAD","mode":"SINGLE"},{"type":"ROLE","value":"FINANCE_MANAGER","mode":"SINGLE"},{"type":"ROLE","value":"ADMIN","mode":"SINGLE"}]}
 ]'
);

CREATE TABLE IF NOT EXISTS `approval_instance` (
    id                  BIGINT PRIMARY KEY COMMENT '审批实例ID',
    template_id         BIGINT       NOT NULL COMMENT '关联模板ID',
    reimbursement_id    BIGINT       NOT NULL COMMENT '关联报销单ID',
    applicant_id        BIGINT       NOT NULL COMMENT '申请人ID',
    department_id       BIGINT       NOT NULL COMMENT '所属部门ID',
    total_amount        DECIMAL(12,2) NOT NULL COMMENT '报销金额',
    expense_type        VARCHAR(50)  NOT NULL COMMENT '费用类型',
    status              VARCHAR(30)  NOT NULL DEFAULT 'IN_PROGRESS' COMMENT 'IN_PROGRESS/APPROVED/REJECTED/CANCELLED',
    current_node_order  INT          DEFAULT 1 COMMENT '当前审批节点序号',
    total_nodes         INT          COMMENT '总审批节点数',
    start_time          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    end_time            DATETIME,
    deleted             TINYINT      NOT NULL DEFAULT 0,
    create_time         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_reimbursement (reimbursement_id),
    INDEX idx_applicant (applicant_id),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='审批实例表';

CREATE TABLE IF NOT EXISTS `approval_node` (
    id                  BIGINT PRIMARY KEY COMMENT '节点ID',
    instance_id         BIGINT       NOT NULL COMMENT '关联审批实例ID',
    node_order          INT          NOT NULL COMMENT '节点序号(1,2,3...)',
    approver_id         BIGINT       NOT NULL COMMENT '审批人ID',
    approver_name       VARCHAR(50)  COMMENT '审批人姓名',
    approver_role       VARCHAR(50)  COMMENT '审批人角色',
    approve_mode        VARCHAR(30)  DEFAULT 'SINGLE' COMMENT '审批模式: SINGLE/COUNTERSIGN/OR_SIGN',
    status              VARCHAR(30)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/APPROVED/REJECTED/TRANSFERRED/SKIPPED',
    action              VARCHAR(30)  COMMENT '操作: APPROVE/REJECT/TRANSFER',
    comment             VARCHAR(500) COMMENT '审批意见',
    deadline            DATETIME     COMMENT '审批截止时间',
    action_time         DATETIME     COMMENT '操作时间',
    deleted             TINYINT      NOT NULL DEFAULT 0,
    create_time         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_instance (instance_id),
    INDEX idx_approver_status (approver_id, status),
    INDEX idx_instance_order (instance_id, node_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='审批节点表';

CREATE TABLE IF NOT EXISTS `approval_record` (
    id              BIGINT PRIMARY KEY COMMENT '记录ID',
    instance_id     BIGINT       NOT NULL COMMENT '关联审批实例ID',
    node_id         BIGINT       COMMENT '关联节点ID',
    operator_id     BIGINT       NOT NULL COMMENT '操作人ID',
    operator_name   VARCHAR(50)  COMMENT '操作人姓名',
    action          VARCHAR(30)  NOT NULL COMMENT '操作: APPROVE/REJECT/TRANSFER/ADD_SIGN/WITHDRAW',
    comment         VARCHAR(500) COMMENT '审批意见',
    before_status   VARCHAR(30)  COMMENT '操作前状态',
    after_status    VARCHAR(30)  COMMENT '操作后状态',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_instance (instance_id),
    INDEX idx_operator (operator_id),
    INDEX idx_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='审批操作记录表';


-- ============================================================
-- 7. costlink_notification — 通知服务
-- ============================================================
USE costlink_notification;

CREATE TABLE IF NOT EXISTS `message` (
    id              BIGINT PRIMARY KEY COMMENT '消息ID',
    user_id         BIGINT       NOT NULL COMMENT '接收人ID',
    title           VARCHAR(200) NOT NULL COMMENT '消息标题',
    content         TEXT         NOT NULL COMMENT '消息内容',
    message_type    VARCHAR(50)  NOT NULL COMMENT '消息类型: APPROVAL_NOTIFY/REJECT_NOTIFY/BUDGET_ALERT/PAYMENT_NOTIFY/SYSTEM',
    channel         VARCHAR(50)  COMMENT '渠道: IN_APP/EMAIL/WECHAT_WORK/DINGTALK',
    related_id      BIGINT       COMMENT '关联业务ID(报销单/审批实例)',
    related_type    VARCHAR(50)  COMMENT '关联业务类型',
    is_read         TINYINT      DEFAULT 0 COMMENT '是否已读',
    read_time       DATETIME     COMMENT '阅读时间',
    send_status     VARCHAR(30)  DEFAULT 'PENDING' COMMENT '发送状态: PENDING/SUCCESS/FAILED',
    send_time       DATETIME     COMMENT '发送时间',
    deleted         TINYINT      NOT NULL DEFAULT 0,
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_user_read (user_id, is_read),
    INDEX idx_user_time (user_id, create_time),
    INDEX idx_send_status (send_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='消息表';

CREATE TABLE IF NOT EXISTS `message_template` (
    id              BIGINT PRIMARY KEY COMMENT '模板ID',
    template_code   VARCHAR(50)  NOT NULL UNIQUE COMMENT '模板编码',
    name            VARCHAR(100) NOT NULL COMMENT '模板名称',
    title_template  VARCHAR(200) NOT NULL COMMENT '标题模板',
    content_template TEXT        NOT NULL COMMENT '内容模板(支持占位符)',
    enabled         TINYINT      DEFAULT 1,
    deleted         TINYINT      NOT NULL DEFAULT 0,
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='消息模板表';

-- 初始消息模板
INSERT INTO `message_template` (id, template_code, name, title_template, content_template) VALUES
(1, 'APPROVAL_NOTIFY', '审批通知', '您有一条新的报销审批待办', '报销单「{title}」金额 {amount} 元已提交，请审批。'),
(2, 'APPROVAL_APPROVED', '审批通过通知', '您的报销单已通过审批', '报销单「{title}」金额 {amount} 元已通过审批。'),
(3, 'APPROVAL_REJECTED', '审批驳回通知', '您的报销单已被驳回', '报销单「{title}」被驳回，原因: {reason}。请修改后重新提交。'),
(4, 'BUDGET_WARNING', '预算预警', '预算预警通知', '部门 {department} 的 {category} 预算已使用 {rate}%，剩余 {available} 元。'),
(5, 'PAYMENT_NOTIFY', '付款通知', '报销款已支付', '报销单「{title}」金额 {amount} 元已支付到您的账户。');


-- ============================================================
-- 8. 审计日志表（各服务共用，放 shared 库）
-- ============================================================
USE costlink_shared;

CREATE TABLE IF NOT EXISTS `audit_log` (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    trace_id        VARCHAR(64)  NOT NULL COMMENT '链路追踪ID',
    user_id         BIGINT       COMMENT '操作人ID',
    username        VARCHAR(50)  COMMENT '操作人用户名',
    action          VARCHAR(100) NOT NULL COMMENT '操作类型',
    resource_type   VARCHAR(50)  NOT NULL COMMENT '资源类型',
    resource_id     VARCHAR(100) NOT NULL COMMENT '资源ID',
    detail          JSON         COMMENT '操作详情(变更前后对比)',
    ip_address      VARCHAR(45)  COMMENT 'IP地址',
    user_agent      VARCHAR(500) COMMENT '用户代理',
    status          VARCHAR(20)  COMMENT 'SUCCESS/FAILURE',
    error_message   VARCHAR(1000),
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_resource (resource_type, resource_id),
    INDEX idx_user_time (user_id, create_time),
    INDEX idx_create_time (create_time),
    INDEX idx_trace_id (trace_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='审计日志表';

-- ============================================================
-- 初始化完成
-- ============================================================
SELECT 'CostLink 数据库初始化完成' AS message;
