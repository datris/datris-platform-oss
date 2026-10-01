# Release Notes

## v1.40.0 — October 1, 2026

**Unity Catalog integration for Databricks, warehouse queries in the Search tab, and less to configure for Databricks pipelines.**

- **Datris annotates your Databricks tables in Unity Catalog.** Turn on Unity Catalog for a pipeline and, after every successful load, its table gets a description, stable tags (which pipeline loaded it, its data-quality status, managed by Datris) and run details in the table properties, all visible in Catalog Explorer. Nothing is written unless a pipeline opts in, and a metadata problem is reported as a warning while the load still succeeds.
- **Lineage appears in Unity Catalog too.** The same opt-in publishes source → pipeline → table lineage, with column mappings, to the table's Lineage tab.
- **Agents can look around Unity Catalog before building a pipeline.** A new tool lists the catalogs, schemas, tables and columns a connection can see, and data discovery can include warehouse tables in its results, marking the ones Datris loaded.
- **Iceberg tables can be registered with, or committed through, an Iceberg REST catalog.** Object-store pipelines writing Iceberg can keep an external catalog current. Databricks Unity Catalog does not yet accept tables at a location you choose; Datris detects this, warns, and keeps writing safely. Use the Databricks destination for governed Databricks tables.
- **Query Databricks and Snowflake from the Search tab.** Pick a pipeline, run read-only SQL and see the results in place. Error messages across every query type are now short and specific.
- **Less to configure for Databricks.** A pipeline no longer needs the SQL warehouse in its configuration when the connection secret provides it, and assistants stop asking for it.
- **Deleting a pipeline tells you when a catalog still references its table.** The CLI shows the same notice. Its keep-data option, which never kept data, now refuses instead of deleting silently.
- **Editors and viewers can run read-only queries** against object-store, Snowflake and Databricks destinations.
- **Fix: uploads to pipelines that leave out the CSV delimiter no longer fail.**

**Upgrading**

Run `docker compose pull && docker compose up -d --force-recreate`. All four images changed. No configuration changes are required: the Unity Catalog features stay off until a pipeline opts in, and a global off switch is documented. To write tags and lineage, the Databricks service principal needs two additional grants. See [Unity Catalog](/destinations/unity-catalog).
