#
# This helper is a preprocessor for a specific pipeline
#

from flask import Flask, jsonify, request
import requests
import threading

app = Flask(__name__)


@app.route('/preprocess/sync', methods=['POST'])
def preprocess_sync():
    payload = request.get_json()
    pipeline_token = payload.get('pipelineToken')
    pipeline_name = payload.get('pipelineName')
    data = payload.get('data')

    print(f"Sync: received pipelineToken={pipeline_token}, pipelineName={pipeline_name}")
    print(f"Sync: data={data}")

    # Process/modify data here if needed

    return jsonify({
        'pipelineToken': pipeline_token,
        'pipelineName': pipeline_name,
        'data': data
    })


@app.route('/preprocess/async', methods=['POST'])
def preprocess_async():
    payload = request.get_json()
    pipeline_token = payload.get('pipelineToken')
    pipeline_name = payload.get('pipelineName')
    data = payload.get('data')

    print(f"Async: received pipelineToken={pipeline_token}, pipelineName={pipeline_name}")
    print(f"Async: data={data}")

    threading.Thread(
        target=send_callback,
        args=(pipeline_token, pipeline_name, data)
    ).start()

    return jsonify({'status': 'accepted'}), 200


def send_callback(pipeline_token, pipeline_name, data):
    callback_url = 'http://localhost:8080/api/v1/restendpoint/callback'

    # Process/modify data here if needed

    payload = {
        'pipelineToken': pipeline_token,
        'pipelineName': pipeline_name,
        'data': data
    }

    try:
        response = requests.post(callback_url, json=payload)
        print(f"Callback sent for token={pipeline_token}, pipeline={pipeline_name}, status={response.status_code}")
    except Exception as e:
        print(f"Callback failed for token={pipeline_token}: {e}")


if __name__ == '__main__':
    app.run(host='0.0.0.0', port=5500)