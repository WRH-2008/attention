package com.attention.agent

import java.util.concurrent.CancellationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// JVM 上 kotlinx.coroutines.CancellationException 就是本文件导入的 JDK 类型的别名：
// 捕获它即可接住协程取消，又不必让纯投影模块拉进 coroutines 依赖（与 :domain/:workspace-state 一致）。

/**
 * ReAct 循环：请求进，终态出。每一轮把推理器的决策落成事件——思考、行动、观测
 * ——并把观测回填给下一轮推理，直到答复、预算耗尽或推理器失败。
 *
 * 这是本模块的深模块：对外只有 [run] 一个入口，两个依赖（推理器、工具表）都是
 * 单方法接口；事件建模、错误吸收、预算控制全部藏在循环里，
 * 调用方与测试只面对终态和轨迹（ADR-0008）。
 */
class ReActAgent(
    private val reasoner: Reasoner,
    private val tools: ToolCatalog,
    private val maxSteps: Int = DEFAULT_MAX_STEPS,
) {
    init {
        require(maxSteps > 0) { "maxSteps 必须为正，实际 $maxSteps" }
    }

    /**
     * 跑一次循环。除协程取消外不抛任何异常：推理器自身失败收敛为
     * [AgentOutcome.Failed]；工具侧的一切失败（未知工具、参数不合法、执行异常）
     * 都收敛为带 isError 的观测回填给推理器，由它决定纠正、换路还是直接答复。
     */
    suspend fun run(request: String): AgentOutcome {
        val trace = mutableListOf<AgentEvent>()
        repeat(maxSteps) {
            val decision = try {
                reasoner.reason(request, trace.toList())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                return AgentOutcome.Failed(describe(failure), trace)
            }
            when (decision) {
                is ReasonerDecision.Act -> {
                    trace += AgentEvent.Thought(decision.thought)
                    trace += actionEvents(decision.action)
                }
                is ReasonerDecision.Answer -> {
                    decision.thought?.let { trace += AgentEvent.Thought(it) }
                    trace += AgentEvent.Final(decision.text)
                    return AgentOutcome.Answered(decision.text, trace)
                }
            }
        }
        return AgentOutcome.BudgetExhausted(trace)
    }

    /** 一次行动 = 一条行动事件加一条观测事件；观测来自工具执行。 */
    private fun actionEvents(action: ActionRequest): List<AgentEvent> =
        listOf(AgentEvent.Action(action.tool, action.args), observe(action))

    /** 工具侧失败不是循环的失败：统一转成错误观测，让推理器看到原因并自行纠正。 */
    private fun observe(action: ActionRequest): AgentEvent.Observation {
        val tool = tools[action.tool] ?: return errorObservation("未知工具：${action.tool}")
        return try {
            AgentEvent.Observation(tool.execute(action.args))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            errorObservation(describe(failure))
        }
    }

    private fun errorObservation(message: String): AgentEvent.Observation =
        AgentEvent.Observation(buildJsonObject { put("error", message) }, isError = true)

    /** 异常给推理器看的是人话：有消息用消息，没有消息退到异常类名。 */
    private fun describe(failure: Throwable): String =
        failure.message ?: failure::class.java.simpleName

    companion object {
        const val DEFAULT_MAX_STEPS = 8
    }
}
