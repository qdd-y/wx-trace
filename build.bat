@echo off
chcp 65001 >nul
echo ========================================
echo  WeTrace Java 重构版 - 一键构建脚本
echo ========================================
echo.

cd /d "%~dp0"

REM 检查 Java
java -version >nul 2>&1
if errorlevel 1 (
    echo [ERROR] 未检测到 Java，请先安装 JDK 17+
    pause
    exit /b 1
)

REM 检查 Maven
mvn -version >nul 2>&1
if errorlevel 1 (
    echo [ERROR] 未检测到 Maven，请先安装 Maven 3.9+
    pause
    exit /b 1
)

echo [1/3] 构建后端 (Spring Boot)...
cd backend
call mvn clean package -DskipTests
if errorlevel 1 (
    echo [ERROR] 后端构建失败
    pause
    exit /b 1
)
cd ..

echo [2/3] 构建前端 (Vue)...
cd frontend
call npm install
if errorlevel 1 (
    echo [ERROR] npm 依赖安装失败
    pause
    exit /b 1
)

call npm run build
if errorlevel 1 (
    echo [ERROR] 前端构建失败
    pause
    exit /b 1
)
cd ..

echo [3/3] 复制前端资源到后端...
if not exist "backend\src\main\resources\static" mkdir backend\src\main\resources\static
xcopy /E /Y "frontend\dist\*" "backend\src\main\resources\static\" >nul

echo.
echo ========================================
echo  构建完成！
echo.
echo  启动方式：
echo    cd backend
echo    mvn spring-boot:run
echo.
echo  或双击运行：
echo    backend\target\wetrace-backend-1.0.0.jar
echo ========================================
pause
