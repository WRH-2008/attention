package com.attention.agent

import kotlinx.serialization.json.JsonObject

/**
 * 一个助手工具。[execute] 必须是状态的纯投影：同步、无副作用、可重复调用；
 * 它抛出的任何异常（参数不合法、状态缺失）都由循环转成错误观测回填给推理器，
 * 因此工具内部不需要也不应该自行把失败编码进返回值。
 * 变更类动作（提交领域 command）属于后续阶段，届时另立类型，不从这里扩展。
 */
interface AgentTool {
    /** 注册名，循环按它查找；推理器通过它引用工具，注册后不可变。 */
    val name: String

    /** 一句话说明工具回答什么问题，下一阶段直接进入 prompt。 */
    val description: String

    /** 参数的 JSON Schema（简版 object schema），下一阶段 LLM 绑定直接可用。 */
    val parametersSchema: JsonObject

    fun execute(args: JsonObject): JsonObject
}

/**
 * 工具注册表。重名在构造时立即失败：两个工具共用一个名字意味着推理器无法
 * 无歧义地引用它们，这种组合根错误不该等到运行中的某次调用才暴露。
 */
class ToolCatalog(tools: List<AgentTool>) {
    private val byName: Map<String, AgentTool> = tools.associateBy { it.name }

    init {
        require(byName.size == tools.size) {
            val duplicated = tools.groupBy { it.name }.filterValues { it.size > 1 }.keys
            "工具名重复：$duplicated"
        }
    }

    val all: List<AgentTool> = tools.toList()

    /** 按名查找；未知名字返回 null，由循环决定如何呈现给推理器。 */
    operator fun get(name: String): AgentTool? = byName[name]
}
