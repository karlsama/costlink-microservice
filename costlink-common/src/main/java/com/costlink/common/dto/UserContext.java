package com.costlink.common.dto;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Data;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 用户上下文 — 从 Gateway 注入的 Header 中提取当前用户信息
 * 每个服务都需要注册 UserContextFilter
 */
public class UserContext {

    private static final ThreadLocal<UserInfo> CONTEXT = new ThreadLocal<>();

    public static void set(UserInfo user) { CONTEXT.set(user); }
    public static UserInfo get()               { return CONTEXT.get(); }
    public static Long getUserId()             { UserInfo u = CONTEXT.get(); return u != null ? u.getUserId() : null; }
    public static String getRole()             { UserInfo u = CONTEXT.get(); return u != null ? u.getRole() : null; }
    public static Long getDepartmentId()       { UserInfo u = CONTEXT.get(); return u != null ? u.getDepartmentId() : null; }
    public static void clear()                 { CONTEXT.remove(); }

    @Data
    public static class UserInfo {
        private Long userId;
        private String role;
        private Long departmentId;
    }

    /**
     * 每个服务在配置类中注册此 Filter
     * 仅 Servlet 环境生效（Gateway 为响应式环境，不注册此 bean）
     */
    @Component
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    public static class UserContextFilter implements Filter {
        @Override
        public void doFilter(ServletRequest request, ServletResponse response,
                             FilterChain chain) throws IOException, ServletException {
            HttpServletRequest httpRequest = (HttpServletRequest) request;
            UserInfo user = new UserInfo();
            user.setUserId(parseLong(httpRequest.getHeader("X-User-Id")));
            user.setRole(httpRequest.getHeader("X-User-Role"));
            user.setDepartmentId(parseLong(httpRequest.getHeader("X-Department-Id")));
            UserContext.set(user);
            try {
                chain.doFilter(request, response);
            } finally {
                UserContext.clear();
            }
        }

        private Long parseLong(String value) {
            if (value == null || value.isBlank()) return null;
            try { return Long.parseLong(value); }
            catch (NumberFormatException e) { return null; }
        }
    }
}
