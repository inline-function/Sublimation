# Optional

> wiki/02-类型-Optional · 主题域：Optional[T] / Some / None / map / fold / getOrElse / when 解构
> 事实来源：_langfacts.md §1/§2/§3/§6；core/collection/optional.subl（72 行，全文）；core/collection/typeclass.subl（Functor/Foldable/Monad 声明）；core/collection/result.subl（Ok/Err 形态）；examples/空安全.subl、examples/高阶函数与lambda.subl；Parser.kt（:1174-1182 路径形态、:1409-1412 ifis 组合条件诊断）；Checker.kt（:181-187 prelude 预注册）；同卷 02-类型-枚举.md（模式与穷尽性参照）

Optional 是标准库的空安全容器：一个普通枚举，`Some(v)` 装一个值、`None()` 表示空。修正案后语言层的空安全糖（`?:` 空替代、`?.`、Some→T 隐式解包）已全部移除（core/collection/optional.subl:3-4）——Optional 如今是**普通容器**：取值一律走 when 解构或 `getOrElse`/`orElse` 兜底，链式变换走类型类成员 map/flatMap。

## 语法

### 声明

标准库声明只有两行（core/collection/optional.subl:6-9）：

```subl
enum Optional[T] {
    Some(T)
    None()
}
```

- 类型参数走方括号 `[T]`；`Some` 带一个位置字段（无字段名、无默认值），`None` 零参
- 构造子间逗号可省（与枚举通用文法一致，见同卷枚举篇）
- Checker 的 prelude 早已注册同名枚举（Checker.kt:181-187：`registerEnum(EnumDecl("Optional", …, Ctor("None", []), Ctor("Some", [T])))`），且 `Some` 在 `None` 之前——**即使单文件不引入标准库，`Optional[T]` 类型与 `Some`/`None` 构造子也可直接使用**；map/getOrElse 等函数则属于 core/collection/optional.subl，需标准库在位

### 构造值

```subl
var a : Optional[Nat] = Some(5)              // 位置实参构造调用
var b : Optional[Nat] = None()               // 零参构造子：加空括号
var c : Optional[Nat] = None                 // 零参构造子也可作裸名值引用（多态类型）
var d : Optional[Nat] = Optional::Some(5)    // 路径形态 E::A(x)（Parser.kt:1174-1182）
var e : Optional[Optional[Nat]] = Some(None())
```

`E::A` 折叠为构造子复合名，后随 `(` 仍是普通构造调用；零参裸名引用的 `None : Optional[T]` 对任意 `T` 成立。

### when 解构

解构**必须显式 `is`**（同卷枚举篇：parsePattern 要求 `is Ctor(x, _)` 形态）：

```subl
fun unwrap(o: Optional[Nat]): Nat = when(o) {
    is Some(v) => v               // 解构绑定：v 即 Some 内的值
    is Some(_) => 0               // 通配：只要是 Some，不取值
    is Some(is Some(y)) => y      // 嵌套模式：Optional 套 Optional
    None => 0                     // 无字段构造子：裸名模式
    _ => 0                        // 通配兜底
}
```

分支分隔符是 `=>`（修正案后 `->` 全位非法，程序位一律 `=>`）。此外 `if (o is Some(v)) { A } else { B }` 作为整条件时脱糖为 when（IsPatExpr 智能解构），效果与上面第一支等价。

### 类型类实例与自由函数

标准库为 Optional 实现了三个类型类（optional.subl:11-32），成员签名即类型类抽象签名：

```subl
impl Functor for Optional[a] {
    fun map[A, B](fa: Optional[A], f: (A) => B): Optional[B] = when(fa) {
        is Some(v) => Some(f(v))
        None => None()
    }
}

impl Foldable for Optional[a] {
    fun fold[A, R](fa: Optional[A], init: R, f: (R, A) => R): R = when(fa) {
        is Some(v) => f(init, v)
        None => init
    }
}

impl Monad for Optional[a] {
    fun pure[A](x: A): Optional[A] = Some(x)
    fun flatMap[A, B](fa: Optional[A], f: (A) => Optional[B]): Optional[B] = when(fa) {
        is Some(v) => f(v)
        None => None()
    }
}
```

`impl … for Optional[a]` 的小写 `a` 是隐式类型变量（HKT 形态，typeclass.subl:5-6）；类型类成员有方法糖——`o.map(f)` 与自由式 `map(o, f)` 路由到同一实例（typeclass.subl:4）。

容器工具为同名自由式（optional.subl:36-72）：

