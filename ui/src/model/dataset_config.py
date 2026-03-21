from dataclasses import dataclass, field
from typing import Optional


@dataclass
class CsvAttributes:
    delimiter: Optional[str] = None
    header: Optional[bool] = None
    encoding: Optional[str] = None  # UTF-8, ISO-8859-1, etc


@dataclass
class JsonAttributes:
    every_row_contains_object: Optional[bool] = None  # If true, each row contains a JSON object
    encoding: Optional[str] = None  # UTF-8, ISO-8859-1, etc


@dataclass
class XmlAttributes:
    every_row_contains_object: Optional[bool] = None  # If true, each row contains an XML object
    encoding: Optional[str] = None  # UTF-8, ISO-8859-1, etc


@dataclass
class XlsAttributes:
    worksheet: Optional[int] = None
    temp_csv_file_delimiter: Optional[str] = None


@dataclass
class UnstructuredAttributes:
    file_extension: Optional[str] = None
    preserve_filename: Optional[bool] = None


@dataclass
class DatabaseAttributes:
    type: Optional[str] = None
    postgres_secrets_name: Optional[str] = None
    mssql_secrets_name: Optional[str] = None
    mysql_secrets_name: Optional[str] = None
    cron_expression: Optional[str] = None
    database: Optional[str] = None
    schema: Optional[str] = None
    table: Optional[str] = None
    include_fields: list[str] = field(default_factory=list)
    timestamp_field_name: Optional[str] = None
    sql_override: Optional[str] = None
    output_delimiter: Optional[str] = None


@dataclass
class FileAttributes:
    csv_attributes: Optional[CsvAttributes] = None
    json_attributes: Optional[JsonAttributes] = None
    xml_attributes: Optional[XmlAttributes] = None
    xls_attributes: Optional[XlsAttributes] = None
    unstructured_attributes: Optional[UnstructuredAttributes] = None
    spark_read_options: dict[str, str] = field(default_factory=dict)


@dataclass
class SchemaField:
    name: Optional[str] = None
    type: Optional[str] = None


@dataclass
class SchemaProperties:
    db_name: Optional[str] = None
    fields: list[SchemaField] = field(default_factory=list)


@dataclass
class Source:
    schema_properties: Optional[SchemaProperties] = None
    file_attributes: Optional[FileAttributes] = None
    database_attributes: Optional[DatabaseAttributes] = None


@dataclass
class Snowflake:
    warehouse: Optional[str] = None
    sql_override: Optional[str] = None
    create_semi_structured_field_as: Optional[str] = None


@dataclass
class Database:
    db_name: Optional[str] = None
    schema: Optional[str] = None
    table: Optional[str] = None
    key_fields: list[str] = field(default_factory=list)
    manage_table_manually: Optional[bool] = None
    truncate_before_write: Optional[bool] = None
    use_snowflake: Optional[bool] = None
    use_redshift: Optional[bool] = None
    use_postgres: Optional[bool] = None
    snowflake: Optional[Snowflake] = None
    options: list[str] = field(default_factory=list)


@dataclass
class ObjectStore:
    prefix_key: Optional[str] = None
    partition_by: list[str] = field(default_factory=list)
    destination_bucket_override: Optional[str] = None
    file_format: Optional[str] = None
    write_to_temporary_location: Optional[bool] = None
    delete_before_write: Optional[bool] = None
    manage_glue_table_manually: Optional[bool] = None
    use_iceberg: Optional[bool] = None
    key_fields: list[str] = field(default_factory=list)
    use_athena: Optional[bool] = None
    use_spark_cluster: Optional[bool] = None
    spark_write_mode: Optional[str] = None


@dataclass
class Destination:
    schema_properties: Optional[SchemaProperties] = None
    database: Optional[Database] = None
    object_store: Optional[ObjectStore] = None


@dataclass
class RowRule:
    function: Optional[str] = None
    parameters: list[str] = field(default_factory=list)
    on_failure_is_error: Optional[bool] = None


@dataclass
class ColumnRule:
    column_name: Optional[str] = None
    function: Optional[str] = None
    parameter: Optional[str] = None
    on_failure_is_error: Optional[bool] = None
    description: Optional[str] = None


@dataclass
class DataQuality:
    validate_file_header: Optional[bool] = None
    validation_schema: Optional[str] = None
    row_rules: list[RowRule] = field(default_factory=list)
    column_rules: list[ColumnRule] = field(default_factory=list)


@dataclass
class RowFunction:
    function: Optional[str] = None
    parameters: list[str] = field(default_factory=list)


@dataclass
class Transformation:
    trim_column_whitespace: Optional[bool] = None
    deduplicate: Optional[bool] = None
    row_functions: list[RowFunction] = field(default_factory=list)


@dataclass
class DatasetConfig:
    name: str
    source: Source
    data_quality: Optional[DataQuality] = None
    transformation: Optional[Transformation] = None
    destination: Destination