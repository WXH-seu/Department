# Windows 部署与恢复

本文面向第一版单机部署：Windows 主机、MySQL 8.4 LTS、Java 21、Nginx。正式上线前先在隔离环境完整演练一次。

## 目录和网络

建议使用以下目录：

```text
D:\ServiceDepartment\
├─ backend.jar
├─ config\application-prod.yml
├─ data\uploads\
├─ frontend\dist\
├─ certs\
└─ logs\
```

Windows 防火墙只允许校园网访问 TCP 443；8080 和 3306 只监听本机。校外用户先连接学校 VPN。不要开放远程桌面端口到校园网。

## 构建

在项目根目录执行：

```powershell
$env:JAVA_HOME='D:\path\to\jdk-21'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
Set-Location backend
mvn.cmd clean verify
mvn.cmd package -DskipTests
Set-Location ..\frontend
npm.cmd ci
npm.cmd test
npm.cmd run build
```

将后端 jar 和 `frontend/dist` 复制到部署目录。使用 [application-prod.yml.example](../deploy/application-prod.yml.example) 创建外部 `application-prod.yml`，不得提交生产副本。

## 数据库与首次管理员

创建 `utf8mb4` 数据库和仅限本机连接的应用账号，为该账号授予目标数据库的常规 DDL/DML 权限。通过 Windows 服务账号的环境变量设置：

- `DEPARTMENT_DB_USERNAME`
- `DEPARTMENT_DB_PASSWORD`
- `DEPARTMENT_BOOTSTRAP_ENABLED=true`
- `DEPARTMENT_BOOTSTRAP_USERNAME`
- `DEPARTMENT_BOOTSTRAP_PASSWORD`

首次启动成功后，立即删除三个 `DEPARTMENT_BOOTSTRAP_*` 变量并重启服务。之后通过网页登录，创建第二名管理员并修改初始密码。

## Windows 服务和 HTTPS

[department-service.xml.example](../deploy/department-service.xml.example) 可配合 WinSW 注册后端服务。把 WinSW 可执行文件和改名后的 XML 放在同一目录，按实际 Java、jar、配置和日志路径修改。服务使用专用的低权限 Windows 账号，该账号只需读取程序与配置、写入日志和附件目录。

以 [nginx.conf.example](../deploy/nginx.conf.example) 为基础配置域名和学校认可的证书。HTTP 仅重定向 HTTPS，正式服务由 443 提供。证书私钥只允许 Nginx 服务账号读取。

上线后检查：

1. `https://域名/api/v1/health` 返回 `{"status":"ok"}`。
2. 匿名访问 `/api/v1/auth/me` 返回 401；登录、退出和重新登录可用。
3. 分别以管理员、部长、组长、部员验证人员与任务权限。
4. 上传、下载一个小附件，确认未授权账号不能访问。
5. 重启 Nginx、后端和 MySQL，确认数据仍在。

## 备份

创建 MySQL 客户端凭据文件，例如：

```ini
[client]
host=127.0.0.1
port=3306
user=department_backup
password=使用独立备份密码
```

限制该文件 ACL，仅允许运行备份的服务账号读取。每天在任务计划程序中先停止后端，再运行：

```powershell
.\backup.ps1 -MySqlBin 'C:\Program Files\MySQL\MySQL Server 8.4\bin' `
  -CredentialsFile 'D:\ServiceDepartment\config\backup-client.ini' `
  -Uploads 'D:\ServiceDepartment\data\uploads' `
  -BackupRoot 'E:\DepartmentBackups' -Database department -RetentionDays 30 -ApplicationStopped
```

脚本只有在数据库导出和附件复制都成功后才写入 `COMPLETE`；完整备份超过保留期后才会清理。任务结束后启动后端，并监控任务退出码。再将完整备份复制到 NAS 或另一台电脑。

## 恢复演练

恢复必须使用新建的空数据库和不存在的附件目录。停止后端后运行：

```powershell
.\restore.ps1 -MySqlBin 'C:\Program Files\MySQL\MySQL Server 8.4\bin' `
  -CredentialsFile 'D:\ServiceDepartment\config\backup-client.ini' `
  -BackupDirectory 'E:\DepartmentBackups\20261016-020000-000' `
  -Database department_restore `
  -Uploads 'D:\ServiceDepartment\data\uploads-restored' -ApplicationStopped
```

恢复脚本会先验证清单和每个文件的 SHA-256，并拒绝覆盖已有附件目录。修改测试配置指向恢复数据库和附件目录，执行登录、任务列表和附件下载检查；确认后再按维护流程切换正式配置。每月至少演练一次，并记录日期、备份目录、执行人和结果。

## 上线前仍需提供的信息

- 校园网正式域名及 DNS 记录；
- 学校认可的 TLS 证书；
- 正式 Windows 服务账号；
- MySQL 应用账号与备份账号；
- NAS 或异机备份路径；
- 部门真实成员、角色、小组和职务名单。
