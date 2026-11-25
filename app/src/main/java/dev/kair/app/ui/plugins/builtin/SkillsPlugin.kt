package dev.kair.app.ui.plugins.builtin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.kair.app.R
import dev.kair.app.app.AppContainer
import dev.kair.app.core.plugin.PanelContribution
import dev.kair.app.core.plugin.Plugin
import dev.kair.app.core.plugin.PluginContext
import dev.kair.app.core.skills.Skill
import dev.kair.app.core.skills.SkillRegistry
import dev.kair.app.core.skills.SkillTool
import dev.kair.app.core.skills.SkillsContributor
import dev.kair.app.ui.icons.PluginGlyph
import java.io.File

/**
 * 技能插件：`skills/` 目录扫描 + 系统提示索引段 + `skill` 工具。
 *
 * 渐进式披露 —— 只有「名称 + 描述」进入缓存稳定前缀；正文由模型按需
 * 通过 `skill` 工具读入（尾部追加，不破坏前缀）。
 */
class SkillsPlugin(private val container: AppContainer) : Plugin {
    override val id = "dev.kair.plugin.skills"
    override val name = "技能"
    override val description = "SKILL.md 渐进式披露：索引进系统提示，正文按需加载。"

    override fun activate(ctx: PluginContext) {
        val registry: SkillRegistry = container.skills
        registry.addRoot(File(container.storageRoot, "skills"))
        registry.addBuiltin(PLUGIN_AUTHORING)
        ctx.systemPrompt(SkillsContributor(registry))
        ctx.tool(SkillTool(registry))
        ctx.panel(
            PanelContribution(
                id = "panels.skills",
                title = "技能",
                icon = PluginGlyph.Spark,
                order = 30,
                content = { SkillsPanel(container) },
            ),
        )
    }
}

