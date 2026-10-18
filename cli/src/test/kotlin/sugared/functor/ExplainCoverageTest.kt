package sugared.functor

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * P5（维护性测试）：EXPLAIN 诊断码解释表与实际发射的诊断码集合保持**双向一致**——
 * 既不缺实码（用户 `explain <CODE>` 查不到），也无幽灵码（查一个现行不存在的码）。
 * 新增/改名/删除诊断码时，须同步更新本清单与 cli/Main.kt 的 EXPLAIN 表。
 */
class ExplainCoverageTest {

    /** 全库实际发射的诊断码（error / warn / hint / supplement 各分级）——人工维护，与 EXPLAIN 同步。 */
    private val knownCodes = sortedSetOf(
        "D-AUTO-AWAIT", "D-BECAUSE-PROVED", "D-BY", "D-DEEP-COPY",
        "D-EXIST-INTRO", "D-INFIX", "D-LOCAL-FUN", "D-METHOD-SUGAR",
        "D-NAT-PROP", "D-PLUSBECAUSE-PROVED", "D-THEREFORE", "D-TRAILING",
        "D-TYPE-INFERRED",
        "E-AMBIGUOUS-INSTANCE", "E-ANY-CALL", "E-ANY-STRUCT", "E-ARITY", "E-ASSIGN-TARGET",
        "E-BY-UNBOUND", "E-CHANNEL-CLOSED", "E-CHECK", "E-CIRCULAR-MOUNT", "E-DEEP-SATURATION",
        "E-DEFAULT-PARAM", "E-DESTRUCT", "E-DUP-DECL", "E-DUP-FIELD", "E-FIELD-MISSING",
        "E-FUN-NO-BODY", "E-FUTURE-FUN", "E-IF-CONCLUSION-MISMATCH", "E-IMMUT-ASSIGN",
        "E-IMPL-EXTRA", "E-IMPL-INCOMPLETE", "E-IMPL-MISMATCH", "E-IMPURE-CALL",
        "E-IMPURE-REQUIRED", "E-KIND-MISMATCH", "E-LEX", "E-MAIN-PARAMS", "E-MISSING-RETURN",
        "E-NAMED-ARG", "E-NEED-ASYNC", "E-NEED-CLASSIC", "E-NO-INSTANCE", "E-NO-METHOD",
        "E-NO-SELF", "E-NON-PATTERN-HKT", "E-NON-SEALED-MATCH", "E-NON-STRUCTURAL-REC",
        "E-NOT-MUT", "E-NOT-STARTED", "E-PARAM-REF", "E-PARAM-REF-IN-BODY", "E-PARSE",
        "E-PARSE-SETTINGS", "E-POST-UNPROVEN", "E-PRE-UNPROVEN", "E-PROCESSOR-ERROR",
        "E-PROCESSOR-LOOP", "E-PROCESSOR-SCRIPT", "E-PROP-AMBIGUOUS", "E-PROP-UNBOUND",
        "E-PROCESSOR-EDIT",
        "E-QUANT-UNPROVEN",
        "E-RETURN-OUTSIDE", "E-RETURN-TYPE", "E-ROOT-NOT-ALLOWED", "E-STRUCT-MUT-PROP",
        "E-STRUCT-PROP-UNPROVEN", "E-THEOREM-SEARCH-DEPTH", "E-TUPLE-ARITY",
        "E-TUPLE-DESTRUCT", "E-TYPE-MISMATCH", "E-TYPE-MISSING",
        "E-UNBOUND-NAME",
        "H-AMBIGUOUS-CTOR", "H-ARITH-PRECEDENCE", "H-EMPTY-DECL", "H-FIELD-SHADOW",
        "H-PARAM-REF", "H-ROOT-BUILTIN",
        "W-CANNOT-PROVE", "W-DIV-ZERO", "W-NON-EXHAUSTIVE", "W-PROCESSOR-EDIT",
        "W-PROCESSOR-SCRIPT", "W-UNREACHABLE", "W-UNUSED",
    )

    @Test
    fun EXPLAIN与已知码清单双向同步() {
        assertEquals(knownCodes, EXPLAIN.keys.toSortedSet(), "诊断码集合与 EXPLAIN 表不同步：新增/改名诊断码时须同时更新 EXPLAIN 与 knownCodes")
    }
}
