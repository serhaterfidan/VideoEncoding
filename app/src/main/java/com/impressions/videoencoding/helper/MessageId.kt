package com.impressions.videoencoding.helper

enum class MessageId {
    JOB_START_MSG, JOB_PROGRESS_MSG, JOB_SUCCEDED_MSG, JOB_FAILED_MSG, FFMPEG_UNSUPPORTED_MSG, UNKNOWN_MSG;

    companion object {
        fun fromInt(value: Int): MessageId {
            for (id in values()) {
                if (id.ordinal == value) {
                    return id
                }
            }
            return UNKNOWN_MSG
        }
    }
}