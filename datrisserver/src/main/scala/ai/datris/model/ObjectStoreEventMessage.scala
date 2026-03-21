package ai.datris.model

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

// S3-compatible event notification format used by MinIO
case class UserIdentity(
                           principalId: String
                       )
case class RequestParameters(
                                sourceIPAddress: String
                            )
case class ResponseElements(
                               `x-amz-request-id`: String,
                               `x-amz-id-2`: String
                           )
case class Bucket(
                     name: String,
                     ownerIdentity: UserIdentity,
                     arn: String
                 )
case class ObjectBis(
                        key: String,
                        size: Double,
                        eTag: String,
                        sequencer: String
                    )
case class S3(
                 s3SchemaVersion: String,
                 configurationId: String,
                 bucket: Bucket,
                 `object`: ObjectBis
             )
case class Records(
                      eventVersion: String,
                      eventSource: String,
                      awsRegion: String,
                      eventTime: String,
                      eventName: String,
                      userIdentity: UserIdentity,
                      requestParameters: RequestParameters,
                      responseElements: ResponseElements,
                      s3: S3
                  )
case class ObjectStoreEventMessage(
                                      Records: java.util.List[Records]
                                  )
