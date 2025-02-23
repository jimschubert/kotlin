@file:JvmVersion
@file:JvmMultifileClass
@file:JvmName("FilesKt")
package kotlin.io

import java.io.*
import java.nio.charset.Charset
import java.util.*

/**
 * Creates an empty directory in the specified [directory], using the given [prefix] and [suffix] to generate its name.
 *
 * If [prefix] is not specified then some unspecified name will be used.
 * If [suffix] is not specified then ".tmp" will be used.
 * If [directory] is not specified then the default temporary-file directory will be used.
 *
 * @return a file object corresponding to a newly-created directory.
*
 * @throws IOException in case of input/output error.
 * @throws IllegalArgumentException if [prefix] is shorter than three symbols.
 */
public fun createTempDir(prefix: String = "tmp", suffix: String? = null, directory: File? = null): File {
    val dir = File.createTempFile(prefix, suffix, directory)
    dir.delete()
    if (dir.mkdir()) {
        return dir
    } else {
        throw IOException("Unable to create temporary directory $dir")
    }
}

/**
 * Creates a new empty file in the specified [directory], using the given [prefix] and [suffix] to generate its name.
 *
 * If [prefix] is not specified then some unspecified name will be used.
 * If [suffix] is not specified then ".tmp" will be used.
 * If [directory] is not specified then the default temporary-file directory will be used.
 *
 * @return a file object corresponding to a newly-created file.
*
 * @throws IOException in case of input/output error.
 * @throws IllegalArgumentException if [prefix] is shorter than three symbols.
 */
public fun createTempFile(prefix: String = "tmp", suffix: String? = null, directory: File? = null): File {
    return File.createTempFile(prefix, suffix, directory)
}

/**
 * Returns this if this file is a directory, or the parent if it is a file inside a directory.
 */
@Deprecated("This property has unclear semantics and will be removed soon.")
public val File.directory: File
    get() = if (isDirectory) this else parentFile!!

/**
 * Returns parent of this abstract path name, or `null` if it has no parent.
 */
@Deprecated("Use 'parentFile' property instead.", ReplaceWith("parentFile"), DeprecationLevel.ERROR)
public val File.parent: File?
    get() = parentFile

/**
 * Returns the extension of this file (not including the dot), or an empty string if it doesn't have one.
 */
public val File.extension: String
    get() = name.substringAfterLast('.', "")

/**
 * Replaces all separators in the string used to separate directories with system ones and returns the resulting string.
 *
 * @return the pathname with system separators.
 */
@Deprecated("Use File.path instead", ReplaceWith("File(this).path", "java.io.File"))
public fun String.separatorsToSystem(): String {
    val otherSep = if (File.separator == "/") "\\" else "/"
    return replace(otherSep, File.separator)
}

/**
 * Replaces all path separators in the string with system ones and returns the resulting string.
 *
 * @return the pathname with system separators.
 */
@Deprecated("This function is deprecated")
public fun String.pathSeparatorsToSystem(): String {
    val otherSep = if (File.pathSeparator == ":") ";" else ":"
    return replace(otherSep, File.pathSeparator)
}

/**
 * Replaces path and directories separators with corresponding system ones and returns the resulting string.
 *
 * @return the pathname with system separators.
 */
@Deprecated("This function is deprecated")
public fun String.allSeparatorsToSystem(): String {
    return separatorsToSystem().pathSeparatorsToSystem()
}

/**
 * Returns a pathname of this file with all path separators replaced with File.pathSeparator.
 *
 * @return the pathname with system separators.
 */
@Deprecated("File has already system separators.")
public fun File.separatorsToSystem(): String {
    return toString().separatorsToSystem()
}

/**
 * Returns [path] of this File using the invariant separator '/' to
 * separate the names in the name sequence.
 */
public val File.invariantSeparatorsPath: String
    get() = if (File.separatorChar != '/') path.replace(File.separatorChar, '/') else path

/**
 * Returns file's name without an extension.
 */
public val File.nameWithoutExtension: String
    get() = name.substringBeforeLast(".")

/**
 * Calculates the relative path for this file from [base] file.
 * Note that the [base] file is treated as a directory.
 * If this file matches the [base] file, then an empty string will be returned.
 *
 * @return relative path from [base] to this.
*
 * @throws IllegalArgumentException if this and base paths have different roots.
 */
@Deprecated("This function will change return type to File soon. Use toRelativeString instead.", ReplaceWith("toRelativeString(base)"))
public fun File.relativeTo(base: File): String
        = toRelativeString(base)


