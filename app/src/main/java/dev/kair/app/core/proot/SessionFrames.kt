package dev.kair.app.core.proot

/**
 * 帧解析：一行输出是不是控制帧（就绪 / 退出码），普通输出返回 null。
 *
 * 抽到类外只是为了 `ContainerSession` 的体量线（那里的登记值只准减）；协议本身见那个类的 KDoc。
 */
internal fun parseSessionFrame(line: String): ContainerSession.Frame? = when {
    line == ContainerSession.READY_TOKEN -> ContainerSession.Frame.Ready
    line.startsWith(ContainerSession.RC_PREFIX) &&
        line.endsWith(ContainerSession.RC_SUFFIX) &&
        line.length > ContainerSession.RC_PREFIX.length + ContainerSession.RC_SUFFIX.length ->
        line.substring(ContainerSession.RC_PREFIX.length, line.length - ContainerSession.RC_SUFFIX.length)
            .toIntOrNull()
            ?.let { ContainerSession.Frame.Exit(it) }

    else -> null
}