| 函数 | 签名 | 所属 |
|---|---|---|
| map / fold / pure / flatMap | 见上 | 类型类成员（可方法调用） |
| isSome | `fun isSome[T](o: Optional[T]): Bool` | 自由式 |
| isNone | `fun isNone[T](o: Optional[T]): Bool` | 自由式 |
| getOrElse | `fun getOrElse[T](o: Optional[T], d: T): T` | 自由式 |
| orElse | `fun orElse[T](o: Optional[T], d: Optional[T]): Optional[T]` | 自由式 |
| filter | `fun filter[T](o: Optional[T], p: (T) => Bool): Optional[T]` | 自由式 |
| toResult | `fun toResult[T, E](o: Optional[T], e: E): Result[T, E]` | 自由式 |
| toList | `fun toList[T](o: Optional[T]): List[T]` | 自由式 |

## 语义

Optional 的全部行为可由「Some 是单元素容器（size = 1），None 是空（size = 0）」这条视角推导（optional.subl:35 注释）：

- **map**（Functor）：保持容器形状、只变换元素——`Some(v) → Some(f(v))`，`None → None`（空值短路，不调用 f）
- **fold**（Foldable）：左结合累积、`init` 是零元、步进函数签名 `(R, A) => R`（累积值**在前**，与 List 的 fold 同序，typeclass.subl:17）。对 Optional 恰好退化成：有值时 `f(init, v)`，空时原样返回 `init`
- **pure**（Monad）：单值注入容器，即 `Some(x)`
- **flatMap**（Monad）：链式展平、**空值短路**——`Some(v)` 才把 v 交给 f，`None` 直接返回 `None()`（typeclass.subl:26：Optional 的 flatMap 即空值短路）
- **isSome / isNone**：判定构造子，`when(o) { is Some(_) => true  None => false }` 与其逆
- **getOrElse**：解包兜底——`None` 回退默认值 `d : T`，返回类型是 **T**（替代已移除的 `?:` 空替代语法）
- **orElse**：空替代——`None` 时切换到另一个 Optional，`Some` 原样返回，返回类型仍是 **Optional[T]**（不改变内容、只改变所在的 Optional）
- **filter**：谓词不成立时降级为 `None()`；`None` 恒为 `None()`（optional.subl:59-62：`if (p(v)) { Some(v) } else { None() }`）
- **toResult**：转错误处理通道——`Some(v) → Ok(v)`、`None → Err(e)`（Result[T, E] 见 core/collection/result.subl）
- **toList**：转列表——`Some(v) → Cons(v, Nil())`、`None → Nil()`

穷尽性（与枚举通用规则一致）：模式实参全为裸绑定/通配且无守卫时，`is Some(v)` 在值空间吸收 Some 的全部值，配 `None` 即穷尽——optional.subl 里所有 when 都只有这两支、没有 `else`；带字面量的 `is Some(0)` 则保守判非穷尽，因为它不覆盖所有 Some。

可变性：Optional 是不可变容器（元素类型 `T` 默认不可变，langfacts §3）。把 Optional 值赋给 `@mut` 目标时按通用拷贝规则深拷（`@mut T ← T` 深拷，langfacts §6）；Optional 自身不带来额外的所有权语义。

## 示例

取值与兜底（自包含形态，Optional 类型与构造子由 prelude 提供）：

```subl
// None 兜底：getOrElse 的自带等价物（examples/空安全.subl 的 elvis 即此形）
fun orZero(o: Optional[Nat]): Nat = when(o) {
    is Some(v) => v
    None => 0
}

@impure
fun main() {
    var a : Optional[Nat] = Some(5)
    var b : Optional[Nat] = None()
    unchecked print(orZero(a))              // 5
    unchecked print(orZero(b))              // 0
    unchecked print(a == Some(5))           // true（== 是结构相等：structEquals 递归按字段比较，Evaluator.kt:401-413）
}
```

标准库在位（工程/目录模式，core/collection/optional.subl 可见）时，上面的 when 可以直接换成库函数：

```subl
fun orZero(o: Optional[Nat]): Nat = getOrElse(o, 0)

fun doble(o: Optional[Nat]): Optional[Nat] = o.map({ x => x + x })

// flatMap 链式：任一步 None 即整体短路
fun total(a: Optional[Nat], b: Optional[Nat]): Optional[Nat] =
    a.flatMap({ x => b.flatMap({ y => Some(x + y) }) })

// fold：累积值在前（(R, A) => R）
fun count(o: Optional[Nat]): Nat = fold(o, 0, { acc, v => acc + 1 })

// filter + orElse：筛选失败时回退备选
fun positive(n: Nat): Optional[Nat] = if (n > 0) { Some(n) } else { None() }

fun safeQty(n: Nat): Optional[Nat] = orElse(filter(positive(n), { x => x < 100 }), Some(1))
```

