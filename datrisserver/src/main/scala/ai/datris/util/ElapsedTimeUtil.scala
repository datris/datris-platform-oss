package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

object ElapsedTimeUtil {
    def getElapsedTime(durationMillis: Long): (String, Boolean) = {
        val millis: Long = (durationMillis % 1000) / 10
        var seconds: Long = durationMillis / 1000
        var minutes: Long = {
            if (seconds > 0) {
                val min = seconds / 60
                seconds = seconds - (min * 60)
                min
            } else
                0
        }
        val hours: Long = {
            if (minutes > 0) {
                val hr = minutes / 60
                minutes = minutes - (hr * 60)
                hr
            } else
                0
        }
        val millisStr = f"$millis%02d"
        if (hours > 0) {
            if (hours > 8)
                ("timed out", true)
            else
                (hours.toString + " hr " + minutes.toString + " min " + seconds + "." + millisStr + " sec", false)
        } else if (minutes > 0)
            (minutes.toString + " min " + seconds + "." + millisStr + " sec", false)
        else
            (seconds + "." + millisStr + " sec", false)
    }
}
