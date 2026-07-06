@echo off
chcp 65001 >nul
title CostLink 启动器

echo ========================================
echo   CostLink 一键启动
echo ========================================
echo.

:: 检查 Nacos
echo [1/2] 检查 Nacos...
curl -s --max-time 3 http://127.0.0.1:8848/nacos/v2/console/namespace/list >nul 2>&1
if %errorlevel% neq 0 (
    echo   ⚠ Nacos 未运行！请先在 WSL 中启动 Docker 容器。
    echo     命令: docker start <nacos容器名>
    pause
    exit /b
)
echo   ✅ Nacos 已连接
echo.

:: 启动后端服务
echo [2/2] 启动后端服务（8个窗口）...
echo.

set BASE=F:\project_007

:: 认证服务
start "costlink-auth" /min cmd /c "cd /d %BASE%\costlink-auth && mvn spring-boot:run"
echo   [+] 认证服务 (8084) ...
timeout /t 75 /nobreak >nul

:: Gateway
start "costlink-gateway" /min cmd /c "cd /d %BASE%\costlink-gateway && mvn spring-boot:run"
echo   [+] Gateway (8080) ...
timeout /t 65 /nobreak >nul

:: 报销（Mock 模式）
start "costlink-reimbursement" /min cmd /c "cd /d %BASE%\costlink-reimbursement && mvn spring-boot:run -Dspring-boot.run.profiles=dev,mock"
echo   [+] 报销服务 (8081) ...
timeout /t 75 /nobreak >nul

:: 预算
start "costlink-budget" /min cmd /c "cd /d %BASE%\costlink-budget && mvn spring-boot:run"
echo   [+] 预算服务 (8082) ...
timeout /t 75 /nobreak >nul

:: 审批（Mock 模式）
start "costlink-approval" /min cmd /c "cd /d %BASE%\costlink-approval && mvn spring-boot:run -Dspring-boot.run.profiles=dev,mock"
echo   [+] 审批服务 (8083) ...
timeout /t 75 /nobreak >nul

:: OCR
start "costlink-ocr" /min cmd /c "cd /d %BASE%\costlink-ocr && mvn spring-boot:run"
echo   [+] OCR 服务 (8085) ...
timeout /t 75 /nobreak >nul

:: 通知
start "costlink-notification" /min cmd /c "cd /d %BASE%\costlink-notification && mvn spring-boot:run"
echo   [+] 通知服务 (8086) ...
timeout /t 60 /nobreak >nul

:: 报表
start "costlink-report" /min cmd /c "cd /d %BASE%\costlink-report && mvn spring-boot:run"
echo   [+] 报表服务 (8087) ...
timeout /t 60 /nobreak >nul

echo.
echo ✅ 所有后端服务已启动！
echo.
echo 打开前端: http://localhost:3000
echo.
echo 登录: admin / admin123
echo.

:: 最后启动前端
start "costlink-frontend" /min cmd /c "cd /d %BASE%\costlink-frontend && npm run dev"
echo   [+] 前端 (3000) ...
echo.
echo ========================================
echo   启动完成，请稍等 10 秒后刷新前端页面
echo ========================================
pause
