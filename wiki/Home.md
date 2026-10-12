# Sublimation 快速上手

Sublimation 是一门带**伴生命题**的静态语言：Kotlin 风格语法 + 类型类/扩展方法 + 上下文自动证明，编译到 JavaScript。

## 编译与运行

```bash
# 构建编译器（一次）
gradlew.bat fatJar

# 编译一个项目目录（目录即模块；子模块用 settings mount 挂载）
java -jar build/libs/sublimation.jar compile MY_PROJECT -o out.js

# 运行输出
node out.js
```

## 语法与基础特性速览

### Hello World

```subl
@impure fun main() {
    print("Hello, Sublimation!")
}
```

### 变量与类型

```subl
var n = 42          // Nat（自然数）
var x: Int = -3     // Int
var s = "hi"        // Str
var b = true        // Bool
```
基础类型：`Nat` `Int` `Rat` `Str` `Bool` `Null`；容器 `List[T]` `Set[T]` `Map[K,V]` `Optional[T]` `Result[T,E]`、元组 `(A,B)`。

### 函数

```subl
fun add(a: Nat, b: Nat): Nat = a + b      // 表达式体
fun double(x: Nat): Nat { return x * 2 }  // 块体
```
- 函数默认**纯**；有副作用标 `@impure`；就地改参数标 `@mut`。
- 高阶函数与 lambda：

```subl
fun applyTwice(f: (Nat) => Nat, x: Nat): Nat = f(f(x))
var r = applyTwice({ v -> v + 1 }, 5)      // 7
```

### 空安全

没有 `null` 裸奔：可空值用 `Optional[T]`（`Some(x)` / `None`）。

```subl
var maybe: Optional[Nat] = Some(3)
when (maybe) {
    Some(v) -> print(v + 1)
    None    -> print("nothing")
}
var y = maybe?.field            // 安全调用：None 时短路
```

### 模式匹配（when / 条件）

```subl
when (n) {
    0        -> print("zero")
    x if x > 10 -> print("big")
    else     -> print("other")
}

// 区间分支：编译器证明穷尽
+∵ n ∈ (0..30)
when(n in _) {
    (0 .. 10)  -> print(1)
    (11 .. 20) -> print(2)
    (21 .. 30) -> print(3)
}
```

### 伴生命题与手动证明

```subl
+∵ n ∈ (0..30)                 // 注入事实
∴ n ≥ 0                        // 求证：由 n ∈ (0..30) 与定理推出
var r: Nat = if (n > 0) { n } else { 0 }
```
- `∵` 从当前事实推导；`+∵` 无条件注入事实；`∴ q` 求证断言；`if[p]` 命题守卫分支。
- 同一结构自动自反：`1 + 2 = 1 + 2` 可证；`1 + 2 = 3` 需要手动证明。
- 数学记号：`∈`（在…中）、`≥` `≤`、`∀` `∃`。

### 类型类与扩展方法

```subl
class Collection {
    fun size(): Nat
    fun isEmpty(): Bool
}
impl Collection for List[a] {
    fun size(): Nat = when (self) { Nil -> 0; Cons(_, t) -> 1 + t.size() }
    fun isEmpty(): Bool = self.size() == 0
}

impl List[a] {             // 扩展方法：没有实现类型类的 impl
    fun last(): Optional[a] = when (self) { Nil -> None(); Cons(h, Nil) -> Some(h); Cons(_, t) -> t.last() }
}
var xs = Cons(1, Cons(2, Nil()))
print(xs.size())           // 2（方法糖优先自由函数，再类型类字典）
```
- ```impl X {}``` 的成员脱糖为带 `self` 首参的自由函数，是同名多形态（重载）的推荐写法。

### 容器与函数式 API

所有容器实现 `Collection`：`size` `isEmpty` `contains`；并有 `map` `filter` `flatMap` `forEach` `fold` `any` `all` 等函数式 API。

```subl
var ys = xs.map({ v -> v * 2 }).filter({ v -> v > 2 })
print(ys.size())
```

### 模块系统

- **目录即模块**：根目录 = 编译入口；子目录 = 子模块。
- settings 文件：`<模块名>.settings`，示例 `mount core/math as math`。
- std（stdlib/）隐式挂载，裸名可直接调用（`size(xs)`、`Cons(...)`）。

## 下一步

- 特性总览见 wiki 侧栏各页；
- 设计哲学与术语表见 `doc/`；
- 形式化规范见 `doc/形式化规范.md`。
