package com.example.util

object TimeUtils {

    fun formatDate(timestamp: Long): String {
        return DateUtils.formatDate(timestamp)
    }

    fun formatTime(timestamp: Long): String {
        return DateUtils.formatTime(timestamp)
    }

    fun formatRelativeTime(timestamp: Long): String {
        return DateUtils.formatRelativeTime(timestamp)
    }

    fun formatFullDateTime(timestamp: Long): String {
        return DateUtils.formatDateTime(timestamp)
    }
}

