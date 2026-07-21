package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.NotificationPublishResult

trait NotificationUtility {
    def add(topicName: String, json: String): NotificationPublishResult
    def add(topicName: String, json: String, filter: Map[String, String]): NotificationPublishResult
    def addFifo(topicName: String, json: String, filter: Map[String, String]): NotificationPublishResult
}
