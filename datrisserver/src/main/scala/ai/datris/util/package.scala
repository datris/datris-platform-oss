package ai.datris

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

package object util {
    lazy val ObjectStoreUtil: ObjectStoreUtility = MinIOUtilBuilder.build()

    lazy val QueueUtil: QueueUtility = ActiveMQUtilBuilder.build()

    lazy val NotificationUtil: NotificationUtility = ActiveMQNotificationUtilBuilder.build()

    lazy val NoSQLDbUtil: NoSQLDbUtility = MongoDBUtilBuilder.build()

    lazy val SecretsUtil: SecretsManagerUtility = VaultSecretsUtilBuilder.build()
}