/**
 * Calculates the relative path for this file from [base] file.
 * Note that the [base] file is treated as a directory.
 * If this file matches the [base] file, then an empty string will be returned.
 *
 * @return relative path from [base] to this.
 *
 * @throws IllegalArgumentException if this and base paths have different roots.
 */
public fun File.toRelativeString(base: File): String
        = toRelativeStringOrNull(base) ?: throw IllegalArgumentException("this and base files have different roots: $this and $base")

/**
 * Calculates the relative path for this file from [base] file.
 * Note that the [base] file is treated as a directory.
 * If this file matches the [base] file, then a [File] with empty path will be returned.
 *
 * @return File with relative path from [base] to this.
 *
 * @throws IllegalArgumentException if this and base paths have different roots.
 */
@Deprecated("This function will be renamed to relativeTo soon.")
public fun File.relativeToFile(base: File): File = File(this.relativeTo(base))


/**
 * Calculates the relative path for this file from [base] file.
 * Note that the [base] file is treated as a directory.
 * If this file matches the [base] file, then a [File] with empty path will be returned.
 *
 * @return File with relative path from [base] to this, or `this` if this and base paths have different roots.
 */
public fun File.relativeToOrSelf(base: File): File
        = toRelativeStringOrNull(base)?.let { File(it) } ?: this

/**
 * Calculates the relative path for this file from [base] file.
 * Note that the [base] file is treated as a directory.
 * If this file matches the [base] file, then a [File] with empty path will be returned.
 *
 * @return File with relative path from [base] to this, or `null` if this and base paths have different roots.
 */
public fun File.relativeToOrNull(base: File): File?
        = toRelativeStringOrNull(base)?.let { File(it) }


private fun File.toRelativeStringOrNull(base: File): String? {
    // Check roots
    val thisComponents = this.toComponents().normalize()
    val baseComponents = base.toComponents().normalize()
    if (thisComponents.root != baseComponents.root) {
        return null
    }

    val baseCount = baseComponents.size
    val thisCount = thisComponents.size

    val sameCount = run countSame@ {
        var i = 0
        val maxSameCount = Math.min(thisCount, baseCount)
        while (i < maxSameCount && thisComponents.segments[i] == baseComponents.segments[i])
            i++
        return@countSame i
    }

    // Annihilate differing base components by adding required number of .. parts
    val res = StringBuilder()
    for (i in baseCount - 1 downTo sameCount) {
        if (baseComponents.segments[i].name == "..") {
            return null
        }

        res.append("..")

        if (i != sameCount) {
            res.append(File.separatorChar)
        }
    }

    // Add remaining this components
    if (sameCount < thisCount) {
        // If some .. were appended
        if (sameCount < baseCount)
            res.append(File.separatorChar)

        thisComponents.segments.drop(sameCount).joinTo(res, File.separator)
    }

    return res.toString()
}

/**
 * Calculates the relative path for this file from [descendant] file.
 * Note that the [descendant] file is treated as a directory.
 * If this file matches the [descendant] directory or does not belong to it,
 * then an empty string will be returned.
 */
@Deprecated("Use relativeTo() function instead")
public fun File.relativePath(descendant: File): String {
    val prefix = directory.canonicalPath
    val answer = descendant.canonicalPath
    return if (answer.startsWith(prefix)) {
        val prefixSize = prefix.length
        if (answer.length > prefixSize) {
            answer.substring(prefixSize + 1)
        } else ""
    } else {
        answer
    }
}

/**
 * Copies this file to the given output [dst], returning the number of bytes copied.
 *
 * If some directories on a way to the [dst] are missing, then they will be created.
 * If the [dst] file already exists, then this function will fail unless [overwrite] argument is set to `true`.
 * Otherwise this file overwrites [dst] if it's a file to, or is written into [dst] if it's a directory.
 *
 * Note: this function fails if you call it on a directory.
 * If you want to copy directories, use 'copyRecursively' function instead.
 *
 * @param overwrite `true` if destination overwrite is allowed.
 * @param bufferSize the buffer size to use when copying.
 * @return the number of bytes copied
 * @throws NoSuchFileException if the source file doesn't exist.
 * @throws FileAlreadyExistsException if the destination file already exists and 'rewrite' argument is set to `false`.
 * @throws IOException if any errors occur while copying.
 */
