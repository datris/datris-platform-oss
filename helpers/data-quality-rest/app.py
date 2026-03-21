#
# This helper is a data quality check for a specific dataset
#

from flask import Flask, jsonify, request

app = Flask(__name__)


@app.route('/dataquality/rest/row', methods=['POST'])
def dataquality_rest_row():
    try:
        payload = request.get_json()
        pipeline_token = payload.get('pipelineToken')
        dataset_name = payload.get('datasetName')
        row = payload.get('row')

        print(f"REST: received pipelineToken={pipeline_token}, datasetName={dataset_name}")
        print(f"REST: row={row}")

        # Process row here
        # On validation failure:
        # return jsonify({'status': 'failure', 'message': 'Column X is invalid'})

        return jsonify({
            'status': 'success',
            'pipelineToken': pipeline_token,
            'datasetName': dataset_name,
        })

    except Exception as e:
        return jsonify({
            'status': 'failure',
            'message': str(e)
        })


@app.route('/dataquality/rest/batch', methods=['POST'])
def dataquality_rest_batch():
    try:
        payload = request.get_json()
        pipeline_token = payload.get('pipelineToken')
        dataset_name = payload.get('datasetName')
        rows = payload.get('rows')
        raw_data = payload.get('rawData')  # Will contain data if JSON or XML

        print(f"REST batch: received pipelineToken={pipeline_token}, datasetName={dataset_name}")
        print(f"REST batch: {len(rows) if rows else 0} rows, rawData={'present' if raw_data else 'None'}")

        failures = []
        # Example:
        # for i, row in enumerate(rows):
        #     if not valid(row):
        #         failures.append({'row': i, 'description': 'Validation failed'})

        return jsonify({
            'status': 'success',
            'pipelineToken': pipeline_token,
            'datasetName': dataset_name,
            'failures': failures
        })

    except Exception as e:
        return jsonify({
            'status': 'failure',
            'message': str(e)
        })

if __name__ == '__main__':
    app.run(host='0.0.0.0', port=5500)