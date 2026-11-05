package com.lanshare.app.core

// Geri dönüşüm kutusu yol çözümleyici testi (Bin.resolve). JVM'de çalışır: kotlinc BinSelfTest.kt Bin.kt (+ Util.kt, stub'lar) ile derlenir.
fun main() {
    var bad = 0
    fun eq(n: String, a: String, b: String) { if (a == b) println("ok   $n") else { bad++; println("FAIL $n: '$a' != '$b'") } }
    val d = Bin.Entry("id01aaaa", "foto.jpg", false, 5, "/DCIM/foto.jpg", 1).also { it.shown = "foto (aaaa).jpg" }
    val d2 = Bin.Entry("id02bbbb", "foto.jpg", false, 5, "/Pics/foto.jpg", 2).also { it.shown = "foto (bbbb).jpg" }
    val f = Bin.Entry("id03cccc", "Klasör", true, 0, "/Klasör", 3)
    val l = listOf(d, d2, f)
    val N = Bin.NAME
    eq("dup tam ad", Bin.resolve("/$N/foto (aaaa).jpg", l, false), "/$N/id01aaaa/foto.jpg")
    eq("dup ikinci", Bin.resolve("/$N/foto (bbbb).jpg", l, false), "/$N/id02bbbb/foto.jpg")
    eq("dup düz ad çözülmez", Bin.resolve("/$N/foto.jpg", l, false), "/$N/foto.jpg")
    eq("klasör alt yol", Bin.resolve("/$N/Klasör/a/b.txt", l, false), "/$N/id03cccc/Klasör/a/b.txt")
    eq("klasör kendisi", Bin.resolve("/$N/Klasör", l, false), "/$N/id03cccc/Klasör")
    eq(".. kaçışı", Bin.resolve("/$N/Klasör/../../x", l, false), "/$N/Klasör/../../x")
    eq("fiziksel id", Bin.resolve("/$N/id03cccc/Klasör", l, true), "/$N/id03cccc/Klasör")
    eq("bin dışı", Bin.resolve("/DCIM/foto.jpg", l, false), "/DCIM/foto.jpg")
    eq("bilinmeyen ad", Bin.resolve("/$N/yok", l, false), "/$N/yok")
    println(if (bad == 0) "ALL OK" else "$bad FAIL"); if (bad > 0) System.exit(1)
}
