# Interview Agent

本仓库同时保留两个独立实现：

- [`java-version/`](java-version/)：原 Java / Spring Boot 版本，包含原方案书、源码、测试、数据与部署资料。
- [`python-version/`](python-version/)：Python / FastAPI 重写版本，使用独立的源码、测试、资源和数据目录。

两个版本互不共用运行数据。请进入对应目录后再执行该版本 README 中的启动、测试和部署命令。

```bat
cd java-version
powershell -File scripts\run-tests.ps1
```

```bat
cd python-version
.venv\Scripts\python.exe -m pytest -q
```
