package com.zhhz.spider.util

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DebugLogbackAppender : AppenderBase<ILoggingEvent>() {
    override fun append(event: ILoggingEvent) {
        val message = buildString {
            append(formatter.format(Date(event.timeStamp)))
            append(" [")
            append(event.threadName)
            append("] ")
            append(event.level.levelStr)
            append(' ')
            append(event.loggerName.substringAfterLast('.'))
            append(" - ")
            append(event.formattedMessage)
            event.throwableProxy?.let {
                append('\n')
                append(it.className)
                append(": ")
                append(it.message.orEmpty())
            }
        }
        DebugLogBuffer.append(message)
    }

    private companion object {
        val formatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    }
}
