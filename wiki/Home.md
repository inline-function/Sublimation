# Sublimation 快速上手

**Sublimation（凝华）**是一门带自动证明的静态类型语言：类型里的命题可以交给编译器自动证明，证明通过才给编译，失败即编译错误。语法像 Kotlin，编译到 JavaScript，node 直接跑。

## 安装

```bat
gradlew.bat fatJar                              # 编译出 build/libs/sublimation.jar
java -jar build/libs/sublimation.jar compile MY_PROJECT -o out.js
node out.js
```

## Hello World

```subl
@impure                            // main 有副作用，标出来
fun main() {
    print("Hello, Sublimation!")
}
```

## 变量与基本类型

```subl
var n = 42                          // Nat（自然数，n≥0）
var x: Int = -3                     // Int（整数）
var s = "hello"                     // Str
var b = true                        // Bool
```

基本类型：`Nat` `Int` `Rat` `Str` `Bool` `Null`，以及容器 `List[T]` `Set[T]` `Map[K,V]` `Optional[T]` `Result[T,E]`、元组 `(A,B)`。

## 函数

```subl
fun add(a: Nat, b: Nat): Nat = a + b            // 纯函数（默认，可以写表达式体）
fun greet(name: Str): Str {                       // 块体
    return "Hello, " + name
}
```

## 自动证明（上下文系统）

Sublimation 的与众不同之处：函数可以**携带命题**，编译器帮你自动证明。

```subl
fun capped(score: Nat)<score <= 100>: Nat = score   // <score<=100> 是"上文"：调用前必须满足
```

调用 `capped(50)` 自动证明 `50 <= 100`（常量折叠）——通过。
调用 `capped(200)` 证明 `200 <= 100` 失败 → **编译错误**。

### 因为/所以/又因为

```subl
+∵ 3 >= 0                // "又因为"：从上下文推出，验证后注入 Γ
∴ 3 >= 0                 // "所以"：求证，证不出即编译错误
```

### 区间穷尽检查

```subl
fun grade(score: Nat)<score ∈ (0..30)>: Str {
    when(score in _) {          // 区间模式匹配——编译器证明三个分支覆盖了 (0..30)
        (0 .. 10)  -> return "低"
        (11 .. 20) -> return "中"
        (21 .. 30) -> return "高"
    }
}
```

## 模式匹配（when）

```subl
when (x) {
    0       -> print("零")
    x if x > 10 -> print("大")
    else    -> print("其他")
}

// 无主题条件 when（没有 subject）：
when {
    n < 0  -> print("负")
    n == 0 -> print("零")
    else   -> print("正")
}
```

## 空安全

没有 `null` 裸奔——可空用 `Optional[T]`：

```subl
var m: Optional[Nat] = Some(42)
var v = when (m) { Some(x) -> x; None -> 0 }   // 解构取值
var y = maybe?.field                             // 安全调用：None 时短路
```

## 结构体与类型类

```subl
struct User(name: Str, age: Nat)

class Show {
    fun show(): Str
}
impl Show for User {
    fun show(): Str = "{ name=" + self.name + ", age=" + self.age + " }"
}
var u = User("Alice", 25)
print(u.show())                          // { name=Alice, age=25 }
```

## 纯度控制

函数默认**纯**（无副作用）。有副作用标 `@impure`；就地改参数标 `@mut`：

```subl
fun pure(x: Nat): Nat = x + 1           // 默认纯

@impure
fun log(msg: Str) { print(msg) }        // 副作用

@mut var counter = 0
counter = counter + 1                      // 可变变量需标 @mut
```

## 容器

```subl
var xs = listOf(1, 2, 3)
xs.map({ v -> v + 1 })          // [2, 3, 4]
xs.filter({ v -> v > 1 })       // [2, 3]
xs.fold(0, { v, a -> a + v })   // 6

var m = mapEmpty[Str, Nat]()
m = m.put("a", 1).put("b", 2)           // 不可变：put 返回新 Map
m.get("a")                              // Some(1)
```

## 目录即模块

每个目录 = 一个模块，`stdlib/` 自动挂载（不需 `mount`），跨模块自动解析：

```
MY_PROJECT/
  main.subl          ← 根模块
  stdlib/            ← 自动挂载（logic.subl + classic.subl + collection.subl）
  MY_PROJECT.settings ← mount 其他模块
```

## 下一步

→ [语言样式规范](语言样式规范) — 写出可读的 Sublimation 代码
→ [上下文系统](上下文系统) — 自动证明深入
→ [模式匹配](模式匹配) — when、when-in、穷尽性
→ [结构体与类型类](结构体与类型类) — struct / class / impl
→ [副作用与可变性控制](副作用与可变性控制) — 纯度纪律
→ [空安全](空安全) — Optional 使用指南
→ [容器](容器) — List / Set / Map / Optional / Result
→ [异步](异步) — 协程与 Channel
