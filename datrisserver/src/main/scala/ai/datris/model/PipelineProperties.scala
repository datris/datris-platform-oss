package ai.datris.model

case class PipelineProperties(
                                name: String,
                                publisherToken: String,
                                pipelineToken: String,
                                metadata: PipelineMetadata,
                                transformFile: String,
                                transformClassName: String,
                                sourceTransformUrl: String,
                                destinationTransformUrl: String,
                                pipelineEnvironment: DatrisEnvironment
                            )
