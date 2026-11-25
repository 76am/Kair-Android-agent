package dev.kair.app.ui.screens.noproject

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.kair.app.R
import dev.kair.app.app.AppContainer
import dev.kair.app.app.ProjectOpResult
import dev.kair.app.app.createProject
import dev.kair.app.core.storage.Project
import dev.kair.app.ui.components.KairTopBar
import dev.kair.app.ui.components.ProjectNameDialog

/**
 * 无项目页（U116）：一个项目都没有时的唯一落点。
 *
 * 旧首页（项目下拉 + 插件 / 设置 + 迷你输入行）已删除；这里换成会话页同一套外壳 ——
 * ☰ 拉开项目 / 会话抽屉，中间只留创建引导。没有项目就不给输入板：消息无处可去，
 * 先建项目再对话。
 *
 * 新建项目成功即交给宿主换到该项目的新会话页，不留在本页，也不补建空会话。
 */
@Composable
internal fun NoProjectScreen(
    container: AppContainer,
    onOpenDrawer: () -> Unit,
    onProjectCreated: (Project) -> Unit,
) {
    var showCreate by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            KairTopBar(
                title = { Text(stringResource(R.string.app_name), maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(Icons.Filled.Menu, contentDescription = stringResource(R.string.cd_sessions))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                stringResource(R.string.projects_none_title),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.size(6.dp))
            Text(
                stringResource(R.string.onboarding_s0_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.size(18.dp))
            Button(onClick = { showCreate = true }) {
                Icon(Icons.Filled.Add, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.projects_new))
            }
        }
    }

    if (showCreate) {
        ProjectNameDialog(
            title = stringResource(R.string.projects_new),
            initialName = "",
            confirmLabel = stringResource(R.string.action_create),
            onDismiss = { showCreate = false },
            onConfirm = { raw ->
                when (val result = container.createProject(raw)) {
                    is ProjectOpResult.Ok -> {
                        container.settings.lastProjectId = result.project.id
                        onProjectCreated(result.project)
                        null
                    }

                    is ProjectOpResult.Failed -> result.error
                }
            },
        )
    }
}
