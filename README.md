# WeTrace Java

PC 微信聊天记录取证与分析工具（Java 重构版）。

- 后端：Spring Boot 3（Java 17）
- 前端：Vue 3 + Vite
- 运行平台：Windows（依赖 `wx_key.dll` 注入能力）

> 免责声明：请仅在合法授权场景下使用本项目，遵守当地法律法规与隐私要求。

## 项目结构

```text
wetrace-java/
  backend/   # Spring Boot API + Native 适配 + 解密逻辑
  frontend/  # Vue3 前端
  build.bat  # 一键构建脚本（Windows）
```

## 环境要求

- JDK 17（必须）
- Maven 3.9+
- Node.js 18+
- npm 9+
- Windows 10/11 x64

## 快速开始（开发模式）

### 1) 启动后端

```powershell
Set-Location D:\Projects\wetrace-java\backend
mvn spring-boot:run
```

后端默认监听：`http://127.0.0.1:8080`

### 2) 启动前端

```powershell
Set-Location D:\Projects\wetrace-java\frontend
npm install
npm run dev
```

前端默认地址：`http://127.0.0.1:5173`（已代理 `/api` 到后端）

### 3) 一键构建（可选）

```powershell
Set-Location D:\Projects\wetrace-java
.\build.bat
```

## 配置说明

- 后端主配置：`backend/src/main/resources/application.yml`
- 本地密钥缓存：项目根目录 `.env`

运行后，系统会尝试把密钥写入 `.env`：

```dotenv
WECHAT_DB_KEY=
IMAGE_KEY=
XOR_KEY=
```

## Native 依赖说明

`wx_key.dll` 需放在 `backend/src/main/resources/native/` 并随后端打包。

- DLL 需要与当前微信版本、系统位数（x64）匹配
- 若 DLL 版本不匹配，常见报错为 `GetWechatVersion failed`
- 详细说明见：`backend/src/main/resources/native/README.md`

## 常见问题（重点）

### 1) Hook 注入失败 / 获取微信版本失败

典型日志：`InitializeHook failed: 获取微信版本失败，目标进程可能已退出`

建议排查顺序：

1. 以**管理员身份**启动后端进程
2. 确认微信已稳定停留在主界面（不要处于登录/扫码切换中）
3. 确认 `wx_key.dll` 与当前微信版本匹配
4. 关闭可能拦截注入的安全软件后重试

### 2) 选错 PID，无法正确退出但会弹登录

这是多进程场景下常见现象（同名 `Weixin` 可能有多个 PID）。

建议：

1. 先手动关闭全部微信实例后再操作
2. 用 PowerShell 检查当前微信进程列表并观察内存占用较高的主进程
3. 确保只有你当前会话对应的微信实例在运行

可用命令：

```powershell
Get-Process Weixin,WeChat -ErrorAction SilentlyContinue | Select-Object Id,ProcessName,WS,CPU
```

### 3) 为什么日志里 PID 总是同一个

`KeyService` 会按“窗口优先 + 进程回退”策略选 PID，并在同一会话中重试同一候选集合；
如果窗口识别持续锁定到同一候选，日志会看到重复 PID。可优先从微信进程数量和 DLL 兼容性排查。

## 常用 API（后端）

基础前缀：`/api/v1`

- `GET /system/status`：系统状态
- `GET /system/wxkey/db`：SSE 获取数据库密钥
- `GET /system/wxkey/image`：提取图片密钥
- `POST /system/decrypt`：SSE 解密数据库
- `GET /system/detect/wechat_path`：探测微信安装路径
- `GET /system/detect/db_path`：探测微信数据目录

## 调试建议

- 关注后端日志关键字：`[wxhook]`、`InitializeHook failed`
- 先验证后端接口可用：`GET /api/v1/system/status`
- 在故障定位时尽量保持单一微信实例，减少 PID 歧义

## 致谢
- 本项目的开发过程中参考了以下优秀的开源项目和资源：
- wx_key - 微信数据库与图片密钥提取工具
- wetrace -go语言加re'ce'a't说明

- 后端主配置：`backend/src/main/resources/application.yml`
- 本地密钥缓存：项目根目录 `.env`

运行后，系统会尝试把密钥写入 `.env`：

```dotenv
WECHAT_DB_KEY=
IMAGE_KEY=
XOR_KEY=
```

## Native 依赖说明

`wx_key.dll` 需放在 `backend/src/main/resources/native/` 并随后端打包。

- DLL 需要与当前微信版本、系统位数（x64）匹配
- 若 DLL 版本不匹配，常见报错为 `GetWechatVersion failed`
- 详细说明见：`backend/src/main/resources/native/README.md`

## 常见问题（重点）

### 1) Hook 注入失败 / 获取微信版本失败

典型日志：`InitializeHook failed: 获取微信版本失败，目标进程可能已退出`

建议排查顺序：

1. 以**管理员身份**启动后端进程
2. 确认微信已稳定停留在主界面（不要处于登录/扫码切换中）
3. 确认 `wx_key.dll` 与当前微信版本匹配
4. 关闭可能拦截注入的安全软件后重试

### 2) 选错 PID，无法正确退出但会弹登录

这是多进程场景下常见现象（同名 `Weixin` 可能有多个 PID）。

建议：

1. 先手动关闭全部微信实例后再操作
2. 用 PowerShell 检查当前微信进程列表并观察内存占用较高的主进程
3. 确保只有你当前会话对应的微信实例在运行

可用命令：

```powershell
Get-Process Weixin,WeChat -ErrorAction SilentlyContinue | Select-Object Id,ProcessName,WS,CPU
```

### 3) 为什么日志里 PID 总是同一个

`KeyService` 会按“窗口优先 + 进程回退”策略选 PID，并在同一会话中重试同一候选集合；
如果窗口识别持续锁定到同一候选，日志会看到重复 PID。可优先从微信进程数量和 DLL 兼容性排查。

## 常用 API（后端）

基础前缀：`/api/v1`

- `GET /system/status`：系统状态
- `GET /system/wxkey/db`：SSE 获取数据库密钥
- `GET /system/wxkey/image`：提取图片密钥
- `POST /system/decrypt`：SSE 解密数据库
- `GET /system/detect/wechat_path`：探测微信安装路径
- `GET /system/detect/db_path`：探测微信数据目录

## 调试建议

- 关注后端日志关键字：`[wxhook]`、`InitializeHook failed`
- 先验证后端接口可用：`GET /api/v1/system/status`
- 在故障定位时尽量保持单一微信实例，减少 PID 歧义

## 致谢
- 本项目的开发过程中参考了以下优秀的开源项目和资源：
- wx_key - 微信数据库与图片密钥提取工具
- wetrace -go语言加react实现，扩展了很多功能

