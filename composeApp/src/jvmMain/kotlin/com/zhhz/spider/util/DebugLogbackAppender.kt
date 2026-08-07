package com.zhhz.spider.util

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class DebugLogbackAppender : AppenderBase<ILoggingEvent>() {
    override fun append(eventObject: ILoggingEvent) {
        val time = formatter.format(Instant.ofEpochMilli(eventObject.timeStamp))
        val loggerName = eventObject.loggerName.substringAfterLast('.')
        val message = buildString {
            append(time)
            append(" [")
            append(eventObject.threadName)
            append("] ")
            append(eventObject.level.levelStr)
            append(" ")
            append(loggerName)
            append(" - ")
            append(eventObject.formattedMessage)
            eventObject.throwableProxy?.let { throwable ->
                append('\n')
                append(throwable.className)
                append(": ")
                append(throwable.message.orEmpty())
            }
        }
        DebugLogBuffer.append(message)
    }

    private companion object {
        val formatter: DateTimeFormatter = DateTimeFormatter
            .ofPattern("HH:mm:ss.SSS")
            .withZone(ZoneId.systemDefault())
    }
}
