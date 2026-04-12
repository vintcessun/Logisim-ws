# Logisim WebSocket

Logisim 是一款数字电路模拟器，最初由 [CBurch 开发](https://www.cburch.com/logisim/)。

本项目是 [Logisim-Ita/Logisim](https://github.com/Logisim-Ita/Logisim) 的一个分支。

## 项目目标
本项目的目标是提供一个 **WebSocket API** 用于远程操作 Logisim 以及实现 Logisim 电路图的**无头（Headless）渲染**。

## 编译与使用
本项目使用 **Java 21** 和 **Gradle** 进行编译构建。

在 `Logisim-Fork` 目录下运行以下命令：
```bash
./gradlew shadowJar
```
生成的输出文件（包含所有依赖的 Jar 包）位于 `build/libs/Logisim-all.jar`。

## API文档

关于无头仿真 WebSocket API 的详细文档与操作指南，请参阅：
[Logisim Headless API 操作指南](Logisim-Fork/logisim-headless-api/README.md)
