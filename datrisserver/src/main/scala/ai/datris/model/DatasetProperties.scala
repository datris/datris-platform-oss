package ai.datris.model

case class DatasetProperties(
                                name: String,
                                publisherToken: String,
                                pipelineToken: String,
                                metadata: DatasetMetadata,
                                transformFile: String,
                                transformClassName: String,
                                sourceTransformUrl: String,
                                destinationTransformUrl: String,
                                pipelineEnvironment: DatrisEnvironment
                            )
