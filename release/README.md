# 发布产物

## sublimation.jar
自包含命令行工具链（Java 17+），含 frontend / compiler / interpreter / language-service / annotationproc 全模块。

用法示例：
```
java -jar sublimation.jar compile <工程路径> -o <输出>
java -jar sublimation.jar check <工程路径>
java -jar sublimation.jar -A<key>=<value> ...   # 注解处理器参数（@postcompile 打包链）
java -jar sublimation.jar --target=node|browser|wasm ...
```

构建：`./gradlew :cli:fatJar`（产物 build/libs/sublimation.jar）。
