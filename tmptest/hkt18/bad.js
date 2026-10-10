"use strict";
(() => {
  const None = () => ({ tag: "None" });
  const Some = (a0) => ({ tag: "Some", 0: a0 });
  const __isa = (v, t) => {
    if (t === "Nat" || t === "Int" || t === "Rat") return typeof v === "number";
    if (t === "Str") return typeof v === "string";
    if (t === "Bool") return typeof v === "boolean";
    if (t === "Null") return v === null || (v && v.tag === "Null");
    if (t === "(fn)") return typeof v === "function";
    return v !== null && typeof v === "object" && v.tag === t;
  };
  async function main() {
    return null;
  }
  main();
})();
