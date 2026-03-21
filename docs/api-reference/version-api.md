# Version API

Returns the pipeline server version.

## Get Version

```
GET /api/v1/version
```

**Example:**
```bash
curl http://localhost:8080/api/v1/version
```

**Response:** `200 OK`
```json
{
  "version": "latest-version-here"
}
```
