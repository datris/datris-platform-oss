#
# This helper is a sample REST endpoint for the pipeline transformation row function.
#
# Row mode:  POST /transform/rest/row   — receives one row, returns transformed row (or null to remove)
# Batch mode: POST /transform/rest/batch — receives all rows, returns transformed rows (null entries removed)
#
# Usage:
#     pip install -r requirements.txt
#     python app.py
#
# Then configure the pipeline transformation REST endpoint to:
#     http://host.docker.internal:5600/transform/rest/row  (row mode)
#     http://host.docker.internal:5600/transform/rest/batch (batch mode)
#

from flask import Flask, jsonify, request

app = Flask(__name__)


@app.route('/transform/rest/row', methods=['POST'])
def transform_rest_row():
    """
    Row mode: receives a single row, returns the transformed row.

    Request:
        { "pipelineName": "...", "pipelineToken": "...", "row": { "col1": "val1", "col2": "val2" } }

    Response (transformed):
        { "status": "success", "row": { "col1": "NEW_VAL1", "col2": "new_val2" } }

    Response (remove row):
        { "status": "success", "row": null }

    Response (error):
        { "status": "failure", "message": "Something went wrong" }
    """
    try:
        payload = request.get_json()
        pipeline_name = payload.get('pipelineName')
        pipeline_token = payload.get('pipelineToken')
        row = payload.get('row')

        print(f"Transform row: pipelineName={pipeline_name}, pipelineToken={pipeline_token}")
        print(f"Transform row: input={row}")

        # --- Example transformations (customize these) ---

        # 1. Lowercase all string values
        transformed = {}
        for key, value in row.items():
            if isinstance(value, str):
                transformed[key] = value.lower()
            else:
                transformed[key] = value

        # 2. Remove rows where a field is empty (return null to drop the row)
        # if not row.get('email'):
        #     return jsonify({'status': 'success', 'row': None})

        # 3. Add a computed field
        # transformed['full_name'] = f"{row.get('first_name', '')} {row.get('last_name', '')}"

        print(f"Transform row: output={transformed}")

        return jsonify({
            'status': 'success',
            'row': transformed
        })

    except Exception as e:
        return jsonify({
            'status': 'failure',
            'message': str(e)
        })


@app.route('/transform/rest/batch', methods=['POST'])
def transform_rest_batch():
    """
    Batch mode: receives all rows, returns all transformed rows.

    Request:
        { "pipelineName": "...", "pipelineToken": "...", "rows": [ { "col1": "val1" }, ... ] }

    Response (transformed):
        { "status": "success", "rows": [ { "col1": "NEW_VAL1" }, ... ] }

    Response (with some rows removed — use null entries):
        { "status": "success", "rows": [ { "col1": "NEW_VAL1" }, null, ... ] }

    Response (error):
        { "status": "failure", "message": "Something went wrong" }
    """
    try:
        payload = request.get_json()
        pipeline_name = payload.get('pipelineName')
        pipeline_token = payload.get('pipelineToken')
        rows = payload.get('rows')

        print(f"Transform batch: pipelineName={pipeline_name}, pipelineToken={pipeline_token}")
        print(f"Transform batch: {len(rows) if rows else 0} rows received")

        # --- Example transformations (customize these) ---

        transformed_rows = []
        for row in rows:
            # 1. Lowercase all string values
            transformed = {}
            for key, value in row.items():
                if isinstance(value, str):
                    transformed[key] = value.lower()
                else:
                    transformed[key] = value
            transformed_rows.append(transformed)

            # 2. To remove a row, append None instead:
            # if not row.get('email'):
            #     transformed_rows.append(None)
            # else:
            #     transformed_rows.append(transformed)

        print(f"Transform batch: {len(transformed_rows)} rows returned")

        return jsonify({
            'status': 'success',
            'rows': transformed_rows
        })

    except Exception as e:
        return jsonify({
            'status': 'failure',
            'message': str(e)
        })


if __name__ == '__main__':
    app.run(host='0.0.0.0', port=5600)
