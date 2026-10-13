# Department

服务部人员与工作管理系统。

## 项目目标

本项目面向十几到几十名内部成员，用于管理人员、组织、小组、工作任务、活动排班、通知和操作日志。

系统计划部署在一台常开的 Windows 主机上：校内通过 HTTPS 访问，校外通过学校 VPN 进入校园网后访问。

## 技术栈

- 前端：Vue 3、TypeScript、Vite、Element Plus
- 后端：Java 21、Spring Boot 3、Spring Security、MyBatis-Plus
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

