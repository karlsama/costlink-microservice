# costlink-auth 开发指南

> 面向实际开发，一份文档写完认证服务。不需要来回翻其他文件。
> 2026-07-02

---

## 1. 你要做一个什么

**一句话**：一个认证服务，完成"用户登录 → 签发 JWT Token → Gateway 拿 Token 做鉴权"这一整条链路。同时提供内部接口供审批服务查询用户列表。

**你的上游**：前端登录页 → 调 `POST /api/auth/login`

**你的下游**：审批服务通过 Feign 调 `GET /internal/users/{userId}` 查审批人姓名

**你的基础设施**：

| 依赖 | 地址 | 数据/用途 |
|-----|------|----------|
| MySQL `costlink_shared` | `127.0.0.1:3306` | user 表（已有，init.sql 建好了） |
| Redis | `127.0.0.1:6379` | 存 Refresh Token（登出时可撤销） |
| Nacos | `127.0.0.1:8848` | 服务注册 + 拉配置 |

---

## 2. 数据库——直接能用

`costlink_shared.user` 表（init.sql 已建好，不要重新建）：

```sql
-- 已有数据
id              BIGINT PRIMARY KEY       -- 雪花算法
username        VARCHAR(50) UNIQUE       -- 登录名
password        VARCHAR(200)             -- BCrypt 哈希后的密码
display_name    VARCHAR(50)              -- 显示姓名
email           VARCHAR(100)
role            VARCHAR(50) DEFAULT 'EMPLOYEE'
department_id   BIGINT
department_name VARCHAR(100)
status          VARCHAR(20) DEFAULT 'ACTIVE'   -- ACTIVE / DISABLED
deleted         TINYINT DEFAULT 0

-- 已有一条管理员数据 (id=1, username=admin)
```

---

## 3. 你要写的接口——严格对号入座

### 3.1 对外接口（走 Gateway，前端调）

**POST /api/auth/login**

```java
// 请求 - 跟你之前定的一模一样
{
  "username": "admin",
  "password": "admin123"
}

// 返回 - 必须用 Result 包裹
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
    "refreshToken": "dGhpcyBpcyBhIHJlZnJl...",
    "expiresIn": 1800,
    "tokenType": "Bearer",
    "userInfo": {
      "id": 1,
      "username": "admin",
      "displayName": "系统管理员",
      "role": "ADMIN",
      "departmentId": 0,
      "departmentName": "总经办"
    }
  }
}
```

**POST /api/auth/refresh**

```java
// 用 Refresh Token 换新的 Access Token
// 请求
{
  "refreshToken": "dGhpcyBpcyBhIHJlZnJl..."
}

// 返回 - 跟 login 一样结构
```

**POST /api/auth/logout**

```java
// 把 Token 加入 Redis 黑名单（可选实现）
// 请求 - 不传 body, Token 在 Header 里
Authorization: Bearer eyJhbGci...
// 返回 - Result.ok()
```

### 3.2 内部接口（Feign 调用，不进 Gateway）

这些接口在 `costlink-common` 里已经定义好了——你打开 `com.costlink.common.feign.AuthClient`，对着它实现。

**GET /internal/users/{userId}**

```java
// 审批服务调用 — 查审批人姓名
// 返回
{
  "code": 200,
  "data": {
    "id": 5,
    "username": "zhangsan",
    "displayName": "张三",
    "role": "DEPARTMENT_HEAD",
    "departmentId": 10,
    "email": "zhangsan@example.com"
  }
}
```

**GET /internal/users/by-role?role=DEPARTMENT_HEAD&departmentId=10**

```java
// 审批服务调用 — 查某部门某角色的用户列表
// 返回 Result<List<UserInfoDTO>>
```

---

## 4. 你要写的代码文件——按时序写

### 4.1 目录结构（遵照模块分组规范的 6.2 节）

```
costlink-auth/src/main/java/com/costlink/auth/
├── AuthApplication.java                    ← 启动类（已存在，不需改）
├── controller/
│   ├── AuthController.java                 ← 对外接口: /api/auth/**
│   └── InternalUserController.java         ← Feign接口实现: /internal/users/**
├── service/
│   ├── AuthService.java                    ← 接口
│   └── impl/
│       └── AuthServiceImpl.java            ← 登录逻辑
├── mapper/
│   └── UserMapper.java                     ← MyBatis-Plus
├── entity/
│   └── User.java                           ← 实体
├── dto/
│   ├── LoginRequest.java
│   ├── LoginResponse.java
│   └── RefreshRequest.java
└── config/
    └── SecurityConfig.java                 ← Spring Security
```

### 4.2 User.java（实体）

对着 `costlink_shared.user` 表写，字段名驼峰对照下划线：

