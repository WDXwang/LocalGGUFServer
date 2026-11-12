# releases/

这个目录**只放当前最新的一个 APK**，历史版本一律放在 GitHub Releases。

## 为什么这么分

- git 里的二进制删不掉：即使以后从工作区删掉，历史里仍然留着一份，从此 clone 仓库的人都要拉这份 22 MB 左右的数据。所以仓库里每个版本只留一份，靠"只保留最新"把体积增长控制在恒定水平。
- Releases 的附件不进 git 历史，旧的可以一直留着给人下载，仓库也不会膨胀。

## 目录约定

```
releases/
└── LocalGGUFServer-<version>-debug-<arch>.apk   # 只有当前最新版
```

历史版本：
https://github.com/WDXwang/LocalGGUFServer/releases

## 发版流程

1. 构建出 APK，确认 `aapt2 dump badging` 里的 `versionCode`、`versionName` 是本次要发的版本。
2. 先建 Release 并上传附件（脚本）：

   ```powershell
   $env:GITHUB_TOKEN = "<有 repo 权限的 PAT>"
   .\scripts\publish_apk_release.ps1 `
       -Version 1.0.5 `
       -ApkPath "app\build\outputs\apk\debug\app-debug.apk" `
       -Notes "本次改了什么"
   ```

3. 把 `releases/` 里的旧 APK 删掉，换成本次的新 APK，一起提交。

   ```powershell
   git rm releases/LocalGGUFServer-1.0.4-debug-D9300.apk   # 旧版（已在 Release 里留档）
   Copy-Item app\build\outputs\apk\debug\app-debug.apk releases\LocalGGUFServer-1.0.5-debug-D9300.apk
   git add releases
   git commit -m "Release 1.0.5"
   git push
   ```

## 备注

- 仓库 `.gitignore` 已经不再忽略 `*.apk`，但 `app/build/` 仍然被忽略——不要把整个构建目录提交进来。
- 删除 `releases/` 里的旧 APK 不会减小仓库历史体积，只是让它不再随每个版本继续变大。
- Release 附件上限：单个文件 2 GB，足够。
