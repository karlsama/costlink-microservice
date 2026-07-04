# costlink-report 开发指南

> 面向实际开发，一份文档写完报表服务。不需要回头看其他文件。
> 2026-07-04

---

## 1. 你要做一个什么

**一句话**：报表服务是一个只读数据聚合器。它连接报销、预算、审批三个数据库的只读副本，执行 SELECT 查询生成统计数据，通过 HTTP 接口返回给前端。

**跟其他服务的区别**：不写数据库（只读）、不调 Feign、不碰 MQ、不调外部 API。是所有服务里最安静的一个——没人调它时 CPU 是零。

## 2. 依赖状态

| 输入 | 来源 | 状态 |
|-----|------|------|
| MySQL `costlink_reimbursement` / `costlink_budget` / `costlink_approval` | 只读连接 | ✅ 已有 |
| Nacos | bootstrap.yml | ✅ |

**不需要**：Redis、RabbitMQ、Feign 客户端、任何外部凭据。

## 3. 数据库——读写三库，不建新表

报表服务不拥有任何数据库。它连三个已有的业务库做只读查询。开发阶段直接连同一 MySQL 实例（不区分读写分离），连接配置如下：

| 数据源名 | 库 | 只读 |
|---------|---|------|
| `reimbursementDS` | costlink_reimbursement | ✅ |
| `budgetDS` | costlink_budget | ✅ |
| `approvalDS` | costlink_approval | ✅ |

多数据源配置要点（这是整个报表服务最容易踩坑的地方）：

```java
// 1. 启动类必须排除自动配置
@SpringBootApplication(
    scanBasePackages = {"com.costlink.report", "com.costlink.common"},
    exclude = { DataSourceAutoConfiguration.class }
)
@EnableDiscoveryClient
public class ReportApplication { ... }

// 2. 一个数据源 = 一套完整配置（DataSource + SqlSessionFactory + SqlSessionTemplate + MapperScan）
// 三套配置除了 bean 名和包路径之外完全一样。只展示 reimbursement，其余两套类推。

@Configuration
@MapperScan(
    basePackages = "com.costlink.report.mapper.reimbursement",
    sqlSessionTemplateRef = "reimbursementSqlSessionTemplate"
)
public class ReimbursementDbConfig {

    @Bean @Primary    // ← 只需主数据源标 @Primary
    @ConfigurationProperties("costlink.report.datasources.reimbursement")
    public DataSource reimbursementDS() {
        return DataSourceBuilder.create().build();
    }

    @Bean @Primary
    public SqlSessionFactory reimbursementSqlSessionFactory(
            @Qualifier("reimbursementDS") DataSource ds) throws Exception {
        MybatisSqlSessionFactoryBean bean = new MybatisSqlSessionFactoryBean();
        bean.setDataSource(ds);
        return bean.getObject();
    }

    @Bean @Primary
    public SqlSessionTemplate reimbursementSqlSessionTemplate(
            @Qualifier("reimbursementSqlSessionFactory") SqlSessionFactory sf) {
        return new SqlSessionTemplate(sf);
    }
}

// BudgetDbConfig 和 ApprovalDbConfig 结构完全一样，去掉 @Primary，改 bean 名和包路径即可
```

**重要**：`@MapperScan` 的 `basePackages` 必须精确到每个数据源的 Mapper 子包（`mapper.reimbursement` / `mapper.budget` / `mapper.approval`），不能扫整个 `mapper` 包。否则一个 Mapper 会注册到多个 SqlSessionFactory 上，启动报 `NoUniqueBeanDefinitionException`。

## 4. 你要提供的接口

### 4.1 报销汇总
**GET /api/reports/reimbursement-summary?deptId=&year=&month=**

查询表：`costlink_reimbursement.reimbursement`

```sql
SELECT COUNT(*) AS total_count,
       SUM(total_amount) AS total_amount,
       SUM(CASE WHEN status='APPROVED' THEN total_amount ELSE 0 END) AS approved_amount,
       SUM(CASE WHEN status='PAID' THEN total_amount ELSE 0 END) AS paid_amount
FROM reimbursement
WHERE department_id = #{deptId}
  AND YEAR(submit_time) = #{year}
  AND deleted = 0
```

### 4.2 预算执行率
**GET /api/reports/budget-execution?deptId=&year=**

查询表：`costlink_budget.budget` + `costlink_budget.budget_line`

```sql
SELECT bl.category, bl.total_amount, bl.used_amount, bl.frozen_amount,
       ROUND(bl.used_amount / bl.total_amount * 100, 1) AS execute_rate
FROM budget_line bl
JOIN budget b ON bl.budget_id = b.id
WHERE b.department_id = #{deptId} AND b.fiscal_year = #{year}
  AND b.deleted = 0 AND bl.deleted = 0
```

### 4.3 部门费用排行
**GET /api/reports/department-ranking?year=&month=**

查询表：`costlink_reimbursement.reimbursement`

```sql
SELECT department_id,
       COUNT(*) AS count,
       SUM(total_amount) AS total_amount
FROM reimbursement
WHERE YEAR(submit_time) = #{year}
  AND MONTH(submit_time) = #{month}
  AND status IN ('APPROVED', 'PAID')
  AND deleted = 0
GROUP BY department_id
ORDER BY total_amount DESC
```

