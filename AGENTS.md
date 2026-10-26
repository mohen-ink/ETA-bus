# AGENTS.md

## 发布新版本（推 Tag）

推送 `v*` 格式的 Tag 会触发 `.github/workflows/release.yml`：构建 arm64-v8a 的 release APK，并发布到 GitHub Release。Release 说明从 `CHANGELOG.md` 中提取。

### 步骤

1. 在 `CHANGELOG.md` 顶部（`# 更新日志` 标题之下、已有版本之上）新增本次版本段落：

   ```markdown
   ## [0.2.0] - 2026-11-01

   ### 新增
   - ……

   ### 修复
   - ……
   ```

   - 标题必须是 `## [版本号]`，版本号与 Tag 去掉前缀 `v` 后完全一致（Tag `v0.2.0` 对应 `## [0.2.0]`）。
   - 内容面向用户，用中文描述功能变化，不写实现细节。
   - 段落内容截止到下一个 `## ` 标题，不要在段落内使用二级标题。

2. 提交并推送到 `main`：

   ```bash
   git add CHANGELOG.md
   git commit -m "更新 0.2.0 版本日志"
   git push origin main
   ```

3. 在要发布的提交上打 Tag 并推送：

   ```bash
   git tag v0.2.0
   git push origin v0.2.0
   ```

### 注意事项

- 版本号取自 Tag：`versionName` 为 Tag 去掉 `v`，`versionCode` 为 Actions 运行编号，不需要修改 `app/build.gradle.kts`。
- 工作流在构建前先检查 CHANGELOG；找不到对应版本段落会直接失败，补上后重新推 Tag。
- 构建依赖仓库 Secrets：`KEYSTORE_BASE64`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`、`AMAP_API_KEY`，不要把签名文件、密码或高德 Key 提交到仓库。
- 推 Tag 前确认本地 `./gradlew :app:assembleRelease -PabiFilters=arm64-v8a` 能通过，release 包开启了混淆，调试包正常不代表正式包正常。
- 进度查看：`gh run list -R mohen-ink/ETA-bus --limit 3`。

### 重新发布同一版本

构建失败或需要替换已发布的安装包时，删除旧 Release 与 Tag 后在新提交上重打：

```bash
gh release delete v0.2.0 -R mohen-ink/ETA-bus -y --cleanup-tag
git tag -d v0.2.0
git tag v0.2.0
git push origin v0.2.0
```

已经对外发布且有人下载过的版本，优先递增版本号发布修复版，而不是覆盖原版本。
