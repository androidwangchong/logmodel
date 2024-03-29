package com.base.logmodel.logger

import android.os.Environment
import android.os.HandlerThread
import com.base.logmodel.logger.Utils.checkNotNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.*


/**
 * CSV formatted file logging for Android.
 * Writes to CSV the following data:
 * epoch timestamp, ISO8601 timestamp (human-readable), log level, tag, log message.
 */
class CsvFormatStrategy private constructor(builder: Builder) : FormatStrategy {

    private val methodCount: Int
    private val methodOffset: Int
    private val showThreadInfo: Boolean
    private val date: Date
    private val dateFormat: SimpleDateFormat
    private val logStrategy: LogStrategy
    private val tag: String?

    init {
        checkNotNull(builder)

        methodCount = builder.methodCount
        methodOffset = builder.methodOffset
        showThreadInfo = builder.showThreadInfo
        date = builder.date!!
        dateFormat = builder.dateFormat!!
        logStrategy = builder.logStrategy!!
        tag = builder.tag
    }

    override fun log(priority: Int, onceOnlyTag: String?, message: String) {
        checkNotNull(message)

        val tag = formatTag(onceOnlyTag)

        logTopBorder(priority, tag)
        logHeaderContent(priority, tag, methodCount)

        //get bytes of message with system's default charset (which is UTF-8 for Android)
        val bytes = message.toByteArray()
        val length = bytes.size
        if (length <= CHUNK_SIZE) {
            if (methodCount > 0) {
                logDivider(priority, tag)
            }
            logContent(priority, tag, message)
            logBottomBorder(priority, tag)
            return
        }
        if (methodCount > 0) {
            logDivider(priority, tag)
        }
        var i = 0
        while (i < length) {
            val count = Math.min(length - i, CHUNK_SIZE)
            //create a new String with system's default charset (which is UTF-8 for Android)
            logContent(priority, tag, String(bytes, i, count))
            i += CHUNK_SIZE
        }
        logBottomBorder(priority, tag)
    }

    private fun logBottomBorder(logType: Int, tag: String?) {
        logChunk(logType, tag, BOTTOM_BORDER)
    }

    private fun logTopBorder(logType: Int, tag: String?) {
        logChunk(logType, tag, TOP_BORDER)
    }

    private fun logDivider(logType: Int, tag: String?) {
        logChunk(logType, tag, MIDDLE_BORDER)
    }

    private fun logContent(logType: Int, tag: String?, chunk: String) {
        checkNotNull(chunk)

        val lines = chunk.split(System.getProperty("line.separator").toRegex())
            .dropLastWhile { it.isEmpty() }.toTypedArray()
        for (line in lines) {
            logChunk(logType, tag, "$HORIZONTAL_LINE $line")
        }
    }

    private fun logChunk(priority: Int, tag: String?, chunk: String) {
        checkNotNull(chunk)
        logStrategy.log(priority, tag, chunk + "\n")
    }

    private fun logHeaderContent(logType: Int, tag: String?, methodCount: Int) {
        var methodCount = methodCount
        val trace = Thread.currentThread().stackTrace
        if (showThreadInfo) {
            logChunk(logType, tag, HORIZONTAL_LINE + " Thread: " + Thread.currentThread().name)
            logChunk(logType, tag, HORIZONTAL_LINE + " Time: " + dateFormat.format(date))
            logDivider(logType, tag)
        }
        var level = ""

        val stackOffset = getStackOffset(trace) + methodOffset

        //corresponding method count with the current stack may exceeds the stack trace. Trims the count
        if (methodCount + stackOffset > trace.size) {
            methodCount = trace.size - stackOffset - 1
        }

        for (i in methodCount downTo 1) {
            val stackIndex = i + stackOffset
            if (stackIndex >= trace.size) {
                continue
            }
            val builder = StringBuilder()
            builder.append(HORIZONTAL_LINE)
                .append(' ')
                .append(level)
                .append(getSimpleClassName(trace[stackIndex].className))
                .append(".")
                .append(trace[stackIndex].methodName)
                .append(" ")
                .append(" (")
                .append(trace[stackIndex].fileName)
                .append(":")
                .append(trace[stackIndex].lineNumber)
                .append(")")
            level += "   "
            logChunk(logType, tag, builder.toString())
        }
    }