```java
@Data
@TableName("user")
public class User {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String username;
    private String password;        // BCrypt 哈希
    private String displayName;
    private String email;
    private String role;            // EMPLOYEE / DEPARTMENT_HEAD / FINANCE / FINANCE_MANAGER / ADMIN
    private Long departmentId;
    private String departmentName;
    private String status;          // ACTIVE / DISABLED
    @TableLogic
    private Integer deleted;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
```

### 4.3 UserMapper.java

```java
@Mapper
public interface UserMapper extends BaseMapper<User> {
    // MyBatis-Plus 自动提供 CRUD，不需要写任何方法
}
```

### 4.4 LoginRequest.java / LoginResponse.java / RefreshRequest.java

```java
// LoginRequest
@Data
public class LoginRequest {
    @NotBlank(message = "用户名不能为空")
    private String username;
    @NotBlank(message = "密码不能为空")
    private String password;
}

// RefreshRequest
@Data
public class RefreshRequest {
    @NotBlank(message = "RefreshToken不能为空")
    private String refreshToken;
}

// LoginResponse
@Data
@Builder
public class LoginResponse {
    private String accessToken;
    private String refreshToken;
    private Long expiresIn;            // 秒
    private String tokenType = "Bearer";
    private AuthClient.UserInfoDTO userInfo;   // ← 复用 common 中的 DTO
}
```

### 4.5 AuthService.java（接口）

```java
public interface AuthService {
    Result<LoginResponse> login(LoginRequest request);
    Result<LoginResponse> refresh(RefreshRequest request);
    void logout(Long userId);
}
```

### 4.6 AuthController.java

```java
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @PostMapping("/refresh")
    public Result<LoginResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return authService.refresh(request);
    }

    @PostMapping("/logout")
    public Result<Void> logout() {
        authService.logout(UserContext.getUserId());
        return Result.ok();
    }
}
```

### 4.7 AuthServiceImpl.java（核心逻辑，含 login/refresh/logout）

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class AuthServiceImpl implements AuthService {

    private final UserMapper userMapper;
    private final StringRedisTemplate redisTemplate;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    @Override
    public Result<LoginResponse> login(LoginRequest request) {
        User user = userMapper.selectOne(
            new LambdaQueryWrapper<User>().eq(User::getUsername, request.getUsername())
        );
        if (user == null) {
            throw new BusinessException(ErrorCode.AUTH_LOGIN_FAILED);
        }
        if (!"ACTIVE".equals(user.getStatus())) {
            throw new BusinessException(ErrorCode.AUTH_ACCOUNT_DISABLED);
        }
        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new BusinessException(ErrorCode.AUTH_LOGIN_FAILED);
        }
        return Result.ok(buildLoginResponse(user));
    }

    @Override
    public Result<LoginResponse> refresh(RefreshRequest request) {
        // 解析 Refresh Token，拿到 userId
        Claims claims;
        try {
            claims = jwtUtil.parseToken(request.getRefreshToken());
        } catch (JwtException e) {
            throw new BusinessException(ErrorCode.AUTH_TOKEN_EXPIRED);
        }
        Long userId = Long.valueOf(claims.getSubject());

        // 验证 Redis 中的 Refresh Token 是否匹配
        String storedToken = redisTemplate.opsForValue().get("auth:refresh:" + userId);
        if (storedToken == null || !storedToken.equals(request.getRefreshToken())) {
            throw new BusinessException(ErrorCode.AUTH_TOKEN_EXPIRED);
        }

        // 查用户信息，重新签发
        User user = userMapper.selectById(userId);
        if (user == null || !"ACTIVE".equals(user.getStatus())) {
            throw new BusinessException(ErrorCode.AUTH_ACCOUNT_DISABLED);
        }

        log.info("Token刷新成功, userId={}", userId);
        return Result.ok(buildLoginResponse(user));
    }

    @Override
    public void logout(Long userId) {
        redisTemplate.delete("auth:refresh:" + userId);
        log.info("登出成功, userId={}", userId);
    }

    // ========== 私有方法 ==========

    private LoginResponse buildLoginResponse(User user) {
        String accessToken = jwtUtil.generateAccessToken(
            user.getId(), user.getRole(), user.getDepartmentId()
        );
        String refreshToken = jwtUtil.generateRefreshToken(user.getId());

        redisTemplate.opsForValue().set(
            "auth:refresh:" + user.getId(),
            refreshToken,
            jwtUtil.getRefreshTokenTtl(),
            TimeUnit.DAYS
        );

        return LoginResponse.builder()
            .accessToken(accessToken)
            .refreshToken(refreshToken)
            .expiresIn(jwtUtil.getAccessTokenTtl())
            .userInfo(toDTO(user))
            .build();
    }

    private AuthClient.UserInfoDTO toDTO(User user) { ... }  // 同之前的版本
}
```

### 4.8 InternalUserController.java

```java
// 注意: 不要 implements AuthClient！
// AuthClient 是 Feign 接口（带 @FeignClient 注解），Controller 不能实现它。
// 两者只是路径和返回格式保持一致，各写各的。