### 4.4 月度趋势
**GET /api/reports/monthly-trend?year=&deptId=**

查询表：`costlink_reimbursement.reimbursement`

```sql
SELECT MONTH(submit_time) AS month,
       COUNT(*) AS count,
       SUM(total_amount) AS amount
FROM reimbursement
WHERE YEAR(submit_time) = #{year}
  AND department_id = #{deptId}
  AND status IN ('APPROVED', 'PAID')
  AND deleted = 0
GROUP BY MONTH(submit_time)
ORDER BY month
```

### 4.5 个人汇总
**GET /api/reports/personal-summary?userId=&year=**

查询表：`costlink_reimbursement.reimbursement`

```sql
SELECT applicant_id,
       COUNT(*) AS total_count,
       SUM(total_amount) AS total_amount,
       SUM(CASE WHEN status='PAID' THEN total_amount ELSE 0 END) AS paid_amount
FROM reimbursement
WHERE applicant_id = #{userId}
  AND YEAR(submit_time) = #{year}
  AND deleted = 0
GROUP BY applicant_id
```

所有接口都是 GET，只做 SELECT，不写数据。

## 5. 你要写的代码文件

```
costlink-report/src/main/java/com/costlink/report/
├── ReportApplication.java              ← @SpringBootApplication
├── controller/
│   └── ReportController.java           ← 5 个报表接口
├── service/
│   ├── ReportService.java              ← 接口
│   └── impl/ReportServiceImpl.java     ← 跨库聚合查询
├── mapper/
│   ├── reimbursement/
│   │   └── ReimbursementStatMapper.java
│   ├── budget/
│   │   └── BudgetStatMapper.java
│   └── approval/
│       └── ApprovalStatMapper.java
├── config/
│   ├── ReimbursementDbConfig.java        ← 报销数据源（@Primary）
│   ├── BudgetDbConfig.java               ← 预算数据源
│   └── ApprovalDbConfig.java             ← 审批数据源
└── dto/
    ├── ReimbursementSummaryVO.java
    ├── BudgetExecutionVO.java
    ├── DepartmentRankingVO.java
    ├── MonthlyTrendVO.java
    └── PersonalSummaryVO.java
```

## 6. Nacos 配置

```yaml
server:
  port: 8087

costlink:
  report:
    datasources:
      reimbursement:
        driver-class-name: com.mysql.cj.jdbc.Driver
        url: jdbc:mysql://127.0.0.1:3306/costlink_reimbursement?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf8&allowPublicKeyRetrieval=true
        username: ${DB_USERNAME:root}
        password: ${DB_PASSWORD:}
        hikari:
          maximum-pool-size: 3
          minimum-idle: 1
          read-only: true
      budget:
        driver-class-name: com.mysql.cj.jdbc.Driver
        url: jdbc:mysql://127.0.0.1:3306/costlink_budget?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf8&allowPublicKeyRetrieval=true
        username: ${DB_USERNAME:root}
        password: ${DB_PASSWORD:}
        hikari:
          maximum-pool-size: 3
          minimum-idle: 1
          read-only: true
      approval:
        driver-class-name: com.mysql.cj.jdbc.Driver
        url: jdbc:mysql://127.0.0.1:3306/costlink_approval?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf8&allowPublicKeyRetrieval=true
        username: ${DB_USERNAME:root}
        password: ${DB_PASSWORD:}
        hikari:
          maximum-pool-size: 3
          minimum-idle: 1
          read-only: true
    query-timeout: 30
    export-max-rows: 50000
    cache-ttl-minutes: 5
```

## 7. 编码规范

1. 所有查询只在 Mapper XML 或 MyBatis-Plus 的 QueryWrapper 里做 SELECT，禁止一条 INSERT/UPDATE/DELETE
2. 跨库查询在 Service 层聚合——先查 reimbursement，再查 budget，内存拼结果
3. 大时间范围的查询一定要加时间条件，防止全表扫描
4. 不需要 MetaObjectHandler（只有读操作，不涉及 create_time 填充）
5. 不需要 JwtUtil Bean（没有 JWT 签发需求，也不需要解析——当前用户由 Gateway Header 提供）

## 8. 验证方法

```bash
# 报销汇总
curl "http://127.0.0.1:8087/api/reports/reimbursement-summary?deptId=10&year=2026"

# 预算执行率
curl "http://127.0.0.1:8087/api/reports/budget-execution?deptId=10&year=2026"

# 部门排行
curl "http://127.0.0.1:8087/api/reports/department-ranking?year=2026&month=7"
```

数据库里只要有报销和预算的测试数据就能返回有意义的结果。数据来源：之前验证报销和预算服务时已经创建了测试数据。如果没有，通过报销服务的创建接口新增几条报销单，预算服务冻结后就有数据可查。

## 9. 检查清单

- [ ] 三个数据源全部配置，`@Primary` 标注主数据源
- [ ] 每个 Mapper 绑定正确的数据源
- [ ] 所有查询只做 SELECT，无任何写操作
- [ ] 跨库查询在 Service 层聚合
- [ ] 无 Feign、无 MQ、无 Redis 依赖
- [ ] 查询结果为空时返回空列表而不是 null
