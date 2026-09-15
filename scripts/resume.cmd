@echo off
rem ===========================================================================
rem  会话恢复脚本：晚上继续开发时先跑这个，确认环境与基线都正常。
rem
rem  用法： scripts\resume.cmd
rem
rem  它会依次做四件事：
rem    1) 打印 git 状态与最近提交（确认上一轮修复都落盘了）
rem    2) 重新编译并跑全部自动化测试（确认基线是绿的）
rem    3) 检测本地模型服务是否可用（Ollama 11434 / LM Studio 1234）
rem    4) 打印下一步待办清单
rem
rem  注意：自动化测试为离线运行，不依赖模型服务；只有真实模型回归才需要它。
rem ===========================================================================
setlocal
set "PROJECT_ROOT=%~dp0.."
pushd "%PROJECT_ROOT%"

echo ============================================================
echo  1/4  git 状态
echo ============================================================
git log --oneline -4
echo.
echo 未提交改动（应为空，除外部工具产物外）:
git status --short
echo.

echo ============================================================
echo  2/4  运行自动化测试
echo ============================================================
powershell -NoProfile -ExecutionPolicy Bypass -File "%PROJECT_ROOT%\scripts\run-tests.ps1"
set "TEST_RC=%ERRORLEVEL%"
echo.

echo ============================================================
echo  3/4  本地模型服务检测
echo ============================================================
powershell -NoProfile -Command "$o = Test-NetConnection -ComputerName 127.0.0.1 -Port 11434 -InformationLevel Quiet -WarningAction SilentlyContinue; $l = Test-NetConnection -ComputerName 127.0.0.1 -Port 1234 -InformationLevel Quiet -WarningAction SilentlyContinue; Write-Host ('  Ollama  11434 : ' + $o); Write-Host ('  LM Studio 1234 : ' + $l); if (-not $o -and -not $l) { Write-Host '  [提示] 未检测到模型服务。真实模型回归前请先启动：' ; Write-Host '         start D:\Ollama\ollama.exe serve   (需先设置 OLLAMA_MODELS=D:\Ollama_models)' ; Write-Host '         或启动 LM Studio 并在 Developer 页开启服务' ; Write-Host '         仅跑自动化测试/演示可忽略，使用 dist\app.cmd --app.llm.provider=mock' }"
echo.

echo ============================================================
echo  4/4  下一步待办
echo ============================================================
echo  [ ] 清理 /api/diagnostics/config 里的临时探针字段
echo      （保留 llmBaseUrl / thinkingDisabled，删掉 bound.probeError / contextCount / propertySources）
echo  [ ] 把真实模型实测指标写入 docs\VERIFICATION.md，替换「待测」表述
echo  [ ] 优化评分一致性（同一回答重复评分当前标准差 0.3~0.7，偏大）
echo  [ ] 提交并（联网时）执行 scripts\publish-github.cmd 推送到 GitHub
echo.

if not "%TEST_RC%"=="0" (
  echo [警告] 自动化测试未通过（exit=%TEST_RC%），请先修复再继续。
  popd
  exit /b %TEST_RC%
)
echo 环境就绪，基线测试通过。
popd
exit /b 0
