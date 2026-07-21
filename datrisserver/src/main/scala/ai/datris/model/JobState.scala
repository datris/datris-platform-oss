package ai.datris.model

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

sealed trait JobState
case object INITIALIZED extends JobState
case object PROCESSING extends JobState
case object COMPLETED extends JobState
case object CANCELLED extends JobState
