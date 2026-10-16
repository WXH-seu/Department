# Department

服务部人员与工作管理系统。

## 项目目标

本项目面向十几到几十名内部成员，用于管理人员、组织、小组、工作任务、活动排班、通知和操作日志。

系统计划部署在一台常开的 Windows 主机上：校内通过 HTTPS 访问，校外通过学校 VPN 进入校园网后访问。

## 技术栈

- 前端：Vue 3、TypeScript、Vite
- 后端：Java 21、Spring Boot 3、Spring Security、Spring JDBC、Flyway
- 数据库：MySQL 8
- 部署：Windows、Nginx、Windows 服务

## 项目结构

```text
Department/
├─ backend/       Spring Boot 后端
├─ frontend/      Vue 前端
├─ deploy/        Windows 与 Nginx 部署配置
└─ docs/          架构和开发文档
```

## 第一版范围

- 用户登录和角色权限
- 人员、小组和职务管理
- 工作创建、分配、进度更新和验收
- 评论与附件
- 首页待办
- 操作日志

活动、排班、签到和复杂统计在后续版本中实现。

## 开发约定

- 默认分支：`main`
- 功能分支：`feature/<功能名称>`
- 修复分支：`fix/<问题名称>`
- 提交信息建议遵循 Conventional Commits
- 不要提交密码、证书、生产配置、数据库文件或上传附件

详细设计见 [docs/architecture.md](docs/architecture.md)。

现状审查、后续开发顺序和验收条件见 [docs/development-plan.md](docs/development-plan.md)。
Windows 上线、HTTPS、备份和恢复步骤见 [docs/deployment.md](docs/deployment.md)。

## 本地开发

需要 Java 21、Maven 3.9+ 和 Node.js 22.12+（22 LTS）。先确认 `java -version` 为 21。

后端在 `backend` 目录运行 `mvn test` 验证，再运行 `mvn spring-boot:run` 启动。
前端在 `frontend` 目录运行 `npm ci` 安装锁定的依赖，运行 `npm run dev` 启动，运行 `npm run build` 检查类型并构建。
开发时前端将 `/api` 请求转发到本机 8080 端口。

启动后端前创建空的 MySQL 数据库 `department`，通过环境变量 `DEPARTMENT_DB_USERNAME`、`DEPARTMENT_DB_PASSWORD` 提供数据库凭据，可用 `DEPARTMENT_DB_URL` 覆盖连接地址。Flyway 在启动时创建并版本化管理表结构。测试使用独立的 H2 内存数据库，不需要本机 MySQL。

第一版已提供数据库账号认证、登录工作台、人员与小组、任务闭环、通知、评论、附件和操作日志。系统不存在内置默认账号或密码；首次启动必须通过下述一次性初始化入口创建管理员。
GitHub Actions 会分别检查后端测试与前端构建。

### 会话接口

- `GET /api/v1/auth/csrf`：获取 `headerName` 和 `token`，客户端必须保留会话 Cookie。
- `POST /api/v1/auth/login`：表单字段 `username`、`password`，并携带上述 CSRF 请求头；成功返回 204，凭据错误返回 401。
- `GET /api/v1/auth/me`：返回当前用户名和角色；未登录返回 401。
- `POST /api/v1/auth/logout`：携带 CSRF 请求头，成功返回 204 并注销会话。

登录后重新获取 CSRF Token；所有写请求都需要 CSRF 保护。生产环境必须启用 HTTPS 和生产配置中的 Secure Cookie。

登录按规范化账号名限流：15 分钟固定窗口内最多 5 次尝试，成功登录清零；达到限制返回 429 和 `Retry-After` 秒数。等待期间即使密码正确也需要等待窗口到期。计数保存在单机进程内，重启会清空；最多保留 10,000 个活跃账号键，容量用尽时暂时拒绝新键。部署为多实例前必须改为共享存储，并补充网关来源限流。

已登录账号在每次访问受保护接口前重新校验数据库状态。账号停用、删除或角色集合变更后，下一次受保护请求返回 401 并使该会话失效，需要重新登录；已开始执行的请求不受此机制追溯撤销。

### 首次初始化管理员

仅在首次启动空数据库时，在启动进程环境中设置以下变量：

| 环境变量 | 含义 |
| --- | --- |
| `DEPARTMENT_BOOTSTRAP_ENABLED` | 设置为 `true` 才启用初始化，默认关闭 |
| `DEPARTMENT_BOOTSTRAP_USERNAME` | 3–64 位英文字母、数字、点、下划线或连字符 |
| `DEPARTMENT_BOOTSTRAP_PASSWORD` | 自行设置的密码，至少 12 个字符，UTF-8 编码最多 72 字节 |

初始化成功后移除这三个变量，再正常启动。密码以 BCrypt 哈希保存，程序不会输出密码。凭据无效会中止启动；数据库已有任何账号则跳过，不重置或提升已有账号。此入口只用于新安装，不用于找回密码。请勿将真实密码写进版本库或命令行参数。

真实成员账号可从部门成员 Excel 表一次性导入，操作方式和职务映射见 [成员账号导入说明](docs/member-import.md)。导入过程只读取学号、姓名和职务，不保存一卡通号或手机号。