    private fun getStackOffset(trace: Array<StackTraceElement>): Int {
        checkNotNull(trace)

        var i = MIN_STACK_OFFSET
        while (i < trace.size) {
            val e = trace[i]
            val name = e.className
            if (name != LoggerPrinter::class.java.name && name != Logger::class.java.name) {
                return --i
            }
            i++
        }
        return -1
    }

    private fun getSimpleClassName(name: String): String {
        checkNotNull(name)

        val lastIndex = name.lastIndexOf(".")
        return name.substring(lastIndex + 1)
    }

    private fun formatTag(tag: String?): String? {
        return if (!Utils.isEmpty(tag) && !Utils.equals(this.tag, tag)) {
            this.tag + "-" + tag
        } else this.tag
    }

    class Builder() {

        internal var methodCount = 2
        internal var methodOffset = 0
        internal var showThreadInfo = true
        internal var date: Date? = null
        internal var dateFormat: SimpleDateFormat? = null
        internal var logStrategy: LogStrategy? = null
        internal var tag: String? = "PRETTY_LOGGER"
        private var folderName: String =
            Environment.getExternalStorageDirectory().absolutePath + File.separatorChar + "logger"

        fun methodCount(`val`: Int): Builder {
            methodCount = `val`
            return this
        }

        fun methodOffset(`val`: Int): Builder {
            methodOffset = `val`
            return this
        }

        fun showThreadInfo(`val`: Boolean): Builder {
            showThreadInfo = `val`
            return this
        }

        fun date(`val`: Date?): Builder {
            date = `val`
            return this
        }

        fun dateFormat(`val`: SimpleDateFormat?): Builder {
            dateFormat = `val`
            return this
        }

        fun logStrategy(`val`: LogStrategy?): Builder {
            logStrategy = `val`
            return this
        }

        fun tag(tag: String?): Builder {
            this.tag = tag
            return this
        }

        fun folderName(folder: String): Builder {
            this.folderName = folder
            return this
        }

        fun build(): CsvFormatStrategy {
            if (date == null) {
                date = Date()
            }
            if (dateFormat == null) {
                dateFormat = SimpleDateFormat("yyyy.MM.dd HH:mm:ss.SSS", Locale.CHINA)
            }
            if (logStrategy == null) {
                val folder = folderName

                val ht = HandlerThread("AndroidFileLogger.$folder")
                ht.start()
                val handler = DiskLogStrategy.WriteHandler(ht.looper, folder, MAX_BYTES)
                logStrategy = DiskLogStrategy(handler)
            }
            return CsvFormatStrategy(this)
        }

        companion object {
            private const val MAX_BYTES = 500 * 1024 // 500K averages to a 4000 lines per file
        }
    }

    companion object {

        private val NEW_LINE = System.getProperty("line.separator")
        private val NEW_LINE_REPLACEMENT = " <br> "
        private val SEPARATOR = ","

        /**
         * Android's max limit for a log entry is ~4076 bytes,
         * so 4000 bytes is used as chunk size since default charset
         * is UTF-8logTopBorder
         */
        private const val CHUNK_SIZE = 4000

        /**
         * The minimum stack trace index, starts at this class after two native calls.
         */
        private const val MIN_STACK_OFFSET = 5

        /**
         * Drawing toolbox
         */
        private const val TOP_LEFT_CORNER = '┌'
        private const val BOTTOM_LEFT_CORNER = '└'
        private const val MIDDLE_CORNER = '├'
        private const val HORIZONTAL_LINE = '│'
        private const val DOUBLE_DIVIDER =
            "────────────────────────────────────────────────────────"
        private const val SINGLE_DIVIDER =
            "┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄"
        private val TOP_BORDER = TOP_LEFT_CORNER + DOUBLE_DIVIDER + DOUBLE_DIVIDER
        private val BOTTOM_BORDER = BOTTOM_LEFT_CORNER + DOUBLE_DIVIDER + DOUBLE_DIVIDER
        private val MIDDLE_BORDER = MIDDLE_CORNER + SINGLE_DIVIDER + SINGLE_DIVIDER


        fun newBuilder(): Builder {
            return Builder()
        }
    }
}