@RestController
@RequestMapping("/internal/users")
@RequiredArgsConstructor
public class InternalUserController {

    private final UserMapper userMapper;

    @GetMapping("/{userId}")
    public Result<AuthClient.UserInfoDTO> getUserById(@PathVariable Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.AUTH_USER_NOT_FOUND);
        }
        return Result.ok(toDTO(user));
    }

    @GetMapping("/by-role")
    public Result<List<AuthClient.UserInfoDTO>> getUsersByRole(
            @RequestParam String role,
            @RequestParam(required = false) Long departmentId) {
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<User>()
            .eq(User::getRole, role)
            .eq(User::getStatus, "ACTIVE");
        if (departmentId != null) {
            wrapper.eq(User::getDepartmentId, departmentId);
        }
        List<User> users = userMapper.selectList(wrapper);
        return Result.ok(users.stream().map(this::toDTO).toList());
    }

    @GetMapping("/by-department/{departmentId}")
    public Result<List<AuthClient.UserInfoDTO>> getUsersByDepartment(
            @PathVariable Long departmentId) {
        List<User> users = userMapper.selectList(
            new LambdaQueryWrapper<User>()
                .eq(User::getDepartmentId, departmentId)
                .eq(User::getStatus, "ACTIVE")
        );
        return Result.ok(users.stream().map(this::toDTO).toList());
    }

    private AuthClient.UserInfoDTO toDTO(User user) {
        AuthClient.UserInfoDTO dto = new AuthClient.UserInfoDTO();
        dto.setId(user.getId());
        dto.setUsername(user.getUsername());
        dto.setDisplayName(user.getDisplayName());
        dto.setRole(user.getRole());
        dto.setDepartmentId(user.getDepartmentId());
        dto.setDepartmentName(user.getDepartmentName());
        dto.setEmail(user.getEmail());
        return dto;
    }
}
```

### 4.9 SecurityConfig.java（含 JWT 过滤器）

```java
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtUtil jwtUtil;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/auth/login", "/api/auth/refresh").permitAll()
                .requestMatchers("/internal/**").permitAll()
                .anyRequest().authenticated()
            )
            .addFilterBefore(jwtAuthFilter(), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * JWT 过滤器 — 从 Authorization Header 解析 JWT，设置 UserContext
     * Gateway 不在时（开发直连），认证服务靠自己解析 Token
     */
    @Bean
    public OncePerRequestFilter jwtAuthFilter() {
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request,
                    HttpServletResponse response, FilterChain filterChain)
                    throws ServletException, IOException {
                String header = request.getHeader(HttpHeaders.AUTHORIZATION);
                if (header != null && header.startsWith("Bearer ")) {
                    try {
                        Claims claims = jwtUtil.parseToken(header.substring(7));
                        UserContext.UserInfo user = new UserContext.UserInfo();
                        user.setUserId(((Number) claims.get("userId")).longValue());
                        user.setRole((String) claims.get("role"));
                        user.setDepartmentId(toLong(claims.get("departmentId")));
                        UserContext.set(user);
                    } catch (JwtException e) {
                        // Token 无效，跳过，Spring Security 后续会拒绝
                    }
                }
                try {
                    filterChain.doFilter(request, response);
                } finally {
                    UserContext.clear();
                }
            }

            private Long toLong(Object val) {
                if (val instanceof Number n) return n.longValue();
                return null;
            }
        };
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public JwtUtil jwtUtil(
            @Value("${costlink.jwt.secret}") String secret,
            @Value("${costlink.jwt.access-token-ttl:30}") long accessTokenTtl,
            @Value("${costlink.jwt.refresh-token-ttl:7}") long refreshTokenTtl) {
        return new JwtUtil(secret, accessTokenTtl, refreshTokenTtl);
    }
}
```

### 4.10 别忘了 @MapperScan

在 `AuthApplication` 启动类上加 `@MapperScan`：

```java
@SpringBootApplication(scanBasePackages = {"com.costlink.auth", "com.costlink.common"})
@EnableDiscoveryClient
@MapperScan("com.costlink.auth.mapper")    // ← 加这行
public class AuthApplication {
    public static void main(String[] args) {
        SpringApplication.run(AuthApplication.class, args);
    }
}
```

---

## 5. JWT 工具类——已在 common 中，直接用

`com.costlink.common.util.JwtUtil` 已经写好了，不需要自己建。关键用法：

```java
// SecurityConfig 中注册为 Bean（见 4.9 节），注入后调用：
String token = jwtUtil.generateAccessToken(userId, role, departmentId);  // 3个参数
long ttl = jwtUtil.getAccessTokenTtl();  // 返回秒