/** 技能面板：列出已注册技能与其来源；只读观测面。 */
@Composable
private fun SkillsPanel(container: AppContainer) {
    // 注册表无流；面板每次进入重读一次即可（装/卸技能是低频操作）。
    val skills = container.skills.list()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            stringResource(
                R.string.skills_root_hint,
                File(container.storageRoot, "skills").absolutePath,
            ),
            style = MaterialTheme.typography.labelSmall,
        )
        if (skills.isEmpty()) {
            Text(stringResource(R.string.skills_empty), style = MaterialTheme.typography.bodyMedium)
            return
        }
        skills.forEach { s ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(s.name, style = MaterialTheme.typography.titleSmall, fontFamily = FontFamily.Monospace)
                    Text(
                        s.description.ifBlank { stringResource(R.string.skills_no_description) },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        stringResource(R.string.skills_body_chars, s.body.length),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    s.dir?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 内置技能：教 agent 写动态插件。这正是「文档即能力」—— 插件 API 不进
 * 系统提示前缀，模型需要时才读这份正文。只需覆盖常见形态，更细的契约
 * 由模型按需读源码 / `docs/插件接口契约.md` 补齐。
 */
private val PLUGIN_AUTHORING = Skill(
    name = "plugin-authoring",
    description = "Write and install Kair dynamic plugins (manifest.json + scripts): tools, prompts, skills, approval categories, and declarative settings panels. Prefer an existing MCP server over a new plugin when one is available.",
    body = """
        # Authoring dynamic plugins

        A dynamic plugin is a directory of **data + scripts** — no compiled code is loaded.

        ```
        plugins/<id>/
          manifest.json            # required
          my_tool.sh               # referenced by tools[].script
          skills/<name>/SKILL.md   # optional skill root (if manifest declares "skills")
        ```

        ## Check MCP first

        If the capability already exists as an MCP (Model Context Protocol) server, install it
        instead of writing a plugin: `mcp_manage { "action": "add", "name": "...", "type": "http",
        "url": "..." }`, or a stdio server whose command runs inside the Linux container.
        `mcp_list` shows connection status and the tools it exposes; they appear to you as
        `mcp__<server>__<tool>`. Write a plugin only when no MCP server fits or you need a
        workspace-specific script.

        ## manifest.json

        ```json
        {
          "id": "my-plugin",
          "name": "My Plugin",
          "description": "What it adds",
          "version": "0.1.0",
          "kind": "script",
          "tools": [
            {
              "name": "my_tool",
              "description": "What the tool does, shown to the model",
              "script": "my_tool.sh",
              "parameters": { "type": "object", "properties": {}, "required": [] },
              "snippet": "one-liner for the system prompt tool list",
              "guidelines": ["a behavioral rule for the model"],
              "category": "write",
              "approvableForever": true,
              "timeoutSeconds": 30
            }
          ],
          "prompts": [
            { "id": "my.prompt", "order": 50, "text": "Static system-prompt section." }
          ],
          "skills": "skills",
          "settings": [
            { "key": "enabled", "label": "Enable thing", "type": "toggle", "defaultBool": true },
            { "key": "limit", "label": "Limit", "type": "number", "defaultInt": 10, "min": 1, "max": 100 },
            { "key": "mode", "label": "Mode", "type": "choice", "defaultText": "a", "options": [{ "value": "a", "label": "A" }] }
          ],
          "settingsTitle": "My Plugin",
          "settingsShowInSettings": false
        }
        ```

        Rules:
        - `id` **must equal** the directory name.
        - `kind`: `"script"` (default; data + scripts) or `"dex"` (compiled plugin — see below).
        - A tool whose `name` matches a built-in tool (e.g. `container_shell`) replaces it.
        - `prompts[].text` is static and deterministic — it joins the cache-stable prefix.
        - `parameters` is a JSON Schema object; omit it for no-argument tools.
        - `category` classifies the tool for the approval panel: `read` (does not ask by
          category; the "all tools" switch still overrides), `write`, `delete`, or `execute`.
          The user's approval switches for that category decide whether it pauses. **Always
          declare a category**: a tool without one requires approval on every call, and the
          user cannot turn that off from the approval panel.
        - Do not write `"requiresApproval": false` — it is the default and does not disable any
          approval; once a `category` is declared, the user's category switches decide. Only set
          `"requiresApproval": true` when the tool must ask on every call even after the user
          turns its category switch off; pair that with `"approvableForever": false` for
          dangerous tools that must never be permanently allowed.

        ## Compatibility gates

        Before loading, the host checks three things: the plugin `id` must not collide with a
        built-in plugin id; a dex plugin's `targetApi` must not exceed the host's plugin API
        version; and the optional `minKairVersion` / `maxKairVersion` (`major.minor.patch`,
        inclusive) must cover the host's Kair version. In strict mode (the default, a Settings
        switch) a failed check aborts the install with the reasons shown to the user; the user can
        force-install anyway, which loads the plugin and lists those reasons as warnings on the
        plugin page. Assume refusal, not force-install: declare a range only when the plugin really
        needs it - a `maxKairVersion` pin makes it stop installing after the next app update.

        The check runs after `plugins/<id>/` already holds your files, so a refused install leaves
        that directory in place (it shows up in the plugin list, just not loaded). Fix the files
        there and install again - do not assume the directory was rolled back.

        Dex plugins must also set `entryClass` (a fully-qualified class implementing
        `dev.kair.plugin.api.Plugin`) and must not declare `tools` / `prompts` / `skills` in the
        manifest — capabilities come from `activate()`. Either mistake fails the install by itself,
        unrelated to the version gates above.

        ## Declarative settings panel

        `settings[]` declares fields; the host renders them with built-in controls
        (`toggle` / `number` / `text` / `choice`). The plugin never touches UI code — values
        are stored per `key` and handed to your script via `KAIR_SETTINGS`. Set
        `settingsShowInSettings: true` to also surface the same panel on the Settings screen.

        ## Script contract

        The script runs via `sh -c` through the current command backend (Ubuntu Linux
        when the proot workspace is ready; Android toybox otherwise).

        - `KAIR_TOOL` — the tool name.
        - `KAIR_ARGS` — the call arguments as a JSON object string.
        - `KAIR_SETTINGS` — this plugin's stored settings as a JSON object string.
        - stdout + stderr become the tool result.
        - exit code 0 = success; non-zero = error shown to the model.
        - Prefer `grep`/`sed` for JSON field extraction unless `jq` is known to be installed.

        ## Install / uninstall

        1. Write all plugin files under `plugins/<id>/` in the workspace (use `write`).
        2. Call `install_plugin` with `{ "id": "<id>" }` — user approval is required.
           If the user denies, do NOT retry; explain and ask them how to proceed.
        3. `uninstall_plugin { "id": "<id>" }` disables it; files are kept.

        Installed plugins persist across restarts. `install_plugin` itself cannot be
        overridden by a plugin.

        ## Packaging & distribution (`.kplug`)

        To hand a finished plugin to the user (or to another device) as a single file, zip it
        into a `.kplug` package. **Both kinds use this format** — a script plugin is packaged
        exactly the same way as a dex plugin; `.kplug` is not an alias for "compiled plugin".

        ```
        my-plugin-0.1.0.kplug     # zip; manifest.json at the package ROOT
          manifest.json           # kind: "script" or "dex"
          my_tool.sh              # script assets, referenced by tools[].script
          skills/<name>/SKILL.md  # when manifest.skills points at "skills"
          LICENSE                 # optional
        ```

        Rules:
        - Zip the *contents* of the plugin directory, not the directory itself. A wrapper
          folder (`my-plugin/manifest.json`) makes the package invalid; renaming a `.zip`
          built that way to `.kplug` does not fix it.
        - `manifest.id` must equal the target plugin directory name, so the archive root
          carries no `id` folder of its own — the host extracts it into `plugins/<id>/`.
        - Name the file `<id>-<version>.kplug` and keep `manifest.version` in sync; that
          version is what the plugins list shows, and reinstalling replaces the old copy.
        - Include only what the plugin needs at runtime. Do not package settings values,
          caches, generated output, `.git`, or workspace files — settings live in the host,
          keyed by plugin id.
        - The user installs it themselves: **Plugins → 安装 → pick the `.kplug` file**. This
          path is an explicit user action, so it skips the agent-side approval gate. Any
          archive works for the picker (it is renamed internally), but the root-manifest
          rule still applies.
        - `install_plugin { "source": ... }` is stricter: the file name must end in
          `.kplug`, otherwise the host treats it as a directory and fails.
        - Packaging is additive — it does not replace `install_plugin`. Use packaging when
          the user asked for a file to keep/share; install directly when they just want it
          running on this device.

        ## Skills

        A skill is `skills/<name>/SKILL.md` with optional frontmatter:

        ```
        ---
        name: my-skill
        description: When the model should use it
        ---
        Full instructions...
        ```

        Only `name` + `description` enter the system prompt index; the body is loaded
        on demand via the `skill` tool. A dynamic plugin contributes skills by pointing
        `manifest.skills` at a directory inside itself.

        ## Compiled plugins (`kind: "dex"`)

        Only for code plugins that must add a transport layer, replace a singleton backend
        (storage / context strategy / command runner), install middleware hooks, or contribute
        declarative UI slots. They ship a `classes.dex` implementing `dev.kair.plugin.api.Plugin`;
        the full contract lives in `docs/插件接口契约.md`. Prefer a script plugin — compiling a
        dex in-session is rarely practical. Compiled tools must also declare `Tool.category`;
        leaving it `UNSPECIFIED` makes every call require approval.
    """.trimIndent(),
)
