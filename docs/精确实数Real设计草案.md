# 精确实数 `Real` 设计草案（修订版）

> 定位：`Real` 是凝华数值链顶端的**精确实数类型**，支持精确分数、精确无理数（√）、精确超越数（π、e），以及它们的任意组合。
>
> 本文档是草案，AI 起草，待审定。

---

## 1. 定位与目标

**`Real` 是什么：**

一个**精确符号数值类型**。它的值用表达式树表示，运算在符号层进行，能化简就化简，化不动就保留。它不自动转浮点，不丢失精度——像 Java 的 `BigDecimal`，但 `BigDecimal` 只能装十进制有限小数，`Real` 能装分数、根号、π、e。

**`Real` 不是什么：**

- 不是浮点数的包装
- 不是类型类——它是具体类型
- 不是完整 CAS——不做积分、极限、级数、三角化简
- 不是完备数值域——有些命题不可判定，证不出就证不出

**数值链：**

```
Nat ⊆ Int ⊆ Rat ⊆ Real
```

`Real` 是链顶。`Nat`、`Int`、`Rat` 通过**隐式 `toReal()`** 提升到 `Real`。

---

## 2. `Rat` 与 `Real` 的关系（关键修订）

### 2.1 `Rat` 是浮点数

**`Rat` 的本质是浮点数**——名字叫"有理数"，实现是浮点。它**会损失精度**：

- `1/3` 在 `Rat` 里是 `0.333...`（有限位截断）
- `0.1 + 0.2` 在 `Rat` 里不等于 `0.3`
- 大数运算会溢出或丢有效位

这不是缺陷，是设计选择——`Rat` 是日常数值计算的类型，快、够用。

### 2.2 `Real` 是精确实数

**`Real` 的本质是精确符号值**。它不损失精度：

- `1/3` 在 `Real` 里就是分数 `1/3`
- `1/3 + 1/6` 精确等于 `1/2`
- `√2 · √2` 精确等于 `2`
- 运算像 `BigDecimal` 一样不损失精度，但 `BigDecimal` 装不下 `√2` 和 `π`——`Real` 能

### 2.3 转换规则

| 方向 | 语法 | 语义 | 损失 |
|------|------|------|------|
| `Rat → Real` | **隐式** `toReal()` | 把浮点值转成精确实数（浮点值本身就是精确的二进制分数） | **无损** |
| `Real → Rat` | **显式** `toRat()` | 展开计算，把符号表达式求值为浮点 | **损失精度** |

**`Real → Rat` 的损失语义：**

```
toRat(1/3)        → 0.333...        // 有限位截断
toRat(√2)         → 1.4142...       // 数值近似
toRat(π)          → 3.14159...      // 数值近似
toRat(√2^√2)      → 失败或近似       // 黑盒节点，见 §7
```

`toRat()` 是**显式降级**——用户主动要求"我接受精度损失"。编译器不自动做。

**注意**：`Rat → Real` 是"无损"的，因为浮点值 `0.1` 本身就是某个精确的二进制分数（`3602879701896397 / 2^55`），`toReal()` 把它原样搬进 `Real` 的分数表示，不丢任何信息。**丢精度发生在 `Real → Rat` 的方向。**

---

## 3. 内部表示

```kotlin
sealed interface Real {
    data class RExact(val num: BigInteger, val den: BigInteger) : Real
    data class RConst(val name: ConstName) : Real
    data class RSqrt(val radicand: Real) : Real
    data class ROp(val op: OpKind, val args: List<Real>) : Real
}

enum class ConstName { PI, E }

enum class OpKind { ADD, MUL, POW, NEG }
```

### 3.1 节点语义

| 节点 | 含义 | 例子 |
|------|------|------|
| `RExact(p, q)` | 精确有理数 `p/q`，约定 `q > 0`，`gcd(p,q)=1` | `1/3`、`-5/2` |
| `RConst(PI)` | 圆周率 | `π` |
| `RConst(E)` | 自然常数 | `e` |
| `RSqrt(x)` | 平方根 `√x` | `√2`、`√(1+√2)` |
| `ROp(ADD, [a,b,...])` | 加法，n 元 | `a+b+c` |
| `ROp(MUL, [a,b,...])` | 乘法，n 元 | `a·b·c` |
| `ROp(POW, [a,b])` | 幂，二元 | `a^b` |
| `ROp(NEG, [a])` | 取负，一元 | `-a` |

### 3.2 为什么这样选

**`RExact` 用分数不用十进制**：十进制表示不了 `1/3`。分数能表示所有有理数，加乘除后仍是有理数，且能用 gcd 约分到唯一规范形。

**`BigInteger` 而非 `Int`**：`1/3 + 1/5` 的分母会增长。`Int` 溢出会丢精度，违背"精确"目标。

**`√` 独立节点而非 `Pow(x, 1/2)`**：`√` 有专属规则（提取平方因子、平方消去、根号合并），独立节点让规则精确、好写。

**`POW` 通用节点**：`π²`、`π^e`、`√2^√2` 都用它。