// Access Token 的 Claims 内容（工具类自动生成）:
{
  "sub": "1",
  "userId": 1,
  "role": "ADMIN",
  "departmentId": 0,
  "iat": 1718612345,
  "exp": 1718614145
}
```

**注意**: Claims 的 key（`userId`, `role`, `departmentId`）必须跟 Gateway 的 JwtAuthFilter 解析时一致。`JwtUtil` 已经确保了这一致性，不要自己改。```

---

## 6. Nacos 配置——服务启动时自动拉

你不用本地写 `application.yml`。服务启动时从 Nacos 拉配置，写在 `bootstrap.yml` 里（已经有了，不用改）。

Nacos 里需要有的配置（参考 Nacos 配置手册第 5 节和第 7 节）：

**共享配置** `costlink-shared-dev.yaml`（所有服务共用）：
```yaml
costlink:
  jwt:
    secret: ${JWT_SECRET:CostLink-JWT-Secret-Key-2026-For-Dev-Environment-Only}
    access-token-ttl: 30
    refresh-token-ttl: 7
spring:
  data:
    redis:
      host: ${REDIS_HOST:127.0.0.1}
      port: ${REDIS_PORT:6379}
```

**认证服务独有配置** `costlink-auth-dev.yaml`：
```yaml
spring:
  datasource:
    url: jdbc:mysql://127.0.0.1:3306/costlink_shared?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf8&allowPublicKeyRetrieval=true
    username: ${DB_USERNAME:root}
    password: ${DB_PASSWORD:}
mybatis-plus:
  configuration:
    log-impl: org.apache.ibatis.logging.stdout.StdOutImpl   # dev 打印 SQL
```

---

## 7. 编码规范——几点硬要求

在写代码时必须遵守（来源：模块分组规范第 6 节）：

1. **返回值必须用 `Result<T>` 包裹**，禁止裸对象。用 `Result.ok(data)` 或 `Result.fail(ErrorCode.xxx)`。
2. **错误码用 `ErrorCode` 枚举**，禁止 `return Result.fail(500, "出错了")`。
3. **异常用 `throw new BusinessException(ErrorCode.xxx)`**，不要自己 catch 了 return。
4. **Controller 不写业务逻辑**，只做参数接收 → 调 Service → 返回结果。
5. **日志必须带关键 ID**，写成 `log.info("登录成功, userId={}, username={}", ...)`，禁止 `log.info("登录成功")`。
6. **密码用 BCrypt**，写死在 SecurityConfig 里配好 `PasswordEncoder`。
7. **依赖注入用 `@RequiredArgsConstructor` + `private final`**，不用 `@Autowired`。

---

## 8. 验证方法——写完怎么确认对

开发阶段服务在 IDE 里跑（不是 Docker），用 WSL 的 `127.0.0.1` 连一切：

**启动你的服务**：

在 IDEA 里直接 Run `AuthApplication`，或者命令行：

```powershell
cd F:\project_007\costlink-auth
mvn spring-boot:run
```

服务在 `http://127.0.0.1:8084`。

**验证一：登录**

```bash
curl -X POST http://127.0.0.1:8084/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}'
```

预期得到一个 `code: 200` 的返回，`data.accessToken` 里是一段 JWT。

**验证二：拿 Token 调内部接口**

```bash
# 先用上面拿到的 Token 替换 {TOKEN}
curl http://127.0.0.1:8084/internal/users/1 \
  -H "Authorization: Bearer {TOKEN}"
```

预期返回 `code: 200`，`data.displayName` 是 "系统管理员"。

**验证三：错误码**

```bash
# 错误用户名
curl -X POST http://127.0.0.1:8084/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"nobody","password":"wrong"}'
```

预期返回 `code: 10501`（AUTH_LOGIN_FAILED）。

---

## 9. 检查清单

写完代码后确认以下全部打勾：

- [ ] `POST /api/auth/login` 正确用户名密码返回 200 + JWT
- [ ] 错误密码返回 `code: 10501`
- [ ] 禁用的用户返回 `code: 10503`
- [ ] `GET /internal/users/{userId}` 返回 200 + 正确用户信息
- [ ] 不存在的用户返回 `code: 10504`
- [ ] 返回给前端的 `data` 里永远不包含 `password` 字段
- [ ] 所有日志都带了 `userId`
- [ ] 没有 `System.out.println`，全部用 `log.info/warn/error`
- [ ] 没有裸数字错误码，全部用 `ErrorCode.xxx`
