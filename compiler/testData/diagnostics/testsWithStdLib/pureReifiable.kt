// !DIAGNOSTICS: -UNUSED_PARAMETER

@Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@kotlin.internal.PureReifiable
inline fun <reified T> foo(x: T) {}

fun test() {
    foo<List<String>>(listOf(""))
    foo(listOf(""))

    foo<Array<String>>(arrayOf(""))
    foo(arrayOf(""))
}
