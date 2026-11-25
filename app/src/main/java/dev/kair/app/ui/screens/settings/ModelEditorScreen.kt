package dev.kair.app.ui.screens.settings

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.kair.app.R
import dev.kair.app.core.ai.ModelCatalog
import dev.kair.app.core.ai.ModelProbe
import dev.kair.app.core.ai.ProbedModel
import dev.kair.app.core.ai.TransportIds
import dev.kair.app.core.ai.inferEndpoint
import dev.kair.app.core.settings.ReasoningAuto
import dev.kair.app.core.settings.ReasoningLevels
import dev.kair.app.core.settings.SavedModel
import dev.kair.app.ui.components.KairTopBar
import kotlinx.coroutines.launch

/**
 * 模型编辑器（整屏）：协议 / 连接信息 / 能力标记 / 思考强度 / 高级采样参数。
 * 保存时用 [SavedModel.copy] 保留原 id（新建项的 id 在 [newSavedModel] 里生成）。
 *
 * 表单各段在 `ModelEditorFormSections.kt`、选择器在 `ModelEditorDialogs.kt`；
 * 这里只持有状态、校验与保存。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelEditorScreen(
    initial: SavedModel,
    onCancel: () -> Unit,
    onSave: (SavedModel) -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var providerId by remember { mutableStateOf(initial.providerId) }
    // 协议覆盖仅对「自定义」生效；预设（LongCat / Gemini）由 ProviderDef 固定。
    var transportId by remember {
        mutableStateOf(initial.transportId?.takeIf { it.isNotBlank() } ?: TransportIds.OPENAI_CHAT)
    }
    // 用户手动选过协议后不再让地址自动改写协议；重新选服务商时解除。
    var protocolPinned by remember { mutableStateOf(false) }
    var baseUrl by remember { mutableStateOf(initial.baseUrl) }
    var apiKey by remember { mutableStateOf(initial.apiKey) }
    var model by remember { mutableStateOf(initial.model) }
    var maxTokens by remember { mutableStateOf(initial.maxTokens?.toString() ?: "") }
    var contextWindow by remember { mutableStateOf(initial.contextWindow?.toString() ?: "") }
    var temperature by remember { mutableStateOf(initial.temperature?.toString() ?: "") }
    var isVision by remember { mutableStateOf(initial.isVisionModel) }
    var isReasoning by remember { mutableStateOf(initial.isReasoningModel) }
    var replayThinking by remember { mutableStateOf(initial.replayThinking) }
    var levels by remember { mutableStateOf(initial.reasoningLevels.toSet()) }
    var levelMap by remember { mutableStateOf(initial.thinkingLevelMap) }
    var defaultEffort by remember { mutableStateOf(initial.reasoningEffort) }
    var frequencyPenalty by remember { mutableStateOf(initial.frequencyPenalty?.toString() ?: "") }
    var presencePenalty by remember { mutableStateOf(initial.presencePenalty?.toString() ?: "") }
    var topP by remember { mutableStateOf(initial.topP?.toString() ?: "") }
    var seed by remember { mutableStateOf(initial.seed?.toString() ?: "") }
    var showProviderPicker by remember { mutableStateOf(false) }
    var showProtocolPicker by remember { mutableStateOf(false) }
    // 模型列表探测：先拉取服务商公开的模型，再按选中项探测上下文 / 思考档位。
    var listingModels by remember { mutableStateOf(false) }
    var probing by remember { mutableStateOf(false) }
    var probeMessage by remember { mutableStateOf<String?>(null) }
    var modelPicker by remember { mutableStateOf<List<ProbedModel>?>(null) }
    val probeScope = rememberCoroutineScope()
    val appContext = LocalContext.current

    val def = ModelCatalog.byId(providerId)
    val effectiveTransport = if (providerId == "openai") transportId else def.transportId
    val isOpenAi = effectiveTransport == TransportIds.OPENAI_CHAT
    val transportName = when (effectiveTransport) {
        TransportIds.OPENAI_RESPONSES -> "OpenAI Responses"
        TransportIds.ANTHROPIC_MESSAGES -> "Anthropic Messages"
        TransportIds.GEMINI_GENERATE -> stringResource(R.string.settings_transport_gemini_native)
        else -> "OpenAI Chat"
    }

    // 选填数值项：留空合法（输出上限留空用默认 8192，其余参数不发送）；
    // 非空必须是数字且落在合法区间内。
    fun invalidInt(s: String, min: Int, max: Int? = null): Boolean {
        if (s.isBlank()) return false
        val v = s.trim().toIntOrNull() ?: return true
        return v < min || (max != null && v > max)
    }
    fun invalidDouble(s: String, min: Double, max: Double): Boolean {
        if (s.isBlank()) return false
        val v = s.trim().toDoubleOrNull() ?: return true
        return v < min || v > max
    }

    val errorMaxTokens = invalidInt(maxTokens, 1)
    val errorContextWindow = invalidInt(contextWindow, 1)
    val errorTemperature = invalidDouble(temperature, 0.0, 2.0)
    val errorFrequencyPenalty = invalidDouble(frequencyPenalty, -2.0, 2.0)
    val errorPresencePenalty = invalidDouble(presencePenalty, -2.0, 2.0)
    val errorTopP = invalidDouble(topP, 0.0, 1.0)
    val errorSeed = invalidInt(seed, 0)

    // 保存按钮启用条件：必填项（模型 ID / 连接地址 / 需要时还有密钥）已填，
    // 且所有数值项要么留空、要么合法（含区间）。高级采样参数仅在 OpenAI 协议下可编辑，
    // 非 OpenAI 时忽略其中的残留文本。
    val canSave = model.isNotBlank() &&
        baseUrl.isNotBlank() &&
        (!def.needsKey || apiKey.isNotBlank()) &&
        !errorMaxTokens &&
        !errorContextWindow &&
        !errorTemperature &&
        (!isOpenAi ||
            (!errorFrequencyPenalty && !errorPresencePenalty && !errorTopP && !errorSeed))

    fun buildModel(): SavedModel = initial.copy(
        name = name.trim().ifBlank { model.trim() },
        providerId = providerId,
        transportId = if (providerId == "openai") transportId else null,
        baseUrl = baseUrl.trim(),
        apiKey = apiKey.trim(),
        model = model.trim(),
        maxTokens = maxTokens.trim().toIntOrNull(),
        contextWindow = contextWindow.trim().toIntOrNull(),
        temperature = temperature.trim().toDoubleOrNull(),
        isVisionModel = isVision,
        isReasoningModel = isReasoning,
        replayThinking = replayThinking,
        reasoningLevels = if (isReasoning) ReasoningLevels.filter { it in levels } else emptyList(),
        thinkingLevelMap = if (isReasoning) levelMap.filterKeys { it in levels } else emptyMap(),
        reasoningEffort = if (isReasoning) defaultEffort else null,
        frequencyPenalty = frequencyPenalty.trim().toDoubleOrNull(),
        presencePenalty = presencePenalty.trim().toDoubleOrNull(),
        topP = topP.trim().toDoubleOrNull(),
        seed = seed.trim().toIntOrNull(),
    )

    /** 把探测到的能力写回编辑表单：上下文、图片输入、公开的思考档位与服务商默认档位。 */
    fun applyProbedModel(probed: ProbedModel, inferredContextWindow: Int?) {
        probed.contextWindow?.let { contextWindow = it.toString() }
        // 只在服务商明确声明支持图片时勾上；未公开则保留用户原来的选择。
        if (probed.supportsImage == true) isVision = true
        if (probed.reasoningLevels.isNotEmpty()) {
            isReasoning = true
            levels = probed.reasoningLevels.toSet()
            val preferred = probed.defaultReasoningLevel
            val current = defaultEffort
            defaultEffort = when {
                preferred != null && preferred in probed.reasoningLevels -> preferred
                current != null && current !in probed.reasoningLevels -> ReasoningAuto
                else -> current
            }
        }
        probeMessage = probeSummary(appContext, probed, inferredContextWindow)
    }

    val listModels: () -> Unit = {
        listingModels = true
        probeMessage = null
        probeScope.launch {
            val result = ModelProbe.listModels(
                effectiveTransport, baseUrl.trim(), apiKey.trim(),
            )
            listingModels = false
            probeMessage = when {
                result.error != null -> appContext.getString(
                    R.string.settings_model_probe_list_failed, result.error,
                )
                result.models.isEmpty() ->
                    appContext.getString(R.string.settings_model_probe_list_empty)
                else -> null
            }
            if (result.models.isNotEmpty()) modelPicker = result.models
        }
    }

    val probeModel: () -> Unit = {
        probing = true
        probeMessage = null
        val askedModel = model.trim()
        probeScope.launch {
            val result = ModelProbe.probe(
                effectiveTransport, baseUrl.trim(), apiKey.trim(), askedModel,
            )
            probing = false
            val found = result.model
            when {
                result.error != null -> probeMessage = appContext.getString(
                    R.string.settings_model_probe_failed, result.error,
                )
                found == null -> probeMessage = appContext.getString(
                    R.string.settings_model_probe_not_listed,
                    result.inferredContextWindow ?: ModelCatalog.contextWindow(askedModel),
                )
                else -> applyProbedModel(found, result.inferredContextWindow)
            }
        }
    }

    Scaffold(
        topBar = {
            KairTopBar(
                title = {
                    Text(
                        stringResource(
                            if (initial.model.isBlank()) R.string.settings_models_new else R.string.settings_models_edit,
                        ),
                    )
                },
                navigationIcon = {
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
                },
                actions = {
                    // 必填项缺失或数值格式非法时置灰不可点，避免点了才发现存不进去。
                    TextButton(
                        onClick = { onSave(buildModel()) },
                        enabled = canSave,
                    ) { Text(stringResource(R.string.action_save)) }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ProviderSection(
                name = name,
                onNameChange = { name = it },
                providerName = def.displayName,
                showsProtocol = providerId == "openai",
                transportName = transportName,
                onOpenProviderPicker = { showProviderPicker = true },
                onOpenProtocolPicker = { showProtocolPicker = true },
            )

            EndpointSection(
                baseUrl = baseUrl,
                onBaseUrlChange = { input ->
                    // 直接粘贴完整端点（.../chat/completions 等）时自动识别协议并收回根地址；
                    // 手动选过协议则以手动选择为准。
                    val inferred = if (!protocolPinned) inferEndpoint(input) else null
                    baseUrl = inferred?.baseUrl ?: input
                    if (inferred != null) transportId = inferred.transportId
                },
                apiKey = apiKey,
                onApiKeyChange = { apiKey = it },
                needsKey = def.needsKey,
                model = model,
                onModelChange = { model = it },
                listingModels = listingModels,
                probing = probing,
                probeMessage = probeMessage,
                onListModels = listModels,
                onProbe = probeModel,
            )

            LimitsSection(
                maxTokens = maxTokens,
                onMaxTokensChange = { maxTokens = it },
                errorMaxTokens = errorMaxTokens,
                contextWindow = contextWindow,
                onContextWindowChange = { contextWindow = it },
                errorContextWindow = errorContextWindow,
                temperature = temperature,
                onTemperatureChange = { temperature = it },
                errorTemperature = errorTemperature,
            )

            CapabilitySection(
                isVision = isVision,
                onVisionChange = { isVision = it },
                isReasoning = isReasoning,
                onReasoningChange = { isReasoning = it },
                replayThinking = replayThinking,
                onReplayThinkingChange = { replayThinking = it },
                levels = levels,
                onLevelsChange = { levels = it },
                defaultEffort = defaultEffort,
                onDefaultEffortChange = { defaultEffort = it },
                levelMap = levelMap,
                onLevelMapChange = { levelMap = it },
            )

            AdvancedSamplingSection(
                isOpenAi = isOpenAi,
                frequencyPenalty = frequencyPenalty,
                onFrequencyPenaltyChange = { frequencyPenalty = it },
                errorFrequencyPenalty = errorFrequencyPenalty,
                presencePenalty = presencePenalty,
                onPresencePenaltyChange = { presencePenalty = it },
                errorPresencePenalty = errorPresencePenalty,
                topP = topP,
                onTopPChange = { topP = it },
                errorTopP = errorTopP,
                seed = seed,
                onSeedChange = { seed = it },
                errorSeed = errorSeed,
            )
        }
    }

    if (showProviderPicker) {
        ProviderPickerDialog(
            onPick = { p ->
                // 换提供商时无条件换成该家的默认地址：否则从 OpenAI 切到
                // LongCat / Gemini 时会残留上一家的 URL。点当前项不覆盖手改值。
                if (p.id != providerId) {
                    baseUrl = p.defaultBaseUrl
                    // 切到自定义时协议回到默认，避免沿用上一条配置的 Responses/Anthropic。
                    if (p.id == "openai") {
                        transportId = TransportIds.OPENAI_CHAT
                        protocolPinned = false
                    }
                }
                providerId = p.id
                showProviderPicker = false
            },
            onDismiss = { showProviderPicker = false },
        )
    }

    modelPicker?.let { models ->
        ModelPickerDialog(
            models = models,
            onPick = { m ->
                model = m.id
                // 列表里已经带了上下文/思考档位，选中即预填，省一次探测。
                applyProbedModel(m, null)
                modelPicker = null
            },
            onDismiss = { modelPicker = null },
        )
    }

    if (showProtocolPicker) {
        ProtocolPickerDialog(
            onPick = { id ->
                // 只有当前地址还是「内置默认值」时才跟着换，手填的代理地址保留。
                val knownDefaults = ModelCatalog.providers.map { it.defaultBaseUrl }.toSet()
                if (baseUrl.isBlank() || baseUrl in knownDefaults) {
                    baseUrl = when (id) {
                        TransportIds.ANTHROPIC_MESSAGES -> "https://api.anthropic.com"
                        else -> "https://api.openai.com/v1"
                    }
                }
                transportId = id
                protocolPinned = true
                showProtocolPicker = false
            },
            onDismiss = { showProtocolPicker = false },
        )
    }
}

/** 把探测结果拼成用户可读的摘要；文案全部来自 strings.xml。 */
private fun probeSummary(context: Context, model: ProbedModel, inferredContextWindow: Int?): String {
    val parts = mutableListOf<String>()
    val window = model.contextWindow
    if (window != null) {
        parts += context.getString(R.string.settings_model_probe_context, window)
    } else if (inferredContextWindow != null) {
        parts += context.getString(R.string.settings_model_probe_context_inferred, inferredContextWindow)
    }
    if (model.reasoningLevels.isNotEmpty()) {
        parts += context.getString(
            R.string.settings_model_probe_reasoning,
            model.reasoningLevels.joinToString(" / "),
        )
        model.defaultReasoningLevel?.let {
            parts += context.getString(R.string.settings_model_probe_reasoning_default, it)
        }
    } else if (model.reasoningSupported == true) {
        parts += context.getString(R.string.settings_model_probe_reasoning_no_levels)
    } else if (model.reasoningSupported == false) {
        parts += context.getString(R.string.settings_model_probe_reasoning_unsupported)
    } else {
        parts += context.getString(R.string.settings_model_probe_reasoning_unknown)
    }
    if (model.supportsImage == true) {
        parts += context.getString(R.string.settings_model_probe_vision)
    }
    if (parts.isEmpty()) parts += context.getString(R.string.settings_model_probe_empty)
    return parts.joinToString("\n")
}
