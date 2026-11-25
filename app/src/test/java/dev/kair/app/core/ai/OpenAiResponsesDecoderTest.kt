package dev.kair.app.core.ai

import dev.kair.app.core.model.StopReason
import dev.kair.app.core.model.Usage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Responses API 事件流解码：用真实事件形状（`event:` + `type` + `item_id`）验证
 * 段落边界、工具调用组装与错误分类。不需要网络。
 */
class OpenAiResponsesDecoderTest {

    @Test
    fun `text deltas become one id-scoped segment with usage`() {
        val d = ResponsesDecoder()
        val events = mutableListOf<StreamEvent>()

        events += d.accept(
            "response.output_item.added",
            """{"type":"response.output_item.added","output_index":0,"item":{"id":"msg_1","type":"message","status":"in_progress","role":"assistant","content":[]}}""",
        )
        events += d.accept(
            "response.output_text.delta",
            """{"type":"response.output_text.delta","item_id":"msg_1","output_index":0,"content_index":0,"delta":"Hi"}""",
        )
        events += d.accept(
            "response.output_text.delta",
            """{"type":"response.output_text.delta","item_id":"msg_1","output_index":0,"content_index":0,"delta":" there"}""",
        )
        events += d.accept(
            "response.completed",
            """{"type":"response.completed","response":{"status":"completed","usage":{"input_tokens":10,"input_tokens_details":{"cached_tokens":4},"output_tokens":3,"output_tokens_details":{"reasoning_tokens":0}}}}""",
        )
        events += d.finish()

        assertEquals(
            listOf(
                StreamEvent.TextStart("msg_1"),
                StreamEvent.TextDelta("Hi"),
                StreamEvent.TextDelta(" there"),
                StreamEvent.TextEnd("msg_1"),
                StreamEvent.UsageEvent(Usage(input = 6, output = 3, cacheRead = 4, reasoning = 0)),
                StreamEvent.Completed(StopReason.STOP),
            ),
            events,
        )
    }

    @Test
    fun `reasoning summary closes before body text starts`() {
        val d = ResponsesDecoder()
        val events = mutableListOf<StreamEvent>()

        events += d.accept(
            "response.reasoning_summary_text.delta",
            """{"type":"response.reasoning_summary_text.delta","item_id":"rs_1","output_index":0,"summary_index":0,"delta":"think"}""",
        )
        events += d.accept(
            "response.output_text.delta",
            """{"type":"response.output_text.delta","item_id":"msg_1","output_index":1,"content_index":0,"delta":"Hi"}""",
        )
        events += d.finish()

        assertEquals(
            listOf(
                StreamEvent.ReasoningStart("rs_1", "SUMMARY_TEXT"),
                StreamEvent.ReasoningDelta("think"),
                StreamEvent.ReasoningEnd("rs_1"),
                StreamEvent.TextStart("msg_1"),
                StreamEvent.TextDelta("Hi"),
                StreamEvent.TextEnd("msg_1"),
                StreamEvent.Completed(StopReason.STOP),
            ),
            events,
        )
    }

    @Test
    fun `function call uses call_id and completes with full arguments`() {
        val d = ResponsesDecoder()
        val events = mutableListOf<StreamEvent>()

        events += d.accept(
            "response.output_item.added",
            """{"type":"response.output_item.added","output_index":0,"item":{"id":"fc_1","type":"function_call","status":"in_progress","call_id":"call_abc","name":"get_weather","arguments":""}}""",
        )
        events += d.accept(
            "response.function_call_arguments.delta",
            """{"type":"response.function_call_arguments.delta","item_id":"fc_1","output_index":0,"delta":"{\"city\":\"Bei"}""",
        )
        events += d.accept(
            "response.function_call_arguments.delta",
            """{"type":"response.function_call_arguments.delta","item_id":"fc_1","output_index":0,"delta":"jing\"}"}""",
        )
        events += d.accept(
            "response.completed",
            """{"type":"response.completed","response":{"status":"completed","usage":{"input_tokens":20,"output_tokens":9}}}""",
        )
        events += d.finish()

        assertEquals(StreamEvent.ToolCallStarted(0, "call_abc", "get_weather"), events[0])
        assertEquals(StreamEvent.ToolCallArgsDelta(0, """{"city":"Bei"""), events[1])
        assertEquals(StreamEvent.ToolCallArgsDelta(0, """jing"}"""), events[2])
        assertEquals(
            StreamEvent.ToolCallsCompleted(listOf(ToolCallData("call_abc", "get_weather", """{"city":"Beijing"}"""))),
            events[4],
        )
        assertEquals(StreamEvent.Completed(StopReason.TOOL_USE), events.last())
    }

    @Test
    fun `failed response is terminal and not retried`() {
        val d = ResponsesDecoder()
        val events = d.accept(
            "response.failed",
            """{"type":"response.failed","response":{"status":"failed","error":{"code":"server_error","message":"boom"}}}""",
        )

        assertEquals(listOf(StreamEvent.Failure("boom", retryable = false)), events)
        assertTrue(d.finish().isEmpty())
    }

    @Test
    fun `incomplete max output tokens maps to length`() {
        val d = ResponsesDecoder()
        d.accept(
            "response.output_text.delta",
            """{"type":"response.output_text.delta","item_id":"msg_1","delta":"partial"}""",
        )
        d.accept(
            "response.incomplete",
            """{"type":"response.incomplete","response":{"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"}}}""",
        )
        val events = d.finish()

        assertEquals(StreamEvent.Completed(StopReason.LENGTH), events.last())
    }

    @Test
    fun `empty response surfaces a retryable failure`() {
        val events = ResponsesDecoder().finish()

        assertEquals(1, events.size)
        val failure = events.single() as StreamEvent.Failure
        assertTrue(failure.retryable == true)
    }
}
