package sugared.functor.subs

import org.junit.jupiter.api.Test

class ScratchTest {

    private val out = ArrayList<String>()

    @Test
    fun probeJson() {
        val s = SubsInterpreter(out::add)
        try {
            val v = s.eval("""
                when (jsonGet(jsonParse("{\"score\": 42}"), "score")) {
                  is Some(v) => v
                  is None => 0
                }
            """.trimIndent())
            println("[json] => " + dshStr(v ?: V.Null))
        } catch (e: Throwable) { println("[json] !! " + e.message) }
    }

    @Test
    fun probeGuard() {
        val s = SubsInterpreter(out::add)
        val cases = listOf(
            "when (42) { n if n % 2 == 0 => \"even\" else => \"odd\" }" to "裸名守卫",
            "when (5) { n if n > 10 => \"big\" 5 => \"five\" else => \"other\" }" to "字面量守卫",
        )
        for ((src, label) in cases) {
            try {
                val v = s.eval(src)
                println("[$label] => " + dshStr(v ?: V.Null))
            } catch (e: Throwable) {
                println("[$label] !! " + e.javaClass.simpleName + ": " + e.message)
            }
        }
    }

    @Test
    fun probeFinal() {
        val s = SubsInterpreter(out::add)
        val probes = listOf(
            "size(Cons(1, Cons(2, Nil())))" to "size",
            "reverse(Cons(1, Cons(2, Cons(3, Nil()))))" to "reverse",
            "Cons(1, Cons(2, Cons(3, Nil()))).map({ x => x * 2 })" to "map",
            "Cons(1, Cons(2, Cons(3, Nil()))).fold(0, { acc, x => acc + x })" to "fold",
            "when (Cons(7, Nil())) { is Cons(h, t) => h }" to "when-h",
        )
        for ((src, label) in probes) {
            try { println("[$label] => " + dshStr(s.eval(src) ?: V.Null)) }
            catch (e: Throwable) { println("[$label] !! " + e.message) }
        }
    }

    @Test
    fun probeCrossVal() {
        val src = java.io.File("examples/日常-数列与区间.subl").readText()
        val s = SubsInterpreter(out::add)
        try { s.run(src) } catch (e: Throwable) { println("[xval] !! " + e.javaClass.simpleName + ": " + e.message) }
        println("[xval] out=" + out.joinToString("|"))
    }

    @Test
    fun probeFactRange() {
        val s = SubsInterpreter(out::add)
        try {
            s.eval("""
                fun fact(n: Nat): Nat {
                    if (n < 2) { return 1 }
                    unchecked var r = fact(n - 1)
                    return n * r
                }
            """.trimIndent())
            println("[fact5] => " + dshStr(s.callFunction("fact", V.Num(5.0)) ?: V.Null))
        } catch (e: Throwable) { println("[fact5] !! " + e.javaClass.simpleName + ": " + e.message) }
        try {
            s.eval("""
                fun range(a: Nat, b: Nat): List[Nat] {
                    if (a < b) {
                        unchecked var rest = range(a + 1, b)
                        return Cons(a, rest)
                    }
                    return Nil()
                }
            """.trimIndent())
            println("[range16] => " + dshStr(s.callFunction("range", V.Num(1.0), V.Num(6.0)) ?: V.Null))
        } catch (e: Throwable) { println("[range16] !! " + e.javaClass.simpleName + ": " + e.message) }
        // 对照：无 unchecked 的同构写法
        try {
            s.eval("""
                fun fact2(n: Nat): Nat {
                    if (n < 2) { return 1 }
                    var r = fact2(n - 1)
                    return n * r
                }
            """.trimIndent())
            println("[fact2-5] => " + dshStr(s.callFunction("fact2", V.Num(5.0)) ?: V.Null))
        } catch (e: Throwable) { println("[fact2-5] !! " + e.javaClass.simpleName + ": " + e.message) }
        try {
            s.eval("""
                fun sumTo(n: Nat): Nat {
                    if (n == 0) { return 0 }
                    unchecked var r = sumTo(n - 1)
                    return n + r
                }
            """.trimIndent())
            println("[sumTo5] => " + dshStr(s.callFunction("sumTo", V.Num(5.0)) ?: V.Null))
        } catch (e: Throwable) { println("[sumTo5] !! " + e.javaClass.simpleName + ": " + e.message) }
    }

    @Test
    fun probeIsolate() {
        val s = SubsInterpreter(out::add)
        for ((src, label, arg) in listOf(
            Triple("fun g1(n: Nat): Nat { var r = n\nreturn n * r }", "g1", 5.0),
            Triple("fun g2(n: Nat): Nat { if (n < 2) { return 1 }\nreturn n * 2 }", "g2", 5.0),
            Triple("fun g3(n: Nat): Nat { if (n < 2) { return 1 }\nvar r = g3(n - 1)\nreturn n * r }", "g3", 5.0),
            Triple("fun g4(n: Nat): Nat { if (n < 2) { return 1 }\nreturn n * g4(n - 1) }", "g4", 5.0),
        )) {
            try {
                s.eval(src)
                println("[$label] => " + dshStr(s.callFunction(label, V.Num(arg)) ?: V.Null))
            } catch (e: Throwable) { println("[$label] !! " + e.javaClass.simpleName + ": " + e.message) }
        }
    }

    @Test
    fun probeG5() {
        val local = ArrayList<String>()
        val s = SubsInterpreter(local::add)
        try {
            s.eval("fun g5(n: Nat): Nat { if (n < 2) { return 1 } var r = g5(n - 1) print(r) print(n) return n * r }")
            println("[g5] => " + dshStr(s.callFunction("g5", V.Num(5.0)) ?: V.Null))
            println("[g5] trace=" + local.joinToString("|"))
        } catch (e: Throwable) { println("[g5] !! " + e.javaClass.simpleName + ": " + e.message) }
    }
}