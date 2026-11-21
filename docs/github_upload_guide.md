# 童趣 · 上传 GitHub 详细步骤（零基础版）

> 前提：已有 GitHub 账号；本机尚未安装 Git。项目已在 `E:\TongQu` 完成脱敏自查。

---

## 第 0 步：安装 Git（只需一次）

1. 打开 https://git-scm.com/download/win
2. 点击 **Download for Windows**（64-bit Git for Windows Setup），下载后双击安装。
3. 安装过程一路点 **Next**（默认选项即可，约 10 步），最后 Install → Finish。
4. 验证：按 `Win+R` 输入 `cmd` 回车，在黑窗口输入：
   ```
   git --version
   ```
   显示 `git version 2.x.x` 即安装成功。

## 第 1 步：在 GitHub 网站创建空仓库

1. 浏览器打开 https://github.com/new （登录后进入新建仓库页）。
2. 填写：
   - **Repository name**：`tongqu-antiaddiction`（或你喜欢的名字）
   - **Description**：儿童防沉迷学习守护 APP（可选）
   - 选择 **Private**（推荐，私有仓库只有你能看；想开源再改 Public）
   - ⚠️ 下面三个勾选框（Add README / .gitignore / license）**全部不要勾**——本地已经有这些文件，勾了会造成冲突。
3. 点绿色按钮 **Create repository**。
4. 创建后页面会显示一个仓库地址，形如：
   ```
   https://github.com/你的用户名/tongqu-antiaddiction.git
   ```
   复制这个地址备用。

## 第 2 步：配置 Git 身份（只需一次）

在 cmd 窗口里执行（用户名/邮箱换成你自己的，邮箱随意但建议真实）：
```
git config --global user.name "你的名字"
git config --global user.email "你的邮箱@example.com"
```

## 第 3 步：生成访问令牌（Token）——代替密码

GitHub 已不允许用账号密码推送，需要生成一个 **Personal Access Token**：

1. 打开 https://github.com/settings/tokens
2. 点 **Generate new token** → **Generate new token (classic)**
3. **Note** 填 `tongqu-upload`；**Expiration** 选 `No expiration`（或 1 年）
4. 勾选权限：只勾 **repo**（第一组）就够
5. 点页面底部绿色 **Generate token**
6. **立刻复制**显示出来的令牌（`ghp_` 开头的一长串）——这个页面只显示一次，刷新就看不到了。保存到安全的地方（比如记事本）。

## 第 4 步：在 E:\TongQu 初始化并推送

在 cmd 窗口逐行执行（`你的用户名` 和令牌处替换成你自己的）：

```
cd /d E:\TongQu

git init
git add .
git commit -m "童趣 V2.0：儿童防沉迷学习守护 APP 首次提交"
git branch -M main
git remote add origin https://github.com/你的用户名/tongqu-antiaddiction.git
git push -u origin main
```

- 执行 `git push` 时会弹出登录窗口（或要求输入）：
  - 用户名：你的 GitHub 用户名
  - 密码：**粘贴刚才的 Token**（不是账号密码！）
- 第一次推送会上传几分钟，完成后刷新 GitHub 页面即可看到全部代码。

> 如果弹出的是 GitHub 登录网页（Git Credential Manager），直接在网页点 **Authorize** 更简单，不用手动粘 Token。

## 第 5 步：以后更新代码再上传

改完代码后，在 `E:\TongQu` 目录：
```
git add .
git commit -m "写一句这次改了什么"
git push
```

## 常见问题

| 现象 | 处理 |
|---|---|
| `git push` 提示 403 / Authentication failed | Token 过期或没勾 repo 权限，重新生成一个 |
| 提示 remote 已存在 | 执行 `git remote set-url origin 新地址` 后再 push |
| 想改仓库名/改公开私有 | GitHub 仓库页 → Settings → 改名/改可见性 |
| 检查将要上传的文件清单 | `git status` —— `local.properties`（含密码）必须**不在**列表里，它在 .gitignore 中会被自动排除 |

## 上传前安全检查（已完成 ✓）

- [x] 全仓扫描：无家长密码、锁屏密码、真实 IP、设备序列号残留
- [x] `local.properties`（含万能密码）在 `.gitignore` 中，不会被上传
- [x] README 含免责声明、使用方法、题库建设、服务器搭建说明
- [x] MIT LICENSE 已就位