lambda 写法：显式参数 `{ x => x + x }`、隐式单参 `{ _ * 2 }`（体里 `_` 引用形参）、多参 `{ acc, v => acc + 1 }`（examples/高阶函数与lambda.subl:24-28）。

与错误处理/列表互转（标准库在位时）：

```subl
fun loadOrError(o: Optional[Nat]): Result[Nat, Str] = toResult(o, "缺失")

fun firstOrEmpty(o: Optional[Nat]): List[Nat] = toList(o)
```

if 智能解构（单支取值时比 when 更短）：

```subl
fun describe(o: Optional[Nat]): Str =
    if (o is Some(v)) { toStr(v) } else { "空" }
```

## 陷阱

1. **`->` 全位非法**：when 分支写成 `is Some(v) -> v` 会被 lexer 的 ARROW 诊断拦截（修正案后 `->` 仅供诊断用）。程序位——when 分支、lambda、函数类型——一律 `=>`；命题蕴含才用 Unicode `→`。
2. **空安全糖已从语言层移除**：`?.`/`?:` 糖与 Some→T 隐式解包都不存在（optional.subl:3-4 的移除声明）。空值处理只有两条路：when 解构（`is Some(v)`），或库函数兜底（`getOrElse` 解包 / `orElse` 保持容器）。
3. **解构漏写 `is`**：`when(o) { Some(v) => v }` 不是解构模式——无 `is` 的 `A()` 仅允许**空参**值比较，带实参的 `Some(v)` 会被解析报错挡住（parsePattern :1644-1648 的诊断）。正确写法是 `is Some(v)`。
4. **组合条件里不能用智能解构**：`if (o is Some(v) && v > 0)` 触发诊断「ifis 智能解构条件须单独为 `表达式 is 构造模式`」（Parser.kt:1409-1412）。模式绑定无法进入组合作用域——请改用 when 表达式或先解构再判断。
5. **`is Some(0)` 不穷尽**：带字面量的模式保守判非穷尽（不覆盖所有 Some）；深度模式同理要补齐内层分支。放心写 `is Some(v)` + `None`——全裸绑定/通配无守卫时这两支即穷尽，不需要 `else`。
6. **`None` 在模式位是构造子名而非新绑定**：`None => …` 的分支里，`None` 是构造子裸名模式而非新变量；分支体里作值用时 `None()` 与裸名 `None` 都可以（零参构造子可作裸名值引用）。别在 `is Some(_)` 分支体里直接返回未解包的容器——那是 Optional，不是 T。
7. **fold 的参数顺序**：步进函数是 `(R, A) => R`（累积在前、元素在后），Optional 的实现是 `f(init, v)`（optional.subl:19-22）。写成 `(v, acc)` 会类型不符；这与某些语言的 `fold(item, acc)` 顺序相反。
8. **getOrElse 与 orElse 只差一个字母、类型差一层**：`getOrElse(o, d : T) : T` 解包；`orElse(o, d : Optional[T]) : Optional[T]` 保持容器。混用会导致返回类型多套/少套一层 Optional。
9. **方法糖只覆盖类型类成员**：`o.map(f)`/`o.flatMap(g)` 可（Functor/Monad 成员有方法式路由，typeclass.subl:4）；`o.getOrElse(0)` 这类**自由式**调用是否同样支持接收者写法 待核——稳妥写自由式 `getOrElse(o, 0)`。同理 filter/isSome/toResult 都按自由式调用。
10. **map 不对空值兜底**：`None.map(f)` 直接返回 `None()`，f 从不执行；把「f 里做默认值」的意图写进 map 是错的——默认值属于 getOrElse/orElse 的职责。
11. **给 Optional 叙述定理时不要用旧 axiom 形态**：已废除的 `unchecked.axiom[p]` 会报错引导；独立 `axiom p1, p2, …` 语句才是当前文法（修正案后）。Optional 相关命题同样走这条路径。
12. **标准库函数需要标准库在位**：`Optional`/`Some`/`None` 由 Checker prelude 预注册（:181-187），单文件可用；但 map/fold/getOrElse 等定义在 core/collection/optional.subl——单文件模式下可自行定义等价的 when 函数（examples/空安全.subl 即是此法），或确认标准库已挂载。
