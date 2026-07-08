package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import com.google.gson.{JsonArray, JsonObject}
import ai.datris.util.AgentLoop
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

/** Shared SSE plumbing for assistant-style endpoints (build mode and ops
  * mode). Both controllers run an `AgentLoop` and forward its events to an
  * `SseEmitter`; the wire format is identical, only the system prompt and
  * tool catalog ordering differ on the server.
  *
  * Pulled out so the two endpoints can't drift in their SSE event shape —
  * the UI parses a single AssistantEvent union for both. */
object AssistantSseSupport {

    /** Serialize an AgentLoop event onto the SSE emitter. Matches the
      * existing wire format consumed by the UI's ops-assistant.service.ts
      * and assistant.service.ts.
      *
      * Returns false if the write failed because the client is gone (a mid-
      * stream browser refresh closes the SSE socket). Callers use that to stop
      * pushing further events at the source rather than firing a broken-pipe
      * write for every remaining token delta. */
    def emitLoopEvent(emitter: SseEmitter, evt: AgentLoop.LoopEvent): Boolean = {
        evt match {
            case AgentLoop.LoopEvent.IterationStart =>
                sendEvent(emitter, "iteration_start", makeEvent("iteration_start"))
            case AgentLoop.LoopEvent.ThinkingDelta(t) =>
                sendEvent(emitter, "thinking_delta", makeEvent("thinking_delta", "text", t))
            case AgentLoop.LoopEvent.TextDelta(t) =>
                sendEvent(emitter, "text_delta", makeEvent("text_delta", "text", t))
            case AgentLoop.LoopEvent.ToolUseStart(id, name) =>
                val obj = new JsonObject()
                obj.addProperty("type", "tool_use_start")
                obj.addProperty("id", id)
                obj.addProperty("name", name)
                sendEvent(emitter, "tool_use_start", obj)
            case AgentLoop.LoopEvent.InputDelta(id, chars) =>
                val obj = new JsonObject()
                obj.addProperty("type", "input_delta")
                obj.addProperty("id", id)
                obj.addProperty("chars", chars)
                sendEvent(emitter, "input_delta", obj)
            case AgentLoop.LoopEvent.ToolUseComplete(id, name, input) =>
                val obj = new JsonObject()
                obj.addProperty("type", "tool_use")
                obj.addProperty("id", id)
                obj.addProperty("name", name)
                obj.add("input", input)
                sendEvent(emitter, "tool_use", obj)
            case AgentLoop.LoopEvent.ToolResult(id, name, result, isError) =>
                val obj = new JsonObject()
                obj.addProperty("type", "tool_result")
                obj.addProperty("id", id)
                obj.addProperty("name", name)
                obj.addProperty("result", result)
                obj.addProperty("isError", isError)
                sendEvent(emitter, "tool_result", obj)
            case AgentLoop.LoopEvent.SecretRequest(id, secretName, fieldNames, reason) =>
                val obj = new JsonObject()
                obj.addProperty("type", "secret_request")
                obj.addProperty("id", id)
                obj.addProperty("secretName", secretName)
                val fieldsArr = new JsonArray()
                fieldNames.foreach(fieldsArr.add)
                obj.add("fieldNames", fieldsArr)
                obj.addProperty("reason", reason)
                sendEvent(emitter, "secret_request", obj)
            case AgentLoop.LoopEvent.Notice(msg) =>
                sendEvent(emitter, "notice", makeEvent("notice", "message", msg))
            case AgentLoop.LoopEvent.Done =>
                sendEvent(emitter, "done", makeEvent("done"))
            case AgentLoop.LoopEvent.Error(msg) =>
                sendEvent(emitter, "error", makeEvent("error", "message", msg))
        }
    }

    def makeEvent(t: String, kvs: String*): JsonObject = {
        val obj = new JsonObject()
        obj.addProperty("type", t)
        kvs.grouped(2).foreach { pair =>
            if (pair.size == 2) obj.addProperty(pair(0), pair(1))
        }
        obj
    }

    /** Write one SSE frame. Returns false (rather than throwing) when the
      * client has disconnected — the write to a closed socket raises
      * IOException("Broken pipe"), which is benign here: the user navigated
      * away or refreshed. We swallow it so it never surfaces as an application
      * error, and report failure so the caller can stop emitting. */
    def sendEvent(emitter: SseEmitter, name: String, payload: JsonObject): Boolean = {
        try {
            emitter.send(SseEmitter.event().name(name).data(payload.toString))
            true
        } catch {
            case _: Exception => false // client disconnected; stop emitting
        }
    }

    def escape(s: String): String =
        if (s == null) ""
        else s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")
}