public fun File.copyTo(dst: File, overwrite: Boolean = false, bufferSize: Int = DEFAULT_BUFFER_SIZE): Long {
    if (!exists()) {
        throw NoSuchFileException(file = this, reason = "The source file doesn't exist")
    } else if (isDirectory) {
        throw IllegalArgumentException("Use copyRecursively to copy a directory $this")
    } else if (dst.exists()) {
        if (!overwrite) {
            throw FileAlreadyExistsException(file = this,
                    other = dst,
                    reason = "The destination file already exists")
        } else if (dst.isDirectory && dst.listFiles().any()) {
            // In this case file should be copied *into* this directory,
            // no matter whether it is empty or not
            return copyTo(dst.resolve(name), overwrite, bufferSize)
        }
    }
    dst.parentFile?.mkdirs()
    dst.delete()
    val input = FileInputStream(this)
    return input.use<FileInputStream, Long> {
        val output = FileOutputStream(dst)
        output.use<FileOutputStream, Long> {
            input.copyTo(output, bufferSize)
        }
    }
}

/**
 * Enum that can be used to specify behaviour of the `copyRecursively()` function
 * in exceptional conditions.
 */
public enum class OnErrorAction {
    /** Skip this file and go to the next. */
    SKIP,

    /** Terminate the evaluation of the function. */
    TERMINATE
}

/** Private exception class, used to terminate recursive copying. */
private class TerminateException(file: File) : FileSystemException(file) {}

/**
 * Copies this file with all its children to the specified destination [dst] path.
 * If some directories on the way to the destination are missing, then they will be created.
 *
 * If any errors occur during the copying, then further actions will depend on the result of the call
 * to `onError(File, IOException)` function, that will be called with arguments,
 * specifying the file that caused the error and the exception itself.
 * By default this function rethrows exceptions.
 * Exceptions that can be passed to the `onError` function:
 * NoSuchFileException - if there was an attempt to copy a non-existent file
 * FileAlreadyExistsException - if there is a conflict
 * AccessDeniedException - if there was an attempt to open a directory that didn't succeed.
 * IOException - if some problems occur when copying.
 *
 * @return `false` if the copying was terminated, `true` otherwise.
*
* Note that if this function fails, then partial copying may have taken place.
 */
public fun File.copyRecursively(dst: File,
                                onError: (File, IOException) -> OnErrorAction =
                                { file, exception -> throw exception }
): Boolean {
    if (!exists()) {
        return onError(this, NoSuchFileException(file = this, reason = "The source file doesn't exist")) !=
                OnErrorAction.TERMINATE
    }
    try {
        // We cannot break for loop from inside a lambda, so we have to use an exception here
        for (src in walkTopDown().fail { f, e -> if (onError(f, e) == OnErrorAction.TERMINATE) throw TerminateException(f) }) {
            if (!src.exists()) {
                if (onError(src, NoSuchFileException(file = src, reason = "The source file doesn't exist")) ==
                        OnErrorAction.TERMINATE)
                    return false
            } else {
                val relPath = src.relativeTo(this)
                val dstFile = File(dst, relPath)
                if (dstFile.exists() && !(src.isDirectory && dstFile.isDirectory)) {
                    if (onError(dstFile, FileAlreadyExistsException(file = src,
                            other = dstFile,
                            reason = "The destination file already exists")) == OnErrorAction.TERMINATE)
                        return false
                } else if (src.isDirectory) {
                    dstFile.mkdirs()
                } else {
                    if (src.copyTo(dstFile, true) != src.length()) {
                        if (onError(src, IOException("src.length() != dst.length()")) == OnErrorAction.TERMINATE)
                            return false
                    }
                }
            }
        }
        return true
    } catch (e: TerminateException) {
        return false
    }
}

/**
 * Delete this file with all its children.
 * Note that if this operation fails then partial deletion may have taken place.
 *
 * @return `true` if the file or directory is successfully deleted, `false` otherwise.
 */
public fun File.deleteRecursively(): Boolean = walkBottomUp().fold(true, { res, it -> (it.delete() || !it.exists()) && res })

/**
 * Returns an array of files and directories in the directory that match the specified [filter]
 * or `null` if this file does not denote a directory.
 */
@Deprecated("Provided for binary compatiblity", level = DeprecationLevel.HIDDEN)
public fun File.listFiles(filter: (file: File) -> Boolean): Array<File>? = listFiles(FileFilter(filter))

/**
 * Determines whether this file belongs to the same root as [other]
 * and starts with all components of [other] in the same order.
 * So if [other] has N components, first N components of `this` must be the same as in [other].
 *
 * @return `true` if this path starts with [other] path, `false` otherwise.
 */
