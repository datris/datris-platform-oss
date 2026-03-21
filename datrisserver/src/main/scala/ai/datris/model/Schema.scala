package ai.datris.model

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

case class SchemaField(
                          name: String,
                          `type`: String
                      )

case class Schema(
                     fields: java.util.List[SchemaField]
                 )