**`ADD`/`MUL` 是 n 元而非二元**：结合律让 `a+b+c` 不必嵌套成人 `(a+b)+c`。展平后排序，同类项合并更自然。

**`NEG` 独立而非 `Mul(-1, x)`**：`-√2` 比 `(-1)·√2` 更贴近数学直觉。

---

## 4. 规范形

> `Real` 的规范形 = 在规则集 R 下跑到不动点的表达式。两个表达式相等，当且仅当它们的规范形结构相等。

### 4.1 规范化函数

```
normalize(r : Real) : Real =
    loop:
        r' = applyRules(r)
        if r' == r: return r
        r = r'
```

**关键：规则集必须合流（confluent）。**

### 4.2 结构相等

```
structuralEqual(a, b) : Bool =
    when (a, b):
        (RExact(p1,q1), RExact(p2,q2)) → p1==p2 && q1==q2
        (RConst(n1),   RConst(n2))     → n1==n2
        (RSqrt(x),     RSqrt(y))       → structuralEqual(x, y)
        (ROp(op1,as1), ROp(op2,as2))   → op1==op2 && zip(as1,as2).all(structuralEqual)
        else → false
```

---

## 5. 重写规则集

### 5.1 基础简化

```
R1.  ROp(ADD, [x])            → x
R2.  ROp(MUL, [x])            → x
R3.  ROp(ADD, [..., 0, ...])  → 删除 0 项
R4.  ROp(MUL, [..., 1, ...])  → 删除 1 项
R5.  ROp(MUL, [..., 0, ...])  → 0
R6.  ROp(NEG, [RExact(p,q)])  → RExact(-p, q)
R7.  ROp(NEG, [ROp(NEG,[x])]) → x
```

### 5.2 有理数运算

```
R8.  ROp(ADD, [RExact, RExact, ...])  → 合并为一个 RExact
R9.  ROp(MUL, [RExact, RExact, ...])  → 合并为一个 RExact
R10. ROp(POW, [RExact(p,q), RExact(m,1)])  → RExact(p^m, q^m)
```

### 5.3 加法合并

```
R11. ROp(ADD, [a, b, ...])  → 按规范序排列
R12. 同类项合并：k·x + m·x → (k+m)·x
R13. x + NEG(x) → 删除两者
```

### 5.4 乘法合并

```
R14. ROp(MUL, [a, b, ...])  → 按规范序排列
R15. 同底幂合并：x^a · x^b → x^(a+b)
R17. 有理数系数提到最前
```

### 5.5 根号与幂

```
R18. RSqrt(RExact(p,q))  → 提取平方因子
R19. RSqrt(x²)           → x (x ≥ 0)
R20. RSqrt(a)·RSqrt(b)   → RSqrt(a·b)
R21. RSqrt(x)·RSqrt(x)   → x
R22. x^0                 → 1
R23. x^1                 → x
R24. 1^x                 → 1
R25. (x^a)^b             → x^(a·b)
R26. x^(-n)              → 保留
```

---

## 6. 关键规则的数学前提

**`Real` 是正实数域**（`Nat ⊆ Int ⊆ Rat ⊆ Real`，`Real` 不含负数），所以 R19、R25 无条件安全。

---

## 7. 黑盒节点

`√2^√2`、`π^e` 这类无法化简的表达式原样保留，当原子处理：能判自反相等、能参与排序、能参与同类项合并，但不能比较大小、不能 `toRat`。

---

## 8. 与上下文系统的集成

### 8.1 等式判定流程

```
proveEq(a : Real, b : Real) : ProofResult =
    na = normalize(a)
    nb = normalize(b)
    if structuralEqual(na, nb): return PROVEN
    else: return UNPROVEN
```

### 8.2 证明通道 11：Real 代数重写

触发条件：目标等式的任一侧类型涉及 `Real`
处理：两边 normalize 到不动点 → structuralEqual → 证得/证不出

---

## 9. 落地路线

### 第一波：有理数 + √
- `RExact`、`RSqrt`、`ROp(ADD/MUL/NEG)`
- 规则 R1–R21 / 证明通道 11 / `toReal()` / `toRat()`
- 覆盖：`√2·√2=2`、`1/3+1/6=1/2`、`√8=2√2`

### 第二波：π、e + 幂
- `RConst(PI/E)`、`ROp(POW)` / 规则 R22–R26
- 覆盖：`2π+3π=5π`、`π+e=e+π`

### 第三波：黑盒节点 + 数值

---

## 10. 错误码

| 码 | 场景 | 等级 |
|----|------|------|
| `E-REAL-DIV-ZERO` | 构造 `RExact(p, 0)` | 错误 |
| `E-REAL-CANNOT-PROVE` | `Real` 等式证不出 | 错误 |
| `E-REAL-NO-NUMERIC` | `toRat` 遇黑盒节点 | 错误 |
| `W-REAL-HUGE` | 分子/分母超过阈值 | 警告 |

---

## 11. 一句话总结

> **`Real` 是精确符号数值类型 = 有理数（分数）+ 根号 + π/e + 运算树。核心是合流、终止的重写系统，证明等式靠两边 normalize 后结构比较。**