public fun File.startsWith(other: File): Boolean {
    val components = toComponents()
    val otherComponents = other.toComponents()
    if (components.root != otherComponents.root)
        return false
    return if (components.size < otherComponents.size) false
    else components.segments.subList(0, otherComponents.size).equals(otherComponents.segments)
}

/**
 * Determines whether this file belongs to the same root as [other]
 * and starts with all components of [other] in the same order.
 * So if [other] has N components, first N components of `this` must be the same as in [other].
 *
 * @return `true` if this path starts with [other] path, `false` otherwise.
 */
public fun File.startsWith(other: String): Boolean = startsWith(File(other))

/**
 * Determines whether this file path ends with the path of [other] file.
 *
 * If [other] is rooted path it must be equal to this.
 * If [other] is relative path then last N components of `this` must be the same as all components in [other],
 * where N is the number of components in [other].
 *
 * @return `true` if this path ends with [other] path, `false` otherwise.
 */
public fun File.endsWith(other: File): Boolean {
    val components = toComponents()
    val otherComponents = other.toComponents()
    if (otherComponents.isRooted)
        return this == other
    val shift = components.size - otherComponents.size
    return if (shift < 0) false
    else components.segments.subList(shift, components.size).equals(otherComponents.segments)
}

/**
 * Determines whether this file belongs to the same root as [other]
 * and ends with all components of [other] in the same order.
 * So if [other] has N components, last N components of `this` must be the same as in [other].
 * For relative [other], `this` can belong to any root.
 *
 * @return `true` if this path ends with [other] path, `false` otherwise.
 */
public fun File.endsWith(other: String): Boolean = endsWith(File(other))

/**
 * Removes all . and resolves all possible .. in this file name.
 * For instance, `File("/foo/./bar/gav/../baaz").normalize()` is `File("/foo/bar/baaz")`.
 *
 * @return normalized pathname with . and possibly .. removed.
 */
public fun File.normalize(): File
        = with (toComponents()) { root.resolve(segments.normalize().joinToString(File.separator)) }

private fun FilePathComponents.normalize(): FilePathComponents
        = FilePathComponents(root, segments.normalize())

private fun List<File>.normalize(): List<File> {
    val list: MutableList<File> = ArrayList(this.size)
    for (file in this) {
        when (file.name) {
            "." -> {}
            ".." -> if (!list.isEmpty() && list.last().name != "..") list.removeAt(list.size - 1) else list.add(file)
            else -> list.add(file)
        }
    }
    return list
}

/**
 * Adds [relative] file to this, considering this as a directory.
 * If [relative] has a root, [relative] is returned back.
 * For instance, `File("/foo/bar").resolve(File("gav"))` is `File("/foo/bar/gav")`.
 * This function is complementary with [relativeTo],
 * so `f.resolve(g.relativeTo(f)) == g` should be always `true` except for different roots case.
 *
 * @return concatenated this and [relative] paths, or just [relative] if it's absolute.
 */
public fun File.resolve(relative: File): File {
    if (relative.isRooted)
        return relative
    val baseName = this.toString()
    return if (baseName.isEmpty() || baseName.endsWith(File.separatorChar)) File(baseName + relative) else File(baseName + File.separatorChar + relative)
}

/**
 * Adds [relative] name to this, considering this as a directory.
 * If [relative] has a root, [relative] is returned back.
 * For instance, `File("/foo/bar").resolve("gav")` is `File("/foo/bar/gav")`.
 *
 * @return concatenated this and [relative] paths, or just [relative] if it's absolute.
 */
public fun File.resolve(relative: String): File = resolve(File(relative))

/**
 * Adds [relative] file to this parent directory.
 * If [relative] has a root or this has no parent directory, [relative] is returned back.
 * For instance, `File("/foo/bar").resolveSibling(File("gav"))` is `File("/foo/gav")`.
 *
 * @return concatenated this.parent and [relative] paths, or just [relative] if it's absolute or this has no parent.
 */
public fun File.resolveSibling(relative: File): File {
    val components = this.toComponents()
    val parentSubPath = if (components.size == 0) File("..") else components.subPath(0, components.size - 1)
    return components.root.resolve(parentSubPath).resolve(relative)
}

/**
 * Adds [relative] name to this parent directory.
 * If [relative] has a root or this has no parent directory, [relative] is returned back.
 * For instance, `File("/foo/bar").resolveSibling("gav")` is `File("/foo/gav")`.
 *
 * @return concatenated this.parent and [relative] paths, or just [relative] if it's absolute or this has no parent.
 */
public fun File.resolveSibling(relative: String): File = resolveSibling(File(relative))